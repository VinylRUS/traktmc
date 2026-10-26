# NOTICE — what this mirror is and is not

**Trakt** is an LGPL-3.0-only fork of *Village Quest* by TopasMusic. This
public repository mirrors the functional source code only.

## Not included in this mirror (All Rights Reserved / pending replacement)

- `assets/trakt/textures/**` — all block, item, GUI, entity-skin and
  painting textures (upstream Village Quest creative work);
- `assets/trakt/lang/*.json` — localization prose (upstream / derived);
- `assets/trakt/models/**`, `assets/trakt/blockstates/**`,
  `assets/trakt/items/**` — resource wiring for the excluded textures;
- `data/trakt/painting_variant/**`, `data/trakt/structure/**` — painting
  registrations and authored structure templates;
- `docs/wiki/**` — upstream documentation prose;
- `src/test/resources/assets/**` — upstream skin-head test fixtures;
- `releases/**` — release jars of the full package.

The only shipped artwork is `assets/trakt/icon.png`, generated for Trakt
by `tools/generators/gen_trakt_icon.py` (provenance recorded in
`THIRD_PARTY_ASSETS.md`).

## Consequences

- `./gradlew build` compiles the mod and runs the offline test suite;
  resource-dependent tests detect the code-only checkout and skip.
- The build does **not** produce a playable, distributable jar.
- Localization, textures and structures will be re-authored as original
  work before any public release of the full package.

## Attribution

Trakt is based on Village Quest by TopasMusic (LGPL-3.0-only code).
Not affiliated with or endorsed by the original project. Historical MIT
material is documented in `THIRD_PARTY_NOTICES.md` and `LICENSE-MIT`.
