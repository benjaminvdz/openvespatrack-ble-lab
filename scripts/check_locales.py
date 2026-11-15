#!/usr/bin/env python3
"""Require complete EN/NL/FR Android UI resources and matching format arguments."""
import collections
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
FORMAT = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])")

def read(folder):
    result = {}
    for path in sorted(folder.glob("*.xml")):
        for item in ET.parse(path).getroot():
            if item.tag != "string" or item.attrib.get("translatable") == "false":
                continue
            key = item.attrib["name"]
            if key in result:
                raise ValueError(f"Duplicate string {key}: {folder}")
            result[key] = "".join(item.itertext())
    return result

def arguments(value):
    return collections.Counter((index, kind) for index, kind in FORMAT.findall(value) if kind != "%")

def main():
    baseline = read(RES / "values")
    errors = []
    for locale in ("nl", "fr"):
        translated = read(RES / f"values-{locale}")
        for key in sorted(baseline.keys() - translated.keys()):
            errors.append(f"{locale}: missing {key}")
        for key in sorted(translated.keys() - baseline.keys()):
            errors.append(f"{locale}: unknown {key}")
        for key in baseline.keys() & translated.keys():
            if arguments(baseline[key]) != arguments(translated[key]):
                errors.append(f"{locale}: format arguments differ for {key}")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"EN/NL/FR: {len(baseline)} strings per language; format arguments match.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
