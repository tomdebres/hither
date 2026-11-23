# Privacy

Hither for Apple TV is a client for a Seerr server that you or someone you know runs. It has no
server of its own and no account system of its own. This page says what the app keeps and what
it sends.
<!-- repository only -->
It is the text a store listing's privacy policy points at, and it changes in the same pull
request as any code that would make it untrue. Hither for Android has its own page
(`docs/privacy.md`), with the same rules. The app shows this file itself, without its notes
for readers of the repository, such as this one.
<!-- /repository only -->

## What the app stores, on the device only

- **The server's address**, as you typed it when signing in.
- **Your sign-in for that server**, by the way you signed in: for the server's own account, the
  email address and the password you entered; for a Jellyfin or Emby account, the username and
  the password you entered; for Plex or Jellyfin's Quick Connect, no password and no Plex token,
  only which way you signed in. The password is kept so the app can sign in again when the
  server's session ends. All of it is kept in the Apple TV's Keychain, for this device only,
  never synced to iCloud; the password is never written to a log. A Plex or Quick Connect
  sign-in cannot be renewed that way: when its session ends, you sign in again.
- **The session cookie** the server issues after sign-in (`connect.sid`), in the Keychain
  beside them, on the same terms.
- **Plex's client identifier**, only once you have used Plex sign-in: a random identifier made
  on the Apple TV the first time, which plex.tv uses to tell this TV apart from your other
  devices. It identifies nothing but this installation of Hither, is not a secret, and is kept
  in the app's own settings on the device; it stays when you sign out, and deleting the app
  removes it.
- **The TV's admin account, only if someone sets one up** (Settings › Admin Account, for
  approving the household's requests from the TV): that account's email address, password and
  session cookie, its name, a one-way hash of the 4-digit PIN that guards it (never the PIN
  itself) and how many wrong PINs were typed in a row. They are kept in a Keychain item of their
  own, readable only while the Apple TV is in use, for this device only, never synced. Removing
  the admin account, ten wrong PINs in a row, or signing out deletes it.
- **Cached artwork:** the posters it has shown, in its cache directory, which tvOS may clear at
  any time.
- **Where your requests stood when it last looked:** each request's id and stage (pending,
  approved, declined, ready), the changes it has not shown you yet and those since you last
  opened My Requests, the names, years and picture addresses of your titles that are ready or
  have just changed, and when it last asked the server. It keeps these to tell you what changed
  since you last looked and to fill the Top Shelf (below).

Nothing else is kept: no viewing history, no other identifier, no usage counters. The list of your
requests itself is read from the server each time. Hither puts nothing in iCloud, and the
Keychain items it keeps cannot move to another device. Signing out removes all of it but Plex's
client identifier, and empties Hither's Top Shelf; deleting the app removes everything.

## What the app sends, and to whom

- **Requests to the server you named**, and to nothing else apart from plex.tv during a Plex
  sign-in (below). Signing in sends that server what you entered: your email address and
  password, or your Jellyfin or Emby username and password (the server checks them with its
  Jellyfin or Emby). Jellyfin's Quick Connect sends nothing you typed: the server gives a code to
  show, and you approve it in Jellyfin. Browsing, searching and requesting send what those
  actions need: a search query, the id of a film or series, the seasons chosen, the words of an
  issue you report.
- **plex.tv, only while a Plex sign-in is on screen.** When you choose Plex, Hither asks plex.tv
  for a code to show you, then asks plex.tv every few seconds whether you have entered it at
  plex.tv/link, until you have, the code runs out, or you cancel. Leaving the code's screen, or
  Hither leaving the TV's screen, cancels it. plex.tv receives the product name "Hither", a
  random client identifier Hither makes for this Apple TV the first time (kept on the device
  until the app is deleted, signing out included), and the code's own number; the connection
  itself tells plex.tv the Apple TV's internet address. Nothing about you, your server or your
  requests is sent there. Once you enter the code, plex.tv gives Hither a Plex sign-in token for
  your Plex account; Hither passes it to your server once, as Seerr's own sign-in page does, and
  keeps no copy. The server keeps it as your Plex token. plex.tv then lists "Hither" among your
  Plex account's authorised devices, where you can remove it. plex.tv is Plex's service, under
  Plex's own privacy policy.
- **Artwork** is fetched through that same server's image proxy.
- **While you are signed in, Hither asks the server about your requests when you open it, and in
  the background when tvOS lets it, no more often than every 30 minutes**, so that what is ready
  reaches the Top Shelf without you looking for it.
- The connection is whatever the address says. A self-hosted Seerr is often plain `http` on a
  home network; over `http`, anyone on that network can read the traffic, your password (or a
  Plex token) included. Use an `https` address when the server is reached over a network you do
  not control. plex.tv is always reached over `https`.

There is no analytics, no crash reporting, no advertising and no third-party SDK that
phones home. The developer of Hither receives nothing from the app.

## On the Apple TV's Home Screen

When Hither is in the top row of the Home Screen, its Top Shelf shows your requested titles that
are ready to watch: the ten newest, under "Ready to watch". What goes there is each title's name,
its poster and a link that opens it in Hither. They are kept in a folder only Hither and its Top
Shelf extension can read, and tvOS draws them. Signing out removes them.

## What the server does with it

The Seerr server you sign in to keeps your account and your requests and has its own
settings and logs; that is between you and whoever runs it. Film and series information and
artwork come to the server from TMDB.
<!-- repository only -->
See `docs/attribution.md`.

## Changes

This file's history in the repository is the record of changes.
<!-- /repository only -->
