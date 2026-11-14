# packmigrate

[![build](https://github.com/SalyyS1/packmigrate/actions/workflows/build.yml/badge.svg)](https://github.com/SalyyS1/packmigrate/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/SalyyS1/packmigrate?style=flat-square)](https://github.com/SalyyS1/packmigrate/releases)
[![browser tool](https://img.shields.io/badge/browser_migrator-live_client--side-7c3aed?style=flat-square)](https://salyys1.github.io/packmigrate/)
[![license](https://img.shields.io/github/license/SalyyS1/packmigrate?style=flat-square)](LICENSE)

packmigrate converts a Minecraft resource pack's legacy `overrides` (from unpacked directories or `.zip` archives) into the item model definitions introduced in 1.21.4 and carried through Minecraft 26.3 (`pack_format` `97` / `[97, 1]`). Every `assets/<ns>/models/item/<item>.json` carrying an `overrides` list becomes an `assets/<ns>/items/<item>.json` decision tree that selects the exact same model for every item state, `pack.mcmeta` and `overlays.entries` are upgraded across format eras, and OASIS SARIF v2.1.0 diagnostics (`PM001` through `PM011`) pinpoint broken model references or predicate errors.

Try the zero-upload client-side browser migrator at [**salyys1.github.io/packmigrate**](https://salyys1.github.io/packmigrate/).

```text
$ packmigrate legacy-pack.zip --versions 1.20.1..26.3 --zip migrated-263.zip --sarif packmigrate.sarif
== legacy-pack.zip: 4 item(s) with overrides
   minecraft:item/bow                       custom_model_data 1
   minecraft:item/compass                   no custom_model_data, other predicates only
   minecraft:item/diamond_sword             custom_model_data 1, 2, 10
   minecraft:item/paper                     custom_model_data 1, 2, 3

   4 definition(s) under assets/<ns>/items/, plus pack.mcmeta

   error   [PM003] minecraft:item/diamond_sword
           References myserver:item/void_sword but assets/myserver/models/item/void_sword.json is not in the pack. The client shows the missing-model box for that entry.
   note    [PM006] minecraft:item/bow
           custom_model_data 1 is used by 3 items: minecraft:item/bow, minecraft:item/diamond_sword, minecraft:item/paper. Legal, but a config that hands out 1 has to say which item.

1 error(s), 0 warning(s), 3 note(s)

Zipped migrated pack to migrated-263.zip (4218 bytes)
resource-pack-sha1=3b8e9a...
```

## Why this exists

Minecraft 1.21.4 stopped reading `overrides` on item models, and Minecraft 1.21.9 through 26.3 replaced `supported_formats` and legacy overlay `formats` with `[major, minor]` `min_format` and `max_format` bounds. A legacy pack loads without complaint on 1.21.4+ and renders every custom item as its plain vanilla base item, or is rejected outright on 26.3 when `pack.mcmeta` or `overlays.entries` omit modern format fields.

Converting `overrides` to `items/*.json` is not a flat rename when overrides combine multiple predicates. A legacy `overrides` list is scanned in order and the **last** entry whose predicates all satisfy `value >= threshold` wins; a modern `range_dispatch` picks the **one** entry with the largest `threshold <= value`. Consider this multi-predicate override list:

```json
{"predicate": {"custom_model_data": 1}, "model": "myserver:item/scroll"},
{"predicate": {"custom_model_data": 2}, "model": "myserver:item/map_fragment"},
{"predicate": {"custom_model_data": 3, "lefthanded": 1}, "model": "myserver:item/left_scroll"}
```

With `custom_model_data: 3` on a right-handed player, the legacy client renders **map_fragment** (entry 3 requires left hand, so the scan falls back to entry 2 where `3 >= 2`). Naive converters emit `left_scroll` at threshold 3 with the base item as fallback, turning the item into plain paper for right-handed players. packmigrate reconstructs the exact decision tree:

```json
{
  "threshold": 3.0,
  "model": {
    "type": "minecraft:select",
    "property": "minecraft:main_hand",
    "cases": [{ "when": "left", "model": { "type": "minecraft:model", "model": "myserver:item/left_scroll" } }],
    "fallback": { "type": "minecraft:model", "model": "myserver:item/map_fragment" }
  }
}
```

## Getting started

Requires Java 21 or newer. Download `packmigrate.jar` from [**Releases**](https://github.com/SalyyS1/packmigrate/releases), or drop a `.zip` directly into the [**browser migrator**](https://salyys1.github.io/packmigrate/).

```sh
packmigrate legacy-pack.zip                                                   # dry-run plan and audit (1.21.4..26.3)
packmigrate legacy-pack.zip --zip migrated-pack.zip                           # write deterministic .zip + SHA-1
packmigrate legacy-pack.zip --versions 1.20.1..26.3 --zip out.zip             # hybrid pack serving 1.20.1 through 26.3
packmigrate legacy-pack.zip --versions 1.20.1..26.3 --overlay-items v1_21_4 \
  --zip out.zip                                                               # isolate items/*.json inside an overlay
packmigrate legacy-pack.zip --sarif packmigrate.sarif --json > report.json    # SARIF v2.1.0 + JSON CI output
```

| Option | Description |
|---|---|
| `--versions RANGE` | Version range for `pack.mcmeta` (default `1.21.4..26.3`; supports `1.20.1..26.3`, `26.1..[97,1]`, or `97.1`) |
| `--overlay-items DIR` | Isolate generated `assets/<ns>/items/*.json` inside `<DIR>/` and register `<DIR>` in `pack.mcmeta` `overlays.entries` |
| `--write` | Apply migration to `--out` or in place (for directories) |
| `--out DIR` | Write the migrated pack directory to `DIR` without modifying the input |
| `--zip FILE` | Write a byte-deterministic migrated `.zip` archive and print `resource-pack-sha1=<sha1>` |
| `--sarif FILE` | Write an OASIS SARIF v2.1.0 diagnostic report (`PM001`..`PM011`) for GitHub Code Scanning |
| `--strip-overrides` | Strip legacy `overrides` arrays from `models/item/*.json` (1.21.4+ ranges only) |
| `--json` | Emit machine-readable JSON migration report to stdout |
| `--force` | Overwrite an existing `--zip` file |
| `--strict` | Exit `1` on warnings as well as errors |

### GitHub Action & Code Scanning

```yaml
- uses: SalyyS1/packmigrate@v0.3.0
  with:
    args: legacy-pack.zip --versions 1.20.1..26.3 --zip build/server-pack.zip --strict
    sarif: build/packmigrate.sarif

- uses: github/codeql-action/upload-sarif@v3
  if: always()
  with:
    sarif_file: build/packmigrate.sarif
```

## SARIF v2.1.0 Diagnostic Rules

| Rule | Default Level | Condition |
|---|---|---|
| `PM001` | `error` | Malformed JSON or invalid `overrides` structure in legacy model or `pack.mcmeta` |
| `PM002` | `error` | Unknown legacy item predicate name |
| `PM003` | `error` | Referenced model file missing from active overlay, base pack, and vanilla Minecraft |
| `PM004` | `warning` | Duplicate unconditional `custom_model_data` entry shadows an earlier override |
| `PM005` | `warning` | Existing `assets/<ns>/items/<item>.json` with differing content would be overwritten |
| `PM006` | `note` | Multiple distinct items claim the same `custom_model_data` value |
| `PM007` | `note` | Item-specific vanilla predicate behavior synthesized (`compass`, `clock`, `trim_material`) |
| `PM008` | `error` | Invalid `overlays.entries` directory (`[a-z0-9_-]+`) or missing format bounds in `pack.mcmeta` |
| `PM009` | `warning` | `overlays.entries` references a directory missing from the pack |
| `PM010` | `note` | Legacy `overlays.entries` `formats` range upgraded with `min_format` and `max_format` for 1.21.9..26.3 |
| `PM011` | `warning` | Fractional or negative `custom_model_data` threshold that diverges from integer `floats[0]` conversion |

## Complete 21-predicate mapping

All twenty-one legacy item model predicates convert automatically:

| Legacy predicate | 1.21.4+ representation | Notes |
|---|---|---|
| `custom_model_data` | `range_dispatch` on `minecraft:custom_model_data` | Reads `floats[0]`, matching Mojang's item data fixer |
| `damage` | `range_dispatch` on `minecraft:damage` | Normalized `damage / max_damage` |
| `damaged` | `condition` on `minecraft:damaged` | Boolean condition |
| `broken` | `condition` on `minecraft:broken` | Elytra broken state |
| `pulling`, `blocking`, `throwing`, `tooting` | `condition` on `minecraft:using_item` | Unified active-use boolean |
| `pull` | `range_dispatch` on `minecraft:use_duration` (`scale: 0.05`) or `minecraft:crossbow/pull` | Item-aware dispatch matching vanilla `bow.json` and `crossbow.json` |
| `cast` | `condition` on `minecraft:fishing_rod/cast` | Fishing rod state |
| `cooldown` | `range_dispatch` on `minecraft:cooldown` | Normalized `0..1` |
| `filled` | `range_dispatch` on `minecraft:bundle/fullness` | Bundle weight ratio |
| `brushing` | `range_dispatch` on `minecraft:use_cycle` (`period: 10`, `scale: 0.1`) | Matches vanilla `brush.json` |
| `time` | `select` on `minecraft:context_dimension` -> `minecraft:time` | Uses `daytime` in `minecraft:overworld` and `random` elsewhere |
| `angle` | `condition` on `minecraft:has_component` (`lodestone_tracker`) -> `minecraft:compass` | Dispatches `lodestone` vs `spawn` (`recovery` on `recovery_compass`) |
| `charged`, `firework` | `select` on `minecraft:charge_type` | Cases `arrow` and `rocket` |
| `lefthanded` | `select` on `minecraft:main_hand` | Case `left` |
| `trim_type` | `select` on `minecraft:trim_material` | Maps legacy float thresholds to armor trim material names |
| `level` | `select` on `minecraft:block_state` (`level`) | Light blocks `0..15` |
| `honey_level` | `select` on `minecraft:block_state` (`honey_level`) | Beehives `0..5` |

## Building from source

```sh
./gradlew build --no-daemon    # runs JUnit 5 suite and builds build/libs/packmigrate.jar
```

## Related Minecraft Engineering Tools by Salyvn

- [**packmerge**](https://github.com/SalyyS1/packmerge): Deterministic multi-plugin Minecraft resource pack compiler with SARIF conflict diagnostics and [browser conflict matrix](https://salyys1.github.io/packmerge/).
- [**glyphsmith**](https://github.com/SalyyS1/glyphsmith): Deterministic bitmap font glyph solver, lockfile compiler, and [browser font auditor](https://salyys1.github.io/glyphsmith/).
- [**shadersmith**](https://github.com/SalyyS1/shadersmith): Vanilla core-shader and post-effect pipeline validator with [browser GLSL inspector](https://salyys1.github.io/shadersmith/).
- [**foliascan**](https://github.com/SalyyS1/foliascan): Static bytecode analyzer with SARIF call-graph taint flows and [browser JAR auditor](https://salyys1.github.io/foliascan/).
- [**pluginscope**](https://github.com/SalyyS1/pluginscope): Plugin JAR dependency, shadow-relocation, and Paper/Spigot compatibility auditor.
- [**serverdoctor**](https://github.com/SalyyS1/serverdoctor): Live config, JVM flag, and crash/timings diagnostic CLI for Minecraft servers.
- [**mcstage**](https://github.com/SalyyS1/mcstage): Reproducible staging environment builder for Paper, Folia, and Velocity server networks.
- [**minecraftkit**](https://github.com/SalyyS1/minecraftkit): MCP server and engineering knowledge kit for Minecraft plugin and pack development.

## Credits and Licence

Checked against [misode/mcmeta](https://github.com/misode/mcmeta) and [minecraft.wiki](https://minecraft.wiki/w/Items_model_definition); see `NOTICE`. Independent project; not affiliated with Mojang or Microsoft. Licensed under [Apache-2.0](LICENSE).
