# Hither's brand: the masters

Hither's own mark (H1, DECISIONS 290): a soft square tile in Seerr's icon gradient (`#C395FC` to `#4F65F5`) with a
white gloss on its top-left corner, a dark comet cut in it with a soft shadow (the cut moved 2.5 down and right at
20%), and the word "hither", lowercase, set in Inter (`THIRD-PARTY.md`). Every file here is written by a script kept
outside the repository, beside the drafts Tom chose from (`make.py --masters docs/brand`); the word's typeface is one
setting there (`WORD_FONT`).

| File | What | Used for |
|---|---|---|
| `hither_tile.svg` | the tile and its gloss, 96 x 96 | the tvOS icon's middle layer; part of every mark below |
| `hither_comet.svg` | the comet cut and its shadow, 96 x 96, in the tile's coordinates | the tvOS icon's front layer, with the word |
| `hither_wordmark.svg` | "hither", its letters in the full logo's coordinates | the word in every logo below |
| `hither_icon.svg` | the tile with the cut, 96 x 96 | the mark alone |
| `hither_full.svg` | mark and word side by side, 322 x 96 | About (`hither_logo_full`; tvOS `LogoFull`) |
| `hither_stacked.svg` | mark over word, 198 x 180 | sign-in and starting (`hither_logo_stacked`; tvOS `LogoStacked`) |
| `hither_banner.svg` | the full logo on `#131928`, 320 x 180 | the TV launcher's banner |
| `hither_adaptive.svg` | the mark on `#131928`, 108 x 108 | the launcher icon (adaptive) |

`scripts/brand-drawables` writes Android's five drawables from these, path for path (no masks are used, so nothing is
re-drawn), and copies `hither_full.svg` and `hither_stacked.svg` byte for byte into the tvOS app's asset catalog,
which draws them as they are. It checks that the composed masters are made of the three parts. `scripts/tvos-icons`
draws the tvOS app icon's layers and its Top Shelf images from the parts with macOS's own SVG renderer (DECISIONS 214,
290). After changing a master, run both; `./verify --fast` fails while either side disagrees, and `BrandTest` samples
the drawn result on a device.
