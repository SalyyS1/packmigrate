# packmigrate

[![build](https://github.com/SalyyS1/packmigrate/actions/workflows/build.yml/badge.svg)](https://github.com/SalyyS1/packmigrate/actions/workflows/build.yml)

packmigrate converts a resource pack's legacy `overrides` into the item model definitions that replaced
them in 1.21.4. Every `assets/<ns>/models/item/<item>.json` carrying an `overrides` list becomes an
`assets/<ns>/items/<item>.json` that picks the same model for the same item state, and `pack.mcmeta` is
rewritten to claim the range of versions you ask for. Whatever cannot come across exactly is named — the
item, the override and the reason — instead of being quietly dropped.

```
$ packmigrate pack
== demo: 3 item(s) with overrides
   minecraft:item/bow                       custom_model_data 1
   minecraft:item/diamond_sword             custom_model_data 1, 2, 10
   minecraft:item/paper                     custom_model_data 1, 2, 3

   3 definition(s) under assets/<ns>/items/, plus pack.mcmeta

   error   minecraft:item/diamond_sword
           References myserver:item/void_sword but assets/myserver/models/item/void_sword.json is not in the pack. The client shows the missing-model box for that entry.
   note    minecraft:item/bow
           custom_model_data 1 is used by 3 items: minecraft:item/bow, minecraft:item/diamond_sword, minecraft:item/paper. Legal, but a config that hands out 1 has to say which item.
   note    minecraft:item/diamond_sword
           custom_model_data 2 is used by 2 items: minecraft:item/diamond_sword, minecraft:item/paper. Legal, but a config that hands out 2 has to say which item.

1 error(s), 0 warning(s), 2 note(s)

Nothing written. Pass --write to apply, or --write --out <dir> to keep the original.
```

That run found a reference to a model the pack does not contain. It was already broken before the
migration — the legacy override pointed at the same missing file — but a pack with a hundred custom items
does not tell you which one, and the migrated pack would have carried the fault across untouched.

## Why this exists

1.21.4 stopped reading `overrides`. A pack that still has them loads without complaint and shows every
custom item as the plain base item, which is why the usual symptom is a support ticket saying the icons
"disappeared" after a server update.

The replacement is not a rename. The two systems select differently, and they agree on exactly one shape.
A legacy `overrides` list is scanned in order and the **last** entry whose every predicate satisfies
`value >= threshold` wins. A `range_dispatch` picks the **one** entry with the largest `threshold` that is
`<= value`. For a plain ascending list of `custom_model_data` values those two rules answer the same model
for every input, so that case is a mechanical rewrite — which is what makes the rest deceptive, because a
pack where 90 % of the file converts by hand tempts you to do the last 10 % the same way.

Here is the last 10 %, from the test fixture in this repository:

```json
{"predicate": {"custom_model_data": 1}, "model": "myserver:item/scroll"},
{"predicate": {"custom_model_data": 2}, "model": "myserver:item/map_fragment"},
{"predicate": {"custom_model_data": 3, "lefthanded": 1}, "model": "myserver:item/left_scroll"}
```

With `custom_model_data: 3` on a right-handed player, the legacy client shows **the map fragment**: entry
three needs the left hand, so the scan falls back to entry two, whose `>= 2` still holds at 3. The obvious
hand-written definition puts `left_scroll` under threshold 3 and lets it fall through to the item's own
model, and the item turns into plain paper for most of the players who see it. packmigrate emits what the
legacy list did:

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

It gets there by rebuilding the legacy rule rather than recognising file shapes: pick a property, split the
overrides over the values that property can take, drop the predicates the branch has decided, and recurse.
An override with nothing left to test matches unconditionally, so it becomes that branch's fallback and
hides everything before it — which is the sentence above, expressed as a tree.

Five legacy predicates are also no longer numbers. `charged` and `firework` were two booleans describing
one enum, and are now cases of `minecraft:charge_type`; `lefthanded` is `minecraft:main_hand`; `trim_type`
was a material's index and is now the material's name; `level` and `honey_level` read a block state. And
three predicates silently depended on which item they were written for, which the new format makes you say
out loud. Those are the parts a converter has to know rather than translate.

## Getting started

You need Java 21 or newer. Download `packmigrate.jar` from the releases page. The pack has to be a
directory — unzip it first.

```sh
packmigrate pack                            # plan: what would change, and what cannot
packmigrate pack --write                    # apply it in place
packmigrate pack --write --out pack-1214    # write a converted copy, keep the original
```

| Option | What it does |
|---|---|
| `--versions 1.21.4..26.2` | The range `pack.mcmeta` must satisfy. Defaults to 1.21.4 through the newest release the tool knows. Snapshots move too fast to name, so pass their format number: `--versions 88..97` |
| `--strip-overrides` | Also delete the `overrides` list from each legacy model file, leaving the rest of the file untouched |
| `--strict` | Exit non-zero on warnings as well as errors, for a CI step |

Exit codes are `0` for a clean run, `1` when something was reported that will render wrong, and `2` when
the command itself could not proceed.

## What lands on disk

```
pack/
  pack.mcmeta                                   # rewritten for the version range
  assets/minecraft/items/bow.json               # new: one definition per legacy item model
  assets/minecraft/items/paper.json
  assets/minecraft/models/item/bow.json         # unchanged, and still referenced
  assets/myserver/models/item/longbow.json      # unchanged
```

The model files stay. The new format references models, it does not replace them, so a definition pointing
at `myserver:item/longbow` needs that file exactly as it is. Leaving them alone also means the pack keeps
working on 1.21.3 and earlier: the two formats do not read each other's files, so one pack can serve both
until you stop caring about the old clients. `--strip-overrides` is there for when that day comes, and it
removes only the `overrides` key — `parent`, `textures`, `display` and anything else you wrote are printed
back as they were.

Three checks run across the whole pack, because they are the ones that reach production:

- a definition referencing a model file that is not in the pack and is not shipped by the game (the
  bundled list of vanilla item models is the 1309 paths of the current release);
- the same `custom_model_data` value claimed by two different items, which is legal and renders correctly
  but is where a plugin config that hands out bare numbers goes wrong;
- a `custom_model_data` value declared twice on one item with no other predicate, where only the second
  declaration ever did anything.

## The mapping

All twenty-one legacy predicates convert. There is no "unsupported, migrate by hand" row.

| Legacy predicate | Becomes | Notes |
|---|---|---|
| `custom_model_data` | `range_dispatch` on `minecraft:custom_model_data` | `threshold` is the legacy integer. The property reads `floats[0]`, which is where the game's own data fixer puts it |
| `damage` | `range_dispatch` on `minecraft:damage` | Both are `damage / max_damage` |
| `damaged` | `condition` on `minecraft:damaged` | |
| `broken` | `condition` on `minecraft:broken` | Elytra |
| `pulling`, `blocking`, `throwing`, `tooting` | `condition` on `minecraft:using_item` | Four names for "the player is using it" |
| `pull` | `range_dispatch` on `minecraft:use_duration`, `scale: 0.05` | The new property counts ticks; on a crossbow it is `minecraft:crossbow/pull` instead, which is already 0..1 |
| `cast` | `condition` on `minecraft:fishing_rod/cast` | |
| `cooldown` | `range_dispatch` on `minecraft:cooldown` | Both are 0..1 |
| `filled` | `range_dispatch` on `minecraft:bundle/fullness` | Both report the `bundle_contents` weight, where a full bundle is 1 |
| `brushing` | `range_dispatch` on `minecraft:use_cycle`, `period: 10`, `scale: 0.1` | The numbers vanilla's own `brush.json` uses, so the thresholds carry over unchanged |
| `time` | `range_dispatch` on `minecraft:time`, `source: daytime` | Wrapped in a dimension select; see below |
| `angle` | `range_dispatch` on `minecraft:compass` with a `target` | Wrapped in a lodestone condition; see below |
| `charged`, `firework` | `select` on `minecraft:charge_type` | Cases `arrow` and `rocket`, merged from both predicates |
| `lefthanded` | `select` on `minecraft:main_hand` | Case `left` |
| `trim_type` | `select` on `minecraft:trim_material` | Each threshold claims the materials whose `item_model_index` it covers, so `0.2` becomes iron through emerald and `0.8` becomes diamond upward |
| `level` | `select` on `minecraft:block_state`, `block_state_property: level` | Light blocks, `0`..`15` |
| `honey_level` | `select` on `minecraft:block_state`, `block_state_property: honey_level` | Beehives, `0`..`5` |

A predicate name that was never part of the legacy format is refused by name and reported as an error, so a
typo in a hand-edited pack surfaces here rather than as an item that looks fine and never changes model.

## Three predicates that knew which item they were on

`pull`, `angle` and `time` each read something the item supplied, and the legacy predicate hid the
difference. The new format does not, so the conversion has to make a decision and then say it made one.
All three follow the shape Mojang's own migrated definition for that item uses.

**A crossbow's `pull`** ran over its charge time, not a 20-tick draw, so on `crossbow.json` it becomes
`minecraft:crossbow/pull` with no scaling — the same property vanilla's `crossbow.json` reads. Everywhere
else `pull` is `minecraft:use_duration` scaled by `0.05`.

**`angle`** pointed at a lodestone when the compass had one and at world spawn otherwise, and
`minecraft:compass` has no default target. The definition keeps both, exactly as vanilla `compass.json`
does — a `has_component` condition on `minecraft:lodestone_tracker`, with the same entries under
`target: "lodestone"` on one side and `target: "spawn"` on the other. On `recovery_compass.json` it is one
dispatch with `target: "recovery"`.

**`time`** read the day cycle in the overworld and spun at random anywhere else. That becomes a
`minecraft:context_dimension` select with `source: "daytime"` in the overworld and `source: "random"`
outside it, which is what vanilla `clock.json` contains.

Each of those three writes a note into the report explaining what was added, because they are the only
places where the output contains a decision the pack author never wrote down.

## pack.mcmeta

`--versions` is not decoration. Resource pack format 64 — 1.21.8 — is the last one without minor versions,
and the rules on either side of that line are different and unforgiving:

- from 65 up, `min_format` and `max_format` are mandatory, must both be present, and `supported_formats` is
  *rejected*, with the client telling you to delete it;
- reaching back below 65 means also carrying `supported_formats` and `pack_format`, with
  `supported_formats` starting on exactly `min_format`'s major and ending on either `max_format`'s major or
  64, and `pack_format` inside the range and at least 15;
- a pack that states only `pack_format` cannot claim support for anything above 64 at all.

For the default range that produces all four fields, consistent with each other. Getting one wrong does not
degrade politely: the pack is listed as incompatible or refuses to load. The description is carried over
from the existing file, escapes intact.

## What it does not do

- **No plugin config rewriting.** Oraxen, Nexo and ItemsAdder generate the pack this tool reads, and each
  has its own migration for its own configs. packmigrate touches the pack and nothing else, so it is safe to
  run on a pack they produced, but it will not update their YAML — and if you re-run their generator
  afterwards it will overwrite what this wrote.
- **No zip handling.** Unzip, convert, zip. One less thing to get wrong about timestamps and entry order.
- **No new-format features.** It converts what the legacy file said, so it emits `model`, `range_dispatch`,
  `condition` and `select` and nothing else. `composite`, `bundle/selected_item`, `special` models, tinting
  and `oversized_in_gui` are new capabilities, not migrations, and inventing them from an old file would be
  guessing.
- **No string or flag custom model data.** The new component also holds strings, flags and colours, which
  are strictly better than integers for new work. A legacy pack has integers, and integers are what comes
  out; switching to named keys is a redesign of your plugin config, not a migration of your pack.
- **No model or texture changes.** Item models did not change in 1.21.4. If your models were wrong before,
  they are wrong after.
- **No pack optimisation.** [PackSquash](https://github.com/ComunidadAylas/PackSquash) does that properly;
  run it afterwards.

## Building it

```sh
./gradlew build          # tests, then the shaded jar in build/libs
```

Kotlin 2.4.10 on a Java 21 toolchain, Gradle 9.7.1, one shaded jar with Clikt and kotlinx.serialization
inside. The JSON the game reads is written by this project's own emitter rather than a serialiser, so key
order, indentation and escaping are fixed instead of incidental.

The tests are shape assertions against the vanilla definitions: a bow has to come out as one `using_item`
condition with the `use_duration` range inside it, a crossbow as a `charge_type` select with the pulling
tree in its fallback, a clock as a dimension select, a compass as a lodestone condition. Those files are
what the client's own migration produced for those items, which makes them the closest thing to a
specification this problem has.

## Credits

The `NOTICE` file lists the dependencies and every source each fact was checked against. Two are worth
naming here.

[misode/mcmeta](https://github.com/misode/mcmeta) publishes the game's assets and data as plain
directories, one branch per kind. Every shape this tool emits was compared against the real 26.3 item model
definition for the item in question — `bow.json`, `crossbow.json`, `compass.json`, `clock.json`,
`brush.json`, `light.json`, `beehive.json`, the trimmed armour files — and against the 1.21.3 files they
replaced, which is where the legacy thresholds came from. No assets from it are redistributed here; the
bundled list of vanilla item model paths is file names only.

[minecraft.wiki](https://minecraft.wiki/w/Items_model_definition) documents the new format's properties and
fields, and was the reference for the ones vanilla does not use in any file, `minecraft:bundle/fullness`
among them. Where the wiki and the game disagreed, the game won.

packmigrate is an independent project and is not affiliated with, endorsed by or sponsored by Mojang or
Microsoft. Minecraft is a trademark of Mojang Synergies AB.

## Licence

Apache-2.0. See `LICENSE`.



