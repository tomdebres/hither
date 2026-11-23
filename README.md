# Hither

A TV client for [Seerr](https://github.com/seerr-team/seerr) servers, on Android TV and Apple TV: browse what's
trending, search, and request films and series from the sofa, built for a remote's D-pad rather than a mouse.

**Hither is independent.** It is not made by, affiliated with or endorsed by the Seerr project. It talks to a Seerr
server that you or someone you know runs, through Seerr's public API, and has no server of its own.

## What it does

- **Discover:** recent requests, trending, popular films and series, and what's coming; each popular list in full,
  with sort and filters.
- **Search**, and a page for each film, series and person: the overview, cast and crew, seasons, and related titles.
- **Requests:** a film in one press; a series by season, in 4K where the server allows. Your requests and their
  status, with a long press to cancel one still pending.
- **Issues:** report a problem with a title, and follow the ones reported.
- **Approving**, for a household's admin: a separate admin sign-in behind a PIN, with the queue of requests waiting.
- **On the home screen:** Android TV's "Ready to watch" channel and Watch Next; the Apple TV's Top Shelf.
- **Sign-in:** a Seerr account, a Jellyfin or Emby account, Jellyfin's Quick Connect, or Plex by code.

The Android app follows the Apple TV app's design, built from Compose for TV's components. The two apps share one
engine, `core` (Kotlin Multiplatform): the Seerr client, the models and the request rules. Each platform's screens are
its own.

## Building

**Android TV** (Android 9 or later): JDK 21 and the Android SDK.

```sh
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
```

**Apple TV** (tvOS 18 or later): a Mac with Xcode and [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```sh
./gradlew :core:assembleHitherCoreReleaseXCFramework   # the shared engine, for tvOS
scripts/tvos-app build                                  # the app, for the simulator
```

**Tests:** `./verify --fast` runs the unit tests and the repository's own checks (about a minute); `./verify` adds the
builds, lint and, on a Mac, the tvOS app's tests. The Android device tests run on an Android TV emulator with
`./gradlew connectedDebugAndroidTest`. The tests run against `scripts/fake-seerr`, a scripted stand-in for a Seerr
server, never a real one.

## Privacy and credits

- [`docs/privacy.md`](docs/privacy.md) (Android) and [`docs/privacy-tvos.md`](docs/privacy-tvos.md) (Apple TV) say what
  the app keeps on the device and what it sends: only to your Seerr server, and to plex.tv if you sign in with Plex.
- Film and series data comes from [TMDB](https://www.themoviedb.org) through Seerr. This product uses the TMDB API but
  is not endorsed or certified by TMDB ([`docs/attribution.md`](docs/attribution.md)).
- [`THIRD-PARTY.md`](THIRD-PARTY.md) lists the code, fonts and marks Hither uses and their licences.

## Development

Hither is developed in a private repository; this one receives a copy with each release. Issues are welcome here.
Pull requests are welcome too: they are carried over into the private repository by hand, and land here with the
next release. Comments in the code that cite a numbered decision ("DECISIONS 365") refer to the maintainers' design
log, which is not published.

## Licence

[MIT](LICENSE).
