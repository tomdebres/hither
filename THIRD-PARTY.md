# Third-party code and assets

- **Inter** (Inter 4.001): SIL Open Font License 1.1, Rasmus Andersson. Seerr's own typeface. Neither app bundles it
  now: the tvOS app moved to SF Pro in M7 part 4 (DECISIONS 186), and the Android app to Roboto, Android TV's own, in
  A1 (DECISIONS 293; `app/src/main/res/font/inter_variable.ttf` went). Both apps' logo sets the word "hither" in it, as
  outlines (H1), so Inter's shapes ship in the logo only; About names it.
- **SeerrTV** (github.com/devmesh-git/seerrtv, MIT, copyright 2024 DevMesh and the Seerr
  Community): where a file here is lifted or adapted from it, the file's header says so, and the
  MIT notice below applies to that code. A read-only checkout for reference lives outside the
  repo. Lifted so far: the client's edge cases (`core/SeerrClient.kt`), the models
  (`core/model/`), and the Plex deep link's form (`util/PlexDeepLink.kt`, in
  `core/rules/Plex.kt`).
- **Seerr** (github.com/seerr-team/seerr, MIT, copyright 2020 sct): the API this app talks to (`seerr-api.yml`). No
  code is copied, and since the Android redesign (A1 to A8) neither app follows its layouts; its colours are used:
  Seerr's indigo on prominent buttons and its status colours on both apps, its icon's gradient in Hither's logo.
  Its rating marks (`src/assets/rt_fresh.svg`, `rt_rotten.svg`, `rt_aud_fresh.svg`, `rt_aud_rotten.svg`,
  `services/imdb.svg`) are converted unchanged into `app/src/main/res/drawable/rating_*.xml` for the media page's
  ratings line (M6, DECISIONS 98), under the MIT notice below; the marks themselves are Rotten Tomatoes' and IMDb's,
  used as Seerr uses them, beside their scores. The tvOS app carries the same marks as SVGs
  (`tvos/Hither/Assets.xcassets/Ratings/`), converted back from those drawables path for path (M7 part 3).
- **Hither's logo** (`docs/brand/hither_*.svg`, H1, DECISIONS 290) is its own: a soft square tile with a dark
  comet cut in it, and the word "hither". No shape or letter of Seerr's logo is used (the app's logo before H1, adapted
  from Seerr's `os_icon.svg` and `logo_full.svg`, is gone). What it keeps of Seerr's is the style: the tile's
  gradient is the two colours of Seerr's icon (`#C395FC` to `#4F65F5`, along the same line), with a white gloss and a
  dark cut with a soft shadow as Seerr's icon draws them; credited in the app's About and under the MIT notice below.
  The word is set in **Inter** (above), its letters drawn as outlines, so the tvOS app, which does not bundle Inter,
  carries Inter's shapes only in its logo.
- **Compose for TV** (`androidx.tv:tv-material`, Apache 2.0, Google; A1, DECISIONS 291) and the **Material icons** it
  brings (`androidx.compose.material:material-icons-core`, Apache 2.0, Google): the Android app's theme, buttons and,
  from A2, its tabs, cards and lists; the states' symbols (`StateView`). Like the other AndroidX libraries, Apache 2.0
  with no notice owed in the app.
- **Ktor's Darwin engine** (`io.ktor:ktor-client-darwin`, Apache 2.0, JetBrains; M7): the HTTP engine of
  `core`'s tvOS targets, linked into `HitherCore.xcframework`, as Ktor's core already is. Like the other Kotlin
  libraries (Ktor, kotlinx), it ships under Apache 2.0 with no notice owed in the app.
- **SKIE** (`co.touchlab.skie`, Apache 2.0, Touchlab; M7, DECISIONS 160): a Kotlin compiler plugin that
  shapes `HitherCore.xcframework`'s Swift API, and compiles its own small Swift runtime (the async and flow
  bridges) into the framework, so the tvOS app links that runtime too. A build tool otherwise: its analytics are
  switched off.
- **XcodeGen** (github.com/yonaskolb/XcodeGen, MIT, Yonas Kolb; M7, DECISIONS 165): writes `tvos/Hither.xcodeproj` from
  `tvos/project.yml`. A build tool, installed from Homebrew, not vendored; nothing of it is in the app.
- **TMDB logo** (`app/src/main/res/drawable/tmdb_logo.xml`): TMDB's "blue_short" logo from
  themoviedb.org/about/logos-attribution, used as TMDB's attribution rules require and allow
  (`docs/attribution.md`): on the About page, unmodified, less prominent than Hither's own mark,
  and since M6 beside TMDB's score on the media page, as Seerr's web shows it. The tvOS app's title page carries it
  as an SVG (`tvos/Hither/Assets.xcassets/Ratings/tmdb_logo.imageset`), the same logo unmodified, and since part 6a
  its About page beside TMDB's notice, as Android's does. It is TMDB's, not
  covered by this repository's licence.

## MIT notice for Seerr's rating marks, colours and icon gradient

MIT License. Copyright (c) 2020 sct.
Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions: The above copyright notice and this
permission notice shall be included in all copies or substantial portions of the Software. THE
SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## MIT notice for lifted SeerrTV code

MIT License. Copyright (c) 2024 DevMesh (original author), Copyright (c) 2024 Seerr Community.
Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions: The above copyright notice and this
permission notice shall be included in all copies or substantial portions of the Software. THE
SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
