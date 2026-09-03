"""Print the trim_material select cases vanilla uses, to cross-check the legacy trim_type table."""
import json
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    model = json.load(handle)["model"]
print(model["type"], model["property"])
for case in model["cases"]:
    print(case["when"], case["model"]["model"])
print("fallback", model["fallback"]["model"])
