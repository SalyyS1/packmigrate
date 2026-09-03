"""Read item_model_index of every 1.21.3 trim material from misode/mcmeta, to seed the trim_type mapping.

Legacy `trim_type` predicates compared against these floats; the modern select on
`minecraft:trim_material` matches the material id instead. Run once and paste the table into
PredicateMapping.kt, which is what shipped.
"""
import json
import urllib.request

MATERIALS = ["quartz", "iron", "netherite", "redstone", "copper", "gold", "emerald", "diamond", "lapis", "amethyst"]
BASE = "https://raw.githubusercontent.com/misode/mcmeta/1.21.3-data/data/minecraft/trim_material/"

for material in MATERIALS:
    with urllib.request.urlopen(BASE + material + ".json") as response:
        data = json.load(response)
    print(f"{material:10} {data['item_model_index']}")
