# The App Store for tvOS: would Hither be accepted, and what that asks of the build

Reviewed 2026-11-10 against Apple's App Review Guidelines (page dated "Last Updated: October
16, 2026"), the App Store's own listings, and Apple's, JetBrains' and Ktor's documentation.
Each line says whether it was **read** at the source or is an **inference**. Re-read the
guidelines before a submission; they change several times a year.

## Verdict

**Likely yes.** The category is on the store: clients for a self-hosted Seerr, Overseerr or
Jellyseerr are plentiful on iOS, and at least one request client and several Jellyfin clients
are on Apple TV. Nothing found says such an app was refused for what it is. Three things can
still sink a submission, in this order of likelihood:

1. **No way for the reviewer to use it** (2.1): certain rejection without a demo server.
2. **The name and the look** (4.1): the app's first name was one letter from "Seerr", with an
   icon and wordmark adapted from Seerr's logo (DECISIONS 72). The Seerr team declined both on
   2026-11-16, so it is Hither now (H1, DECISIONS 286): its own name, its own mark, and a look
   that follows the Apple TV app's, with Seerr's indigo kept on the prominent buttons.
3. **The listing reading like a download tool** (5.2.3): avoidable with wording.

## What it asks of the build, now

These are the points that change code or decisions before tvOS work starts.

- **About screen, both platforms:** the privacy policy must be reachable inside the app, not
  only on the store page (5.1.1(i), read). tvOS has no browser and no web view, so the text
  itself, or a QR code to it, goes on the screen. The TMDB notice and the "unofficial, not
  affiliated" line go beside it.
- **Sign-in with Plex must be the PIN flow.** tvOS has neither `WKWebView` nor
  `SFSafariViewController` (read). Plex's link-code flow (show a code, poll) is made for this.
  No "Sign in with Apple" is owed for it: 4.8 exempts "a client for a specific third-party
  service" where users sign in to their own account (read).
- **The app must survive losing its stored account.** tvOS gives an app 500 KB of persistent
  local storage and may purge the rest (read, from an archived Apple guide). The Keychain is
  the place for the password and the cookie; Hither already treats "nothing stored" and "will
  not open" as "sign in again", which is the behaviour to keep.
- **Plain `http` to a LAN address needs an App Transport Security exception**, and exceptions
  must be justified at review (read). *Since M7 part 2: on the tvOS 26.5 simulator it needs none, as Apple's ATS page
  says for IP addresses, unqualified names and `.local` (DECISIONS 167); a device check is still owed.* `NSAllowsLocalNetworking` covers unqualified names,
  `.local` and IP addresses on iOS; Apple's pages do not list tvOS for it, so this is a device
  test early in the tvOS milestone, not an assumption. The local-network permission prompt
  does not exist on tvOS (read, Apple TN3179).
- **`core` is on course.** Kotlin/Native's `tvosArm64` and `tvosSimulatorArm64` are Tier 2,
  tvOS 15.0 and later; `tvosX64` is deprecated, so simulators are Apple-silicon only (read).
  Ktor's Darwin engine, kotlinx.serialization, coroutines and Preferences DataStore all
  publish tvOS artefacts at the versions this repo uses (read, from the published module
  metadata). One difference: the Darwin engine has no connect timeout, only request and
  socket timeouts, so `SeerrClient`'s 10 s connect timeout will not apply there.
- **A demo backend is a deliverable**, not an afterthought (next section).
- **Nothing about torrents, usenet or downloading in the app's own words**, on either
  platform: Seerr's vocabulary (request, requested, available) is already the right one.

## What the guidelines say

- **2.1(a) App Completeness** (read): "include demo account info (and turn on your back-end
  service!) if your app includes a login. If you are unable to provide a demo account due to
  legal or security obligations, you may include a built-in demo mode in lieu of a demo
  account with prior approval by Apple." So the default is a live server and an account in
  the review notes; a demo mode needs Apple's agreement first. An Apple forum thread shows a
  client for a self-hosted service rejected under 2.1 and asked for "access information".
  Hither needs a Seerr reachable from the internet over `https`, with content and a
  request-only account that does not expire, for as long as the app is in review or on sale.
  Seerr publishes no public demo instance (inference from its site and repository).
- **4.1 Copycats** (read): "Don't simply copy the latest popular app on the App Store, or make
  some minor changes to another app's name or UI and pass it off as your own", and "(c) You
  cannot use another developer's icon, brand, or product name in your app's icon or name,
  without approval from the developer." Seerr is MIT-licensed, so copying its artwork is not a
  licence problem; but from 0.3.0-m3 the app's icon and wordmark were adapted from Seerr's logo
  (DECISIONS 72), which is exactly the "icon, brand" 4.1(c) names, beside a name one letter
  from "Seerr". The Seerr team declined the name and the logo (2026-11-16), and the app became
  Hither with a mark of its own (H1, DECISIONS 286); the listing names Seerr only as the server
  it works with, as the clients below do. The Seerr team has a mobile app in its repository, with an Apple TV build script,
  "not yet available on the app stores" (read): if that ships first, the resemblance gets
  harder to defend. The clients already listed either name the project plainly
  ("Pocket for Seerr", "Ovue for Seerr") or use an unrelated name ("Nestrr", "Vision").
- **5.2.1** (read): no "misleading, false, or copycat representations, names, or metadata".
  Same point as 4.1, for the listing: it keeps the "not made by, affiliated with or endorsed
  by the Seerr project" line the About screen carries.
- **5.2.2 Third-Party Sites/Services** (read): "If your app uses, accesses, monetizes access
  to, or displays content from a third-party service, ensure that you are specifically
  permitted to do so … Authorization must be provided upon request." For Hither that is TMDB
  (`docs/attribution.md`): free with attribution for non-commercial use; a price on the app
  makes it a question for TMDB first.
- **5.2.3 Audio/Video Downloading** (read): "Apps should not facilitate illegal file sharing
  or include the ability to save, convert, or download media from third-party sources". Hither
  downloads nothing and talks to no indexer; it asks the user's own server for a title.
  Radarr and Sonarr managers and a score of Seerr clients are listed, so the rule is not
  applied to the category (inference). The only rejections found were of BitTorrent remote
  controls in 2009 and 2010.
- **2.3.7 Metadata** (read): do not "pack any of your metadata with trademarked terms, popular
  app names". Plex, Jellyfin and Seerr may be named where the app works with them; not as
  keyword stuffing.
- **4.2 Minimum Functionality** (read): no "repackaged website". A native tvOS interface for a
  product whose own answer on TV is a web page is comfortably past this.
- **5.1.1(v)** (read): account deletion is owed only where an app creates accounts. Hither
  creates none.

## What is on the store

Read from Apple's search API and store pages, 2026-11-10 (US storefront).

- **Seerr request clients on iOS, about twenty**, free and paid ($1.99 to $2.99): Pocket for
  Seerr, Vision - Seerr Companion, Ovue for Seerr, SeerGo, Requestr for Seerr, Octopus
  Explorer, ServerrSeerr, ScenePeek, Accio for Plex, and Ombi's own app, among others.
- **On Apple TV:** Nestrr (Seerr requests with Plex and Jellyfin playback; its page says
  "Requires tvOS 15.0"), and the Jellyfin clients Swiftfin, Moonfin and JellyTV. Requestr for
  Seerr's description says it runs on Apple TV.
- **Radarr and Sonarr managers:** Ruddarr, Helmarr, and a dozen more.
- **How they describe themselves:** "requires a self-hosted Seerr, Overseerr or Jellyseerr",
  "does not provide any media", and a line such as "an unofficial app … not affiliated with
  or endorsed by the Seerr project, Jellyfin, Emby or TMDB". Age ratings run from 4+ to 13+.
- No account of how any of them got through review was found: the listings prove they passed,
  not what it took.

## For the submission itself

- Review notes: the demo server's address and account, a sentence on what Seerr is, and that
  the app shows the user's own server's data.
- The ATS exception's justification, in Apple's own terms: the app "must support connecting
  to devices that cannot be upgraded to use secure connections" (a server on a home network).
- Assets: a layered app icon (two to five layers) and a static Top Shelf image (2320 x 720 pt);
  a Top Shelf extension is optional.
- A privacy policy URL in App Store Connect, the same text as `docs/privacy-tvos.md` (the tvOS app's own facts;
  About shows it, DECISIONS 203).
- The Apple Developer Program membership, and an Apple TV to test on.

## Not established

- Any developer's first-hand account of review for this category (none found; Reddit was not
  reachable).
- Whether tvOS applies iOS 17's "no IP addresses by default" ATS rule on a device. **On the simulator it does
  not** (M7 part 2, DECISIONS 167, tvOS 26.5): with no ATS key the app reached a LAN IP address, a `.local` name and
  localhost over http, while a public name over http was refused (-1022). So the app ships with no ATS exception;
  the device test is part 7's first TestFlight build on a real Apple TV.
- Keychain behaviour on tvOS from an Apple page (it is available as far as is known).
- Plex's brand rules for listings (its pages refused the fetch).
- The store name: plain "Hither" is taken on the App Store, so the record (6835203537) is
  "Hither TV"; the name under the icon is "Hither" (Tom, 2026-11-16).
- Google Play's side of the same questions: not reviewed here.
