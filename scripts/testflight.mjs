// App Store Connect for Hither's tvOS TestFlight builds (M7 part 7, DECISIONS 216): the API with the team's key, and the
// step that puts an uploaded build in front of its testers. scripts/tvos-testflight archives and uploads; this reads and
// releases.
//
//   node scripts/testflight.mjs get <path>                 a read, e.g. /v1/apps?filter[bundleId]=com.tomdebres.heerr
//   node scripts/testflight.mjs post|patch <path> <json>   a write: only with the owner's yes for that write
//   node scripts/testflight.mjs status <build>             the build's processing and TestFlight states, and its groups
//   node scripts/testflight.mjs release <build> <group>    wait for processing, then add the build to that internal group
//
// The key comes from the environment, never from the repository: ASC_KEY_ID, ASC_ISSUER_ID and ASC_KEY_PATH (the .p8).
// Exported, not just set: a variable a shell only sets is invisible here, and App Store Connect then answers 401
// NOT_AUTHORIZED, which reads exactly like a bad key (`set -a` around sourcing a file of them).
//
// Uploading is not releasing: a processed build sits at READY_FOR_BETA_TESTING, installable by nobody, until it is in a
// group. Internal groups only (DECISIONS 216): an external group sends the build to beta review and towards the public,
// which waits for the Seerr team's written OK (M9).
import { createSign } from 'node:crypto';
import { readFileSync } from 'node:fs';

const BUNDLE = 'com.tomdebres.heerr';
const POLL_SECONDS = 30;
const GIVE_UP_AFTER = 60 * 60_000; // processing takes minutes; an hour is a failure

function need(name) {
  const value = process.env[name];
  if (!value) {
    console.error(`testflight: ${name} is not in the environment (export ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH)`);
    process.exit(2);
  }
  return value;
}

const key = readFileSync(need('ASC_KEY_PATH'), 'utf8');
const keyId = need('ASC_KEY_ID');
const issuer = need('ASC_ISSUER_ID');

// A token lives 20 minutes at most and a release polls for longer: minted when needed, again after 10 minutes.
let token = { value: null, at: 0 };
function auth() {
  const now = Date.now();
  if (token.value && now - token.at < 10 * 60_000) return token.value;
  const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const iat = Math.floor(now / 1000);
  const signing = `${b64({ alg: 'ES256', kid: keyId, typ: 'JWT' })}.` +
    `${b64({ iss: issuer, iat, exp: iat + 900, aud: 'appstoreconnect-v1' })}`;
  const signer = createSign('SHA256');
  signer.update(signing);
  token = { value: `${signing}.${signer.sign({ key, dsaEncoding: 'ieee-p1363' }).toString('base64url')}`, at: now };
  return token.value;
}

async function api(path, init) {
  const res = await fetch('https://api.appstoreconnect.apple.com' + path, {
    ...init,
    headers: { Authorization: `Bearer ${auth()}`, 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
  });
  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (res.status >= 400) {
    throw new Error(`${init?.method ?? 'GET'} ${path} -> ${res.status} ` +
      (body?.errors ?? []).map((e) => e.detail ?? e.title).join('; '));
  }
  return body;
}

async function app() {
  const apps = await api(`/v1/apps?filter[bundleId]=${BUNDLE}`);
  const found = apps.data?.[0];
  if (!found) throw new Error(`no app record for ${BUNDLE}`);
  return found.id;
}

async function build(appId, version) {
  const builds = await api(`/v1/builds?filter[app]=${appId}&filter[version]=${encodeURIComponent(version)}` +
    '&include=preReleaseVersion,buildBetaDetail,betaGroups&limit=5');
  const found = builds.data?.[0];
  if (!found) return null;
  const included = (type, id) => (builds.included ?? []).find((i) => i.type === type && i.id === id);
  const rel = found.relationships ?? {};
  return {
    id: found.id,
    version: found.attributes.version,
    short: included('preReleaseVersions', rel.preReleaseVersion?.data?.id)?.attributes?.version ?? null,
    platform: included('preReleaseVersions', rel.preReleaseVersion?.data?.id)?.attributes?.platform ?? null,
    processing: found.attributes.processingState,
    expired: found.attributes.expired,
    internal: included('buildBetaDetails', rel.buildBetaDetail?.data?.id)?.attributes?.internalBuildState ?? null,
    groups: (rel.betaGroups?.data ?? []).map((g) => included('betaGroups', g.id)?.attributes?.name ?? g.id),
  };
}

const [command, ...rest] = process.argv.slice(2);
switch (command) {
  case 'get':
    console.log(JSON.stringify(await api(rest[0]), null, 2));
    break;
  case 'post':
  case 'patch':
    console.log(JSON.stringify(await api(rest[0], { method: command.toUpperCase(), body: rest[1] }), null, 2));
    break;
  case 'status': {
    const found = await build(await app(), rest[0]);
    console.log(JSON.stringify(found ?? { version: rest[0], processing: 'not yet visible' }, null, 2));
    break;
  }
  case 'release': {
    const [wanted, groupName] = rest;
    if (!wanted || !groupName) throw new Error('usage: node scripts/testflight.mjs release <build> <group>');
    const appId = await app();
    const groups = await api(`/v1/betaGroups?filter[app]=${appId}&filter[name]=${encodeURIComponent(groupName)}`);
    const group = (groups.data ?? []).find((g) => g.attributes.name === groupName);
    if (!group) throw new Error(`no tester group "${groupName}"`);
    if (!group.attributes.isInternalGroup) throw new Error(`"${groupName}" is an external group: internal groups only`);

    const deadline = Date.now() + GIVE_UP_AFTER;
    let found;
    for (;;) {
      found = await build(appId, wanted);
      // Not visible for the first minute or two is normal; PROCESSING for several more.
      const state = found?.processing ?? 'not yet visible';
      if (state === 'VALID') break;
      if (state === 'FAILED' || state === 'INVALID') throw new Error(`build ${wanted} came back ${state}`);
      if (Date.now() > deadline) throw new Error(`build ${wanted} still ${state} after an hour`);
      console.log(`build ${wanted}: ${state}, waiting…`);
      await new Promise((r) => setTimeout(r, POLL_SECONDS * 1000));
    }
    if (group.attributes.hasAccessToAllBuilds) {
      console.log(`build ${wanted} reaches "${groupName}" by itself (the group has every build)`);
    } else if (!found.groups.includes(groupName)) {
      // From the group's side; bodyops saw that answer 404 for a valid build, and the build's side go through.
      try {
        await api(`/v1/betaGroups/${group.id}/relationships/builds`, {
          method: 'POST', body: JSON.stringify({ data: [{ type: 'builds', id: found.id }] }),
        });
      } catch (e) {
        if (!/-> 404 /.test(e.message)) throw e;
        await api(`/v1/builds/${found.id}/relationships/betaGroups`, {
          method: 'POST', body: JSON.stringify({ data: [{ type: 'betaGroups', id: group.id }] }),
        });
      }
    }
    // Read back, not the POST's word for it.
    console.log(JSON.stringify(await build(appId, wanted), null, 2));
    break;
  }
  default:
    console.error('usage: node scripts/testflight.mjs get <path> | post|patch <path> <json> | status <build> | release <build> <group>');
    process.exit(2);
}
