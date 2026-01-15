#!/usr/bin/env python3
"""
Update review_required.org with TMDB_TITLE and TMDB_ID from TMDB API.
Uses SUGGESTED_SEARCH and YEAR fields to query the API.
"""

import re
import subprocess
import sys
import urllib.request
import urllib.parse
import json
from typing import Optional, Dict, List, Tuple


TMDB_SEARCH_URL = "https://api.themoviedb.org/3/search/movie"
ORG_FILE = "resources/review_required.org"

# Confidence threshold for auto-updating
CONFIDENCE_THRESHOLD = 70


def get_api_key() -> str:
    """Get TMDB API key from pass."""
    try:
        result = subprocess.run(
            ["pass", "tmdb/api-key"],
            capture_output=True,
            text=True,
            check=True
        )
        return result.stdout.strip()
    except subprocess.CalledProcessError as e:
        print(f"Error getting API key from pass: {e}", file=sys.stderr)
        sys.exit(1)


def search_tmdb(query: str, year: Optional[int], api_key: str) -> List[Dict]:
    """Search TMDB for a movie."""
    params = {
        'api_key': api_key,
        'query': query,
        'language': 'de-DE',
        'include_adult': 'false'
    }

    if year:
        params['year'] = str(year)

    url = f"{TMDB_SEARCH_URL}?{urllib.parse.urlencode(params)}"

    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            data = json.loads(response.read().decode())
            return data.get('results', [])
    except Exception as e:
        print(f"Error querying TMDB for '{query}': {e}", file=sys.stderr)
        return []


def calculate_confidence(result: Dict, original_query: str, year: Optional[int]) -> float:
    """Calculate confidence score for a TMDB result."""
    confidence = result.get('popularity', 0)

    # Boost confidence if titles match closely
    title = result.get('title', '').lower()
    original_title = result.get('original_title', '').lower()
    query_lower = original_query.lower()

    if title == query_lower or original_title == query_lower:
        confidence += 50
    elif query_lower in title or query_lower in original_title:
        confidence += 25

    # Boost if year matches
    if year:
        release_date = result.get('release_date', '')
        if release_date and release_date.startswith(str(year)):
            confidence += 20

    return min(confidence, 100)


def parse_org_entry(lines: List[str], start_idx: int) -> Optional[Tuple[Dict, int]]:
    """
    Parse a single org entry starting at start_idx.
    Returns (entry_dict, end_idx) or None if no valid entry found.
    """
    if start_idx >= len(lines):
        return None

    # Find the headline
    if not lines[start_idx].startswith('*'):
        return None

    headline = lines[start_idx]
    entry = {
        'headline': headline,
        'start_line': start_idx,
        'properties': {},
        'property_lines': {}  # Track which line each property is on
    }

    # Find properties block
    props_start = None
    props_end = None

    for i in range(start_idx + 1, len(lines)):
        line = lines[i]

        if line.strip() == ':PROPERTIES:':
            props_start = i
        elif line.strip() == ':END:':
            props_end = i
            break
        elif line.startswith('*'):
            # Next entry
            break

    if props_start is not None and props_end is not None:
        # Parse properties
        for i in range(props_start + 1, props_end):
            line = lines[i]
            match = re.match(r'^:([A-Z_]+):\s*(.*)$', line)
            if match:
                key = match.group(1)
                value = match.group(2)
                entry['properties'][key] = value
                entry['property_lines'][key] = i

        entry['end_line'] = props_end
    else:
        # No properties block, skip this entry
        return (entry, start_idx + 1)

    return (entry, props_end + 1)


def update_org_file(org_file: str, api_key: str, dry_run: bool = False, limit: Optional[int] = None):
    """Update org file with TMDB data."""

    # Read the file
    with open(org_file, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    # Keep track of lines to update
    updates = {}  # line_number -> new_line_content
    stats = {
        'total': 0,
        'processed': 0,
        'found': 0,
        'updated': 0,
        'skipped': 0
    }

    # Parse entries
    idx = 0
    while idx < len(lines):
        result = parse_org_entry(lines, idx)
        if result is None:
            idx += 1
            continue

        entry, next_idx = result
        idx = next_idx

        # Check if entry has SUGGESTED_SEARCH
        suggested_search = entry['properties'].get('SUGGESTED_SEARCH', '').strip()
        if not suggested_search:
            continue

        stats['total'] += 1

        # Check if we've reached the limit
        if limit and stats['total'] > limit:
            print(f"\nReached limit of {limit} entries, stopping...")
            break

        # Get year if available
        year_str = entry['properties'].get('YEAR', '').strip()
        year = None
        if year_str:
            try:
                year = int(year_str)
            except ValueError:
                pass

        print(f"\n{'='*60}")
        print(f"Processing: {entry['headline'].strip()}")
        print(f"  Search: '{suggested_search}'" + (f" (year: {year})" if year else ""))

        # Check if already has TMDB data
        existing_id = entry['properties'].get('TMDB_ID', '').strip()
        existing_title = entry['properties'].get('TMDB_TITLE', '').strip()

        if existing_id and existing_title:
            print(f"  Current: {existing_title} (ID: {existing_id})")
            print(f"  Skipping (already has TMDB data)")
            stats['skipped'] += 1
            continue

        # Query TMDB
        stats['processed'] += 1
        results = search_tmdb(suggested_search, year, api_key)

        if not results:
            print(f"  No results found")
            continue

        # Get best result
        best_result = results[0]
        confidence = calculate_confidence(best_result, suggested_search, year)

        tmdb_id = best_result['id']
        tmdb_title = best_result.get('title', best_result.get('original_title', ''))
        release_date = best_result.get('release_date', 'unknown')

        print(f"  Found: {tmdb_title} ({release_date})")
        print(f"  TMDB ID: {tmdb_id}")
        print(f"  Confidence: {confidence:.1f}")

        stats['found'] += 1

        # Update the entry (no longer checking confidence threshold)
        props_end = entry['end_line']

        # Find where to insert/update properties
        tmdb_id_line = entry['property_lines'].get('TMDB_ID')
        tmdb_title_line = entry['property_lines'].get('TMDB_TITLE')

        if tmdb_id_line is not None:
            # Update existing TMDB_ID
            updates[tmdb_id_line] = f":TMDB_ID: {tmdb_id}\n"
        else:
            # Insert new TMDB_ID before :END:
            if props_end not in updates:
                updates[props_end] = []
            updates[props_end].append(f":TMDB_ID: {tmdb_id}\n")

        if tmdb_title_line is not None:
            # Update existing TMDB_TITLE
            updates[tmdb_title_line] = f":TMDB_TITLE: {tmdb_title}\n"
        else:
            # Insert new TMDB_TITLE before :END:
            if props_end not in updates:
                updates[props_end] = []
            updates[props_end].append(f":TMDB_TITLE: {tmdb_title}\n")

        print(f"  ✓ Will update")
        stats['updated'] += 1

    # Apply updates
    if updates and not dry_run:
        # Rebuild the file with updates
        new_lines = []

        for line_idx, line in enumerate(lines):
            # Check if this line should be replaced or have insertions
            if line_idx in updates:
                update_value = updates[line_idx]
                if isinstance(update_value, list):
                    # This is an insertion point (before :END:)
                    # Add all new properties before :END:
                    for new_prop in update_value:
                        new_lines.append(new_prop)
                    new_lines.append(line)  # Add the :END: line
                else:
                    # This is a replacement
                    new_lines.append(update_value)
            else:
                new_lines.append(line)

        # Write back
        with open(org_file, 'w', encoding='utf-8') as f:
            f.writelines(new_lines)

        print(f"\n{'='*60}")
        print(f"File updated: {org_file}")

    # Print stats
    print(f"\n{'='*60}")
    print("Statistics:")
    print(f"  Total entries with SUGGESTED_SEARCH: {stats['total']}")
    print(f"  Processed (queried TMDB): {stats['processed']}")
    print(f"  Found matches: {stats['found']}")
    print(f"  Updated: {stats['updated']}")
    print(f"  Skipped (already had TMDB data): {stats['skipped']}")

    if dry_run:
        print("\n(Dry run - no changes written)")


def main():
    """Main entry point."""
    dry_run = '--dry-run' in sys.argv

    # Parse limit argument
    limit = None
    for arg in sys.argv:
        if arg.startswith('--limit='):
            try:
                limit = int(arg.split('=')[1])
            except ValueError:
                print(f"Invalid limit value: {arg}", file=sys.stderr)
                sys.exit(1)

    print("Getting TMDB API key...")
    api_key = get_api_key()

    print(f"Processing: {ORG_FILE}")
    if dry_run:
        print("(DRY RUN MODE)")
    if limit:
        print(f"(Limit: {limit} entries)")

    update_org_file(ORG_FILE, api_key, dry_run, limit)


if __name__ == '__main__':
    main()
