#!/usr/bin/env python3
"""
Query TMDB using SUGGESTED_SEARCH and directly update org file with TMDB_ID.
Only processes entries with SUGGESTED_SEARCH that need review.
"""

import re
import sys
import time
import subprocess
from pathlib import Path
from typing import Optional, List, Dict
import requests
from rapidfuzz import fuzz

# TMDB API Configuration
TMDB_BASE_URL = "https://api.themoviedb.org/3"
TMDB_SEARCH_URL = f"{TMDB_BASE_URL}/search/movie"

# Rate limiting
RATE_LIMIT_DELAY = 0.25  # 4 requests per second


def parse_org_file(org_path: Path) -> tuple[List[Dict], List[str]]:
    """
    Parse org file and extract entries with SUGGESTED_SEARCH that need review.
    Returns (entries, lines) where entries contain all parsed data.
    """
    with open(org_path, "r", encoding="utf-8") as f:
        lines = f.readlines()

    entries = []
    current_entry = None

    for i, line in enumerate(lines):
        if line.startswith("* "):
            if current_entry is not None:
                current_entry["end_line"] = i
                entries.append(current_entry)

            heading_text = line[2:].rstrip("\n")
            current_entry = {
                "line_number": i,
                "heading": heading_text,
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


def search_tmdb(title: str, year: Optional[int] = None, api_key: str = None) -> List[Dict]:
    """Search TMDB for a movie title."""
    params = {
        "api_key": api_key,
        "query": title,
        "include_adult": "false",
    }

    if year:
        params["year"] = year

    try:
        response = requests.get(TMDB_SEARCH_URL, params=params, timeout=10)
        response.raise_for_status()
        data = response.json()
        return data.get("results", [])
    except Exception as e:
        print(f"  Error searching TMDB: {e}", file=sys.stderr)
        return []


def calculate_confidence(original: str, suggested: str, tmdb_title: str, year_hint: Optional[int], tmdb_year: Optional[str]) -> float:
    """Calculate match confidence score."""
    # Title similarity
    title_ratio = fuzz.ratio(suggested.lower(), tmdb_title.lower())

    # Year bonus
    year_bonus = 0
    if year_hint and tmdb_year:
        try:
            tmdb_year_int = int(tmdb_year[:4])
            if tmdb_year_int == year_hint:
                year_bonus = 20
            elif abs(tmdb_year_int - year_hint) <= 1:
                year_bonus = 10
        except (ValueError, TypeError):
            pass

    return min(100, title_ratio + year_bonus)


def get_api_key_from_pass() -> str:
    """Retrieve TMDB API key from GNU pass store."""
    try:
        result = subprocess.run(
            ['pass', 'tmdb/api-key'],
            capture_output=True,
            text=True,
            check=True
        )
        return result.stdout.strip()
    except subprocess.CalledProcessError as e:
        print(f"Error retrieving API key from pass: {e}", file=sys.stderr)
        sys.exit(1)
    except FileNotFoundError:
        print("Error: 'pass' command not found. Please install pass or provide API key as argument.", file=sys.stderr)
        sys.exit(1)


def update_org_entries(org_path: Path, api_key: str, dry_run: bool = False) -> int:
    """
    Update org file entries with TMDB_ID from SUGGESTED_SEARCH queries.
    Returns number of entries updated.
    """
    entries, lines = parse_org_file(org_path)

    # Filter entries that need processing
    to_process = []
    for entry in entries:
        props = entry["properties"]

        # Skip if no SUGGESTED_SEARCH
        if "SUGGESTED_SEARCH" not in props:
            continue

        # Skip if already has TMDB_ID and doesn't need review
        if "TMDB_ID" in props and props.get("NEEDS_REVIEW") != "true":
            continue

        to_process.append(entry)

    print(f"Found {len(to_process)} entries to process")

    updates = 0

    for idx, entry in enumerate(to_process, 1):
        props = entry["properties"]
        suggested_search = props["SUGGESTED_SEARCH"]
        heading = entry["heading"]

        # Extract year hint from SUGGESTED_SEARCH or YEAR property
        year_hint = None
        if "YEAR" in props:
            try:
                year_hint = int(props["YEAR"])
            except ValueError:
                pass

        if not year_hint:
            year_match = re.search(r'\b(19\d{2}|20[0-2]\d)\b', suggested_search)
            if year_match:
                year_hint = int(year_match.group(1))

        print(f"\n[{idx}/{len(to_process)}] {heading}")
        print(f"  Searching: {suggested_search}" + (f" ({year_hint})" if year_hint else ""))

        # Search TMDB
        results = search_tmdb(suggested_search, year_hint, api_key)
        time.sleep(RATE_LIMIT_DELAY)

        if not results:
            print(f"  No results found")
            continue

        # Find best match
        best_match = None
        best_confidence = 0

        for result in results[:5]:  # Check top 5 results
            tmdb_title = result.get("title", "")
            tmdb_year = result.get("release_date", "")
            tmdb_id = result.get("id")

            confidence = calculate_confidence(
                heading,
                suggested_search,
                tmdb_title,
                year_hint,
                tmdb_year
            )

            if confidence > best_confidence:
                best_confidence = confidence
                best_match = result

        if best_match:
            tmdb_id = best_match["id"]
            tmdb_title = best_match.get("title", "")
            tmdb_year = best_match.get("release_date", "")[:4] if best_match.get("release_date") else ""

            print(f"  ✓ Match: {tmdb_title} ({tmdb_year}) [ID: {tmdb_id}] - confidence: {best_confidence:.1f}")

            if not dry_run:
                # Update the properties in memory
                props["TMDB_ID"] = str(tmdb_id)
                props["TMDB_TITLE"] = tmdb_title
                props["TMDB_CONFIDENCE"] = f"{best_confidence:.2f}"

                # Rebuild properties block
                prop_lines = [":PROPERTIES:\n"]
                for prop_name, prop_value in props.items():
                    prop_lines.append(f":{prop_name}: {prop_value}\n")
                prop_lines.append(":END:\n")

                # Replace in lines
                start = entry["properties_start"]
                end = entry["properties_end"] + 1
                lines[start:end] = prop_lines

                updates += 1
        else:
            print(f"  ✗ No match found")

    # Write back if not dry run
    if not dry_run and updates > 0:
        print(f"\nWriting {updates} updates to {org_path.name}...")
        with open(org_path, "w", encoding="utf-8") as f:
            f.writelines(lines)
        print("Done!")
    elif dry_run:
        print(f"\nDry run complete - would have updated {updates} entries")

    return updates


def main():
    dry_run = "--dry-run" in sys.argv

    print("Retrieving API key from pass store (tmdb/api-key)...")
    api_key = get_api_key_from_pass()

    script_dir = Path(__file__).parent
    resources_dir = script_dir / "resources"
    org_path = resources_dir / "review_required.org"

    if not org_path.exists():
        print(f"Error: {org_path} not found")
        return 1

    print(f"Processing {org_path}")
    if dry_run:
        print("DRY RUN MODE - no changes will be made")

    updates = update_org_entries(org_path, api_key, dry_run)

    print(f"\nTotal updates: {updates}")

    return 0


if __name__ == "__main__":
    exit(main())
