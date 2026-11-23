# TMDB attribution

Everything Hither shows about a film or a series (titles, overviews, posters, backdrops, cast)
is TMDB's. It reaches the app through the Seerr server, which fetches it from the TMDB API;
Hither never calls TMDB itself. TMDB's API terms still ask every application that shows its
content to say so, and a public release must carry this. Read on 2026-11-10 from
<https://www.themoviedb.org/api-terms-of-use> (section 3, Attribution) and
<https://developer.themoviedb.org/docs/faq>.

## The About screen carries

1. **The notice, word for word** (the terms give the sentence with a choice of noun; Hither is
   an application):

   > This application uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise
   > approved by TMDB.

   The developer FAQ still gives an older, shorter sentence ("This product uses the TMDB API
   but is not endorsed or certified by TMDB."); the terms of use are the binding text, so
   theirs is the one used.

2. **The TMDB logo**, one of the approved files from
   <https://www.themoviedb.org/about/logos-attribution>, beside the notice.

## The rules the screen has to keep

- The attribution sits in an "About" or "Credits" type section (FAQ). Hither's is Settings ›
  About.
- The TMDB logo is "less prominent than the logos or marks that primarily describe or
  identify" the application (terms): smaller than Hither's own logo on that screen, and
  not on the launcher banner, the sign-in screen or a store listing's art.
- The logo is not modified "in color, aspect ratio, flipped or rotated"; it may be white,
  black or one of TMDB's brand colours (FAQ).
- Nothing implies endorsement: no "powered by" beside Hither's name, no TMDB mark in the icon.
- In text, "TMDB" or "The Movie Database", nothing else (FAQ).

## In the app (M1)

- Settings › About shows the notice above and the logo `blue_short`
  (`app/src/main/res/drawable/tmdb_logo.xml`: the SVG's one path and gradient, unchanged, as
  a vector drawable), 22 px tall beside Hither's 96 px logo (48 dp; DECISIONS 51, 72).

## On the TV's home screen (M4)

- Hither's "Ready to watch" channel and its Watch Next entries carry TMDB's titles and artwork
  (a poster and a backdrop) for the user's ready titles. The artwork comes through the server's
  image proxy, as everywhere else in Hither; the launcher fetches it from there.
- No TMDB logo and no TMDB notice on the home screen: those cards are titles, not an About or
  Credits section, and a TMDB mark there would sit on the launcher, where the rules above keep it
  off. The attribution stays in Settings › About.

## Not done yet

- TMDB's API is free for non-commercial use with attribution; a commercial use needs a
  written agreement with TMDB (terms, section 2). Hither holds no TMDB key, the server does,
  so whether a paid listing of a client would count is a question for TMDB before any price
  is put on the app.
- The same About screen credits Seerr, SeerrTV and Inter (`THIRD-PARTY.md`).

## Beside TMDB's score on the media page (M6)

- The media page's ratings line shows TMDB's user score (the vote average as a percentage) beside the
  same `blue_short` logo, unmodified, 38 dp wide, as Seerr's web marks the score with TMDB's logo
  (DECISIONS 98). It names the score's source; it is not the attribution, which stays in About, and it
  is smaller than the page's own title and poster, never on the launcher, the banner or a store's art.
