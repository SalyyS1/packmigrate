"""Print the item model definitions vanilla ships for the items that used legacy predicates.

Usage: python dump-vanilla-definitions.py <assets-root> item [item ...]
"""
import json
import os
import sys

root = sys.argv[1]
for item in sys.argv[2:]:
    path = os.path.join(root, "assets", "minecraft", "items", item + ".json")
    print("==", item)
    with open(path, encoding="utf-8") as handle:
        print(json.dumps(json.load(handle)["model"], indent=1))
