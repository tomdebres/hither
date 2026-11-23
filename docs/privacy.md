# Privacy

Hither is a client for a Seerr server that you or someone you know runs. It has no server of
its own and no account system of its own. This page says what the app keeps and what it sends;
it is the text a store listing's privacy policy points at, and it changes in the same pull
request as any code that would make it untrue.

## What the app stores, on the device only

- **The server's address**, as you typed it when signing in.
- **Your sign-in for that server**, by the way you signed in: for the server's own account, the
  email address and the password you entered; for a Jellyfin or Emby account, the username and
  the password you entered; for Plex or Jellyfin's Quick Connect, no password and no Plex token,
  only which way you signed in. The password is kept so the app can sign in again when the
  server's session ends. It is encrypted with a key held in the Android Keystore and never
  written to a log. A Plex or Quick Connect sign-in cannot be renewed that way: when its
  session ends, you sign in again.
- **The session cookie** the server issues after sign-in (`connect.sid`).
- **Plex's client identifier**, only once you have used Plex sign-in: a random identifier made
  on the device the first time, which plex.tv uses to tell this TV apart from your other
  devices. It identifies nothing but this installation of Hither, and it stays when you sign
  out; clearing the app's data or uninstalling it removes it.
- **Cached artwork:** the posters it has shown, in its cache directory, which Android may
  clear at any time.
- **Where your requests stood when it last looked:** each request's id and stage (pending,
  approved, declined, ready), the changes it has not shown you yet and those since you last
  opened My Requests, the names, years and picture addresses of your titles that are ready or
  have just changed, when you last opened one of them in Hither, when it last asked the server,
  and, for an account allowed to approve requests, how many requests on the server wait for
  approval. It keeps these to tell you what changed since you last looked and to fill the TV's
  home screen (below).

Nothing else is kept: no viewing history, no other identifier, no usage counters. The list of your
requests itself is read from the server each time. Android backup is switched off for
the app (`android:allowBackup="false"`), so none of this is copied to a cloud backup or to
another device. Signing out, clearing the app's data or uninstalling it removes all of it, and
signing out also takes Hither's cards off the home screen.

## What the app sends, and to whom

- **Requests to the server you named**, and to nothing else apart from plex.tv during a Plex
  sign-in (below). Signing in sends that server your email address and password, or your
  Jellyfin or Emby username and password (the server checks them with its Jellyfin or Emby).
  Browsing, searching and requesting send what those actions need: a search query, the id of
  a film or series, the seasons chosen.
- **plex.tv, only while a Plex sign-in is on screen.** When you choose Plex, Hither asks plex.tv
  for a code to show you, then asks plex.tv every few seconds whether you have entered it at
  plex.tv/link, until you have, the code runs out, or you cancel. plex.tv receives the product
  name "Hither", Hither's client identifier for this TV (above), and the code's own number; the
  connection itself tells plex.tv the TV's internet address. Nothing about you, your server or
  your requests is sent there. Once you enter the code, plex.tv gives Hither a Plex sign-in
  token for your Plex account; Hither passes it to your server once, as Seerr's own sign-in page
  does, and keeps no copy. The server keeps it as your Plex token. plex.tv then lists "Hither"
  among your Plex account's authorised devices, where you can remove it. plex.tv is Plex's
  service, under Plex's own privacy policy.
- **Quick Connect** sends nothing beyond your server: the server asks its Jellyfin for the code,
  and you approve it in Jellyfin yourself.
- **Artwork** is fetched through that same server's image proxy.
- **While you are signed in, Hither asks the server about your requests every 30 minutes in the
  background**, and when you open it, so that what is ready reaches the home screen without you
  looking for it.
- The connection is whatever the address says. A self-hosted Seerr is often plain `http` on a
  home network; over `http`, anyone on that network can read the traffic, your password and
  a Plex token included. Use an `https` address when the server is reached over a network you
  do not control. plex.tv is always reached over `https`.

There is no analytics, no crash reporting, no advertising and no third-party SDK that
phones home. The developer of Hither receives nothing from the app.

## On the TV's home screen

Hither puts your requested titles that are ready to watch on the TV's home screen: a "Ready to
watch" channel (the ten newest) and, for those that became ready in the last 14 days and that
you have not opened in Hither since, Android's Watch Next ("Play Next", or a launcher's Continue
Watching). What goes there is each title's name, year, a poster and a backdrop (addresses on your
server's image proxy) and a link that opens it in Hither. Android keeps them in its TV listings,
where the TV's launcher shows them and where other apps allowed to read TV listings can read
them. Signing out removes them.

## What the server does with it

The Seerr server you sign in to keeps your account and your requests and has its own
settings and logs; that is between you and whoever runs it. Film and series information and
artwork come to the server from TMDB (see `docs/attribution.md`).

## Changes

This file's history in the repository is the record of changes.
