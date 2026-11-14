# packmigrate

[![build](https://github.com/SalyyS1/packmigrate/actions/workflows/build.yml/badge.svg)](https://github.com/SalyyS1/packmigrate/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/SalyyS1/packmigrate?style=flat-square)](https://github.com/SalyyS1/packmigrate/releases)
[![license](https://img.shields.io/github/license/SalyyS1/packmigrate?style=flat-square)](LICENSE)

packmigrate converts a Minecraft resource pack's legacy `overrides` (from unpacked directories or `.zip` archives) into the item model definitions introduced in 1.21.4. Every `assets/<ns>/models/item/<item>.json` carrying an `overrides` list becomes an `assets/<ns>/items/<item>.json` decision tree that selects the exact same model for every item state, and `pack.mcmeta` is rewritten to claim the version range you request (including hybrid `1.20.1..26.2` dual-era packs). Anything broken in the source pack is named by item, override index, and reason rather than silently dropped.

```text
$ packmigrate legacy-pack.zip --zip migrated-1214.zip
== legacy-pack.zip: 3 item(s) with overrides
   minecraft:item/bow                       custom_model_data 1
   minecraft:item/diamond_sword             custom_model_data 1, 2, 10
   minecraft:item/paper                     custom_model_data 1, 2, 3

   3 definition(s) under assets/<ns>/items/, plus pack.mcmeta

   error   minecraft:item/diamond_sword
           References myserver:item/void_sword but assets/myserver/models/item/void_sword.json is not in the pack. The client shows the missing-model box for that entry.
   note    minecraft:item/bow
           custom_model_data 1 is used by 3 items: minecraft:item/bow, minecraft:item/diamond_sword, minecraft:item/paper. Legal, but a config that hands out 1 has to say which item.

1 error(s), 0 warning(s), 1 note(s)

Zipped migrated pack to migrated-1214.zip (4192 bytes)
resource-pack-sha1=3b8e9a...
```

## Why this exists

Minecraft 1.21.4 stopped reading `overrides` on item models. A legacy pack loads without complaint and renders every custom item as its plain vanilla base item.

Converting `overrides` to 1.21.4+ `items/*.json` is not a flat rename when overrides combine multiple predicates. A legacy `overrides` list is scanned in order and the **last** entry whose predicates all satisfy `value >= threshold` wins; a modern `range_dispatch` picks the **one** entry with the largest `threshold <= value`. Consider this common multi-predicate override list:

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

Requires Java 21 or newer. Download `packmigrate.jar` from [**Releases**](https://github.com/SalyyS1/packmigrate/releases). Input can be a pack directory or `.zip` archive:

```sh
packmigrate legacy-pack.zip                                        # dry-run plan and audit
packmigrate legacy-pack.zip --zip migrated-pack.zip                # write deterministic .zip + SHA-1
packmigrate legacy-pack.zip --versions 1.20.1..26.2 --zip out.zip  # hybrid pack serving 1.20.1 through 26.2
packmigrate pack/ --write --out pack-1214/                         # write converted directory copy
packmigrate legacy-pack.zip --json > migration-report.json         # machine-readable CI report
```

| Option | Description |
|---|---|
| `--versions RANGE` | Version range for `pack.mcmeta` (default `1.21.4..newest`; pass `1.20.1..26.2` without `--strip-overrides` for hybrid packs serving both pre-1.21.4 and 1.21.4+ clients) |
| `--write` | Apply migration to `--out` or in place (for directories) |
| `--out DIR` | Write the migrated pack directory to `DIR` without modifying the input |
| `--zip FILE` | Write a byte-deterministic migrated `.zip` archive and print `resource-pack-sha1=<sha1>` |
| `--strip-overrides` | Strip legacy `overrides` arrays from `models/item/*.json` (1.21.4+ ranges only) |
| `--json` | Emit machine-readable JSON migration report to stdout |
| `--force` | Overwrite an existing `--zip` file |
| `--strict` | Exit `1` on warnings as well as errors |

### GitHub Action

```yaml
- uses: SalyyS1/packmigrate@v0.2.0
  with:
    args: legacy-pack.zip --versions 1.20.1..26.2 --zip build/server-pack.zip --strict
```

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

- [**packmerge**](https://github.com/SalyyS1/packmerge): Deterministic multi-plugin Minecraft resource pack compiler for directories and `.zip` archives.
- [**glyphsmith**](https://github.com/SalyyS1/glyphsmith): Deterministic bitmap font glyph solver, lockfile compiler, and resource-pack font auditor.
- [**shadersmith**](https://github.com/SalyyS1/shadersmith): Vanilla core-shader and post-effect pipeline validator and builder for Minecraft resource packs.
- [**foliascan**](https://github.com/SalyyS1/foliascan): Static bytecode analyzer with SARIF call-graph taint flows for Paper and Folia server plugins.
- [**pluginscope**](https://github.com/SalyyS1/pluginscope): Plugin JAR dependency, shadow-relocation, and Paper/Spigot compatibility auditor.
- [**serverdoctor**](https://github.com/SalyyS1/serverdoctor): Live config, JVM flag, and crash/timings diagnostic CLI for Minecraft servers.
- [**mcstage**](https://github.com/SalyyS1/mcstage): Reproducible staging environment builder for Paper, Folia, and Velocity server networks.
- [**minecraftkit**](https://github.com/SalyyS1/minecraftkit): MCP server and engineering knowledge kit for Minecraft plugin and pack development.

## Credits and Licence

Checked against [misode/mcmeta](https://github.com/misode/mcmeta) and [minecraft.wiki](https://minecraft.wiki/w/Items_model_definition); see `NOTICE`. Independent project; not affiliated with Mojang or Microsoft. Licensed under [Apache-2.0](LICENSE).
