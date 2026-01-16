#!/usr/bin/env python3
"""
Inject properties from batch*.json files into review_required.org entries.

Maps:
  tmdb_id -> TMDB_ID (if not null)
  suggested_search -> SUGGESTED_SEARCH
  corrected -> AI_TITLE
  year -> YEAR (if not null)
  notes -> AI_NOTES
"""

import json
import re
from pathlib import Path


def load_knowledge_batches(batches_dir: Path) -> dict[str, dict]:
    """Load all batch*.json files and return a dict keyed by title."""
    knowledge = {}
    batch_files = sorted(batches_dir.glob("batch*.json"))

    for batch_file in batch_files:
        print(f"Loading {batch_file.name}...")
        with open(batch_file, "r", encoding="utf-8") as f:
            entries = json.load(f)
            for entry in entries:
                title = entry.get("title")
                if title:
                    knowledge[title] = entry

    print(f"Loaded {len(knowledge)} entries from {len(batch_files)} batch files")
    return knowledge


def parse_org_file(org_path: Path) -> list[dict]:
    """Parse org file into a list of entries with their line ranges."""
    with open(org_path, "r", encoding="utf-8") as f:
        lines = f.readlines()

    entries = []
    current_entry = None

    for i, line in enumerate(lines):
        # Check for headline
        if line.startswith("* "):
            if current_entry is not None:
                current_entry["end_line"] = i
                entries.append(current_entry)

            title = line[2:].rstrip("\n")
            current_entry = {
                "title": title,
                "start_line": i,
                "properties_start": None,
                "properties_end": None,
                "properties": {},
            }
        elif current_entry is not None:
            if line.strip() == ":PROPERTIES:":
                current_entry["properties_start"] = i
            elif line.strip() == ":END:":
                current_entry["properties_end"] = i
            elif current_entry["properties_start"] is not None and current_entry["properties_end"] is None:
                # Parse property line
                match = re.match(r":([^:]+):\s*(.*)", line.strip())
                if match:
                    prop_name = match.group(1)
                    prop_value = match.group(2)
                    current_entry["properties"][prop_name] = prop_value

    # Don't forget the last entry
    if current_entry is not None:
        current_entry["end_line"] = len(lines)
        entries.append(current_entry)

    return entries, lines


def update_org_file(org_path: Path, knowledge: dict[str, dict]) -> int:
    """Update org file with properties from knowledge entries."""
    entries, lines = parse_org_file(org_path)

    updates = 0
    # Process in reverse order so line numbers stay valid
    for entry in reversed(entries):
        title = entry["title"]
        if title not in knowledge:
            continue

        k = knowledge[title]

        # Build new properties to add/update
        new_props = {}

        if k.get("tmdb_id") is not None:
            new_props["TMDB_ID"] = str(k["tmdb_id"])

        if k.get("suggested_search"):
            new_props["SUGGESTED_SEARCH"] = k["suggested_search"]

        if k.get("corrected"):
            new_props["AI_TITLE"] = k["corrected"]

        if k.get("year") is not None:
            new_props["YEAR"] = str(k["year"])

        if k.get("notes"):
            new_props["AI_NOTES"] = k["notes"]

        if not new_props:
            continue

        # Merge with existing properties
        merged_props = entry["properties"].copy()
        merged_props.update(new_props)

        # Build new properties block
        prop_lines = [":PROPERTIES:\n"]
        for prop_name, prop_value in merged_props.items():
            prop_lines.append(f":{prop_name}: {prop_value}\n")
        prop_lines.append(":END:\n")

        # Replace the old properties block
        start = entry["properties_start"]
        end = entry["properties_end"] + 1  # Include the :END: line
        lines[start:end] = prop_lines

        updates += 1

    # Write back
    with open(org_path, "w", encoding="utf-8") as f:
        f.writelines(lines)

    return updates


def main():
    script_dir = Path(__file__).parent
    resources_dir = script_dir / "resources"
    batches_dir = resources_dir / "knowledge_batches_set2"
    org_path = resources_dir / "review_required.org"

    if not org_path.exists():
        print(f"Error: {org_path} not found")
        return 1

    if not batches_dir.exists():
        print(f"Error: {batches_dir} not found")
        return 1

    knowledge = load_knowledge_batches(batches_dir)

    if not knowledge:
        print("No knowledge entries found")
        return 1

    updates = update_org_file(org_path, knowledge)
    print(f"Updated {updates} entries in {org_path.name}")

    return 0


if __name__ == "__main__":
    exit(main())
