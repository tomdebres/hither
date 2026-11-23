# Fixtures: what a real Seerr sent

Recorded on 2026-11-10 from a Seerr 3.3.0 with Plex as its media server, signed in as a local
request-only user, over read-only endpoints. `FixtureContractTest` parses every file with the
models in `core` and runs the request rules over them; `SeerrClientTest` replays some of them
through the client; `scripts/fake-seerr` serves them to the app on an emulator.

| File | Endpoint |
| --- | --- |
| `settings-public.json` | `GET /settings/public` |
| `auth-local.json` | the body of a successful `POST /auth/local` |
| `auth-me.json` | `GET /auth/me` |
| `discover-trending.json`, `discover-movies.json`, `discover-tv.json`, `discover-movies-upcoming.json` | the four discover rows |
| `search.json` | `GET /search?query=…` |
| `movie.json`, `tv.json` | `GET /movie/{id}`, `GET /tv/{id}` for titles nobody had requested |
| `movie-in-library.json`, `tv-in-library.json` | the same titles carrying a library record |
| `request-list.json` | `GET /request?filter=all` |
| `error-401.json`, `error-403-sign-in.json` | a call without a session; a wrong password |

## What was changed before they were committed

The structure and the field values are the server's, except:

- **People.** Every user is reduced to an id and placeholders (`User 8`,
  `user8@example.invalid`); settings, tokens and avatars are gone.
- **Addresses and instance identifiers.** The application URL, the media server's and the
  download services' URLs (`…example.invalid`), the Plex client identifier, rating keys and
  service slugs are replaced. Dates on library and request records are one fixed date.
- **What the household has and asked for.** Titles that were in the library were dropped from
  the lists, and their library records were moved onto other titles in the same response
  (`mediaInfo.tmdbId` rewritten to match). The two `*-in-library` files are the unrequested
  title with another title's library record grafted on; the series' record was set to
  "partially available", since its new host has more seasons than its donor. Requests in
  `request-list.json` point at titles from the trending fixture. So the shapes and statuses are
  real and say nothing about anyone's library.
- **Size.** Lists are cut to eight results, credits to five, videos, keywords and providers to
  a couple.

Raw recordings and the script that did this stay outside the repository. To refresh: record
again from any Seerr, scrub the same way, and let the contract test say what changed.
