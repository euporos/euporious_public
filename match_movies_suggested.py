#!/usr/bin/env python3
"""
Phase 1b: Match org file movie entries using SUGGESTED_SEARCH property.
Only processes entries with SUGGESTED_SEARCH that don't have TMDB_ID.
Outputs a CSV mapping file for manual review and verification.
"""

import re
import csv
import sys
import time
import subprocess
from pathlib import Path
from typing import Optional, Tuple, List, Dict
import requests
from rapidfuzz import fuzz

# TMDB API Configuration
TMDB_API_KEY = None  # Will be set via command line or environment
TMDB_BASE_URL = "https://api.themoviedb.org/3"
TMDB_SEARCH_URL = f"{TMDB_BASE_URL}/search/movie"

# Matching thresholds
HIGH_CONFIDENCE_THRESHOLD = 85
MEDIUM_CONFIDENCE_THRESHOLD = 70


def parse_org_file_with_properties(org_path: Path) -> List[Dict]:
    """
    Parse org file and extract entries with SUGGESTED_SEARCH property.
    Only returns entries that have SUGGESTED_SEARCH and no TMDB_ID.

    Returns list of dicts with:
    - line_number: line number of the heading
    - original_title: the heading text
    - suggested_search: the SUGGESTED_SEARCH property value
    - year_hint: extracted year if any
    """
    entries = []

    with open(org_path, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    i = 0
    while i < len(lines):
        line = lines[i]

        # Check if this is a heading
        if not line.strip().startswith('*'):
            i += 1
            continue

        # Extract heading
        match = re.match(r'^\*+\s+(.+)$', line.strip())
        if not match:
            i += 1
            continue

        heading_text = match.group(1).strip()
        heading_line = i + 1  # 1-based line number

        # Look for properties section
        i += 1
        properties = {}

        # Check if next line starts a properties drawer
        if i < len(lines) and lines[i].strip() == ':PROPERTIES:':
            i += 1
            # Read properties until :END:
            while i < len(lines):
                prop_line = lines[i].strip()
                if prop_line == ':END:':
                    i += 1
                    break

                # Parse property: :PROP_NAME: value
                prop_match = re.match(r'^:([^:]+):\s*(.*)$', prop_line)
                if prop_match:
                    prop_name = prop_match.group(1).strip()
                    prop_value = prop_match.group(2).strip()
                    properties[prop_name] = prop_value

                i += 1

        # Check if this entry qualifies:
        # 1. Has SUGGESTED_SEARCH property
        # 2. Does NOT have TMDB_ID property (or it's empty)
        suggested_search = properties.get('SUGGESTED_SEARCH', '').strip()
        tmdb_id = properties.get('TMDB_ID', '').strip()

        if suggested_search and not tmdb_id:
            # Prefer YEAR_HINT property, fallback to extracting from suggested_search
            year_hint = properties.get('YEAR_HINT', '').strip()
            if year_hint:
                try:
                    year_hint = int(year_hint)
                except ValueError:
                    year_hint = None
            else:
                # Extract year hint from suggested_search if present
                year_match = re.search(r'\b(19\d{2}|20[0-2]\d)\b', suggested_search)
                year_hint = int(year_match.group(1)) if year_match else None

            entries.append({
                'line_number': heading_line,
                'original_title': heading_text,
                'suggested_search': suggested_search,
                'year_hint': year_hint
            })

    return entries


def search_tmdb(title: str, year: Optional[int] = None, api_key: str = None) -> List[Dict]:
    """
    Search TMDB for a movie title.
    Returns list of potential matches.
    """
    if not api_key:
        raise ValueError("TMDB API key required")

    params = {
        'api_key': api_key,
        'query': title,
        'language': 'de-DE',  # Search with German language support
        'include_adult': False
    }

    if year:
        params['year'] = year

    try:
        response = requests.get(TMDB_SEARCH_URL, params=params, timeout=10)
        response.raise_for_status()
        data = response.json()
        return data.get('results', [])
    except requests.RequestException as e:
        print(f"Error searching TMDB for '{title}': {e}", file=sys.stderr)
        return []


def calculate_match_score(search_term: str, tmdb_title: str, tmdb_original_title: str,
                         year_hint: Optional[int], tmdb_year: Optional[str]) -> float:
    """
    Calculate confidence score for a TMDB match.
    Uses fuzzy string matching and year validation.
    """
    # Compare with both German title and original title
    score_title = fuzz.ratio(search_term.lower(), tmdb_title.lower())
    score_original = fuzz.ratio(search_term.lower(), tmdb_original_title.lower())

    # Use the better score
    base_score = max(score_title, score_original)

    # Boost score if years match
    if year_hint and tmdb_year:
        tmdb_year_int = int(tmdb_year.split('-')[0]) if tmdb_year else None
        if tmdb_year_int == year_hint:
            base_score = min(100, base_score + 10)
        elif abs(tmdb_year_int - year_hint) <= 1:
            base_score = min(100, base_score + 5)

    return base_score


def find_best_match(entry: Dict, api_key: str) -> Dict:
    """
    Find the best TMDB match for an entry.
    Returns match info with confidence score.
    """
    search_term = entry['suggested_search']
    year_hint = entry['year_hint']

    # Try with year first if available
    results = search_tmdb(search_term, year_hint, api_key)

    # If no results with year, try without
    if not results and year_hint:
        time.sleep(0.25)  # Rate limiting
        results = search_tmdb(search_term, None, api_key)

    if not results:
        return {
            'tmdb_id': None,
            'confidence': 0,
            'tmdb_title': None,
            'tmdb_original_title': None,
            'year': None,
            'genres': None,
            'needs_review': True,
            'match_status': 'NO_RESULTS'
        }

    # Score all results
    scored_results = []
    for result in results[:10]:  # Check top 10 results
        score = calculate_match_score(
            search_term,
            result.get('title', ''),
            result.get('original_title', ''),
            year_hint,
            result.get('release_date')
        )
        scored_results.append((score, result))

    # Get best match
    scored_results.sort(reverse=True, key=lambda x: x[0])
    best_score, best_result = scored_results[0]

    # Determine if review needed
    needs_review = best_score < HIGH_CONFIDENCE_THRESHOLD
    match_status = 'HIGH_CONFIDENCE' if best_score >= HIGH_CONFIDENCE_THRESHOLD else \
                   'MEDIUM_CONFIDENCE' if best_score >= MEDIUM_CONFIDENCE_THRESHOLD else \
                   'LOW_CONFIDENCE'

    return {
        'tmdb_id': best_result.get('id'),
        'confidence': round(best_score, 2),
        'tmdb_title': best_result.get('title'),
        'tmdb_original_title': best_result.get('original_title'),
        'year': best_result.get('release_date', '').split('-')[0] if best_result.get('release_date') else None,
        'genres': None,  # Will be filled in Phase 2
        'needs_review': needs_review,
        'match_status': match_status
    }


def process_org_file(org_path: Path, api_key: str) -> List[Dict]:
    """
    Process org file and match entries with SUGGESTED_SEARCH.
    """
    print("Parsing org file for entries with SUGGESTED_SEARCH...")
    entries = parse_org_file_with_properties(org_path)

    total_entries = len(entries)
    print(f"Found {total_entries} entries with SUGGESTED_SEARCH and no TMDB_ID...")

    if total_entries == 0:
        print("\nNo entries to process. All entries either:")
        print("  - Don't have SUGGESTED_SEARCH property, or")
        print("  - Already have TMDB_ID property")
        return []

    matches = []
    processed = 0

    for entry in entries:
        processed += 1
        print(f"[{processed}/{total_entries}] Processing: {entry['suggested_search']}", end='')

        match_info = find_best_match(entry, api_key)

        result = {
            **entry,
            **match_info
        }
        matches.append(result)

        # Print result
        status_icon = "✓" if not match_info['needs_review'] else "⚠"
        print(f" → {status_icon} {match_info['match_status']} ({match_info['confidence']}%)")

        # Rate limiting
        time.sleep(0.25)

    return matches


def write_csv(matches: List[Dict], output_path: Path):
    """
    Write matches to CSV file for manual review.
    """
    if not matches:
        print("\nNo matches to write.")
        return

    fieldnames = [
        'line_number',
        'original_title',
        'suggested_search',
        'year_hint',
        'tmdb_id',
        'confidence',
        'tmdb_title',
        'tmdb_original_title',
        'year',
        'match_status',
        'needs_review'
    ]

    with open(output_path, 'w', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction='ignore')
        writer.writeheader()
        writer.writerows(matches)

    # Print statistics
    total = len(matches)
    high_conf = sum(1 for m in matches if m['confidence'] >= HIGH_CONFIDENCE_THRESHOLD)
    medium_conf = sum(1 for m in matches if MEDIUM_CONFIDENCE_THRESHOLD <= m['confidence'] < HIGH_CONFIDENCE_THRESHOLD)
    low_conf = sum(1 for m in matches if m['confidence'] < MEDIUM_CONFIDENCE_THRESHOLD)
    no_match = sum(1 for m in matches if m['tmdb_id'] is None)

    print(f"\n{'='*60}")
    print(f"Matching Complete!")
    print(f"{'='*60}")
    print(f"Total entries:        {total}")
    print(f"High confidence:      {high_conf} ({high_conf/total*100:.1f}%)")
    print(f"Medium confidence:    {medium_conf} ({medium_conf/total*100:.1f}%)")
    print(f"Low confidence:       {low_conf} ({low_conf/total*100:.1f}%)")
    print(f"No match found:       {no_match} ({no_match/total*100:.1f}%)")
    print(f"\nReview needed:        {sum(1 for m in matches if m['needs_review'])}")
    print(f"\nOutput written to: {output_path}")
    print(f"\nNext steps:")
    print(f"1. Review entries where needs_review=True")
    print(f"2. Manually correct tmdb_id where needed")
    print(f"3. Use verified CSV for applying matches back to org file")


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


def main():
    # Parse arguments: org_file is required, api_key is optional (will use pass), output_csv is optional
    if len(sys.argv) < 2:
        print("Usage: python match_movies_suggested.py <org_file> [tmdb_api_key] [output_csv]")
        print("\nIf tmdb_api_key is not provided, will attempt to retrieve from 'pass tmdb/api-key'")
        print("Get your TMDB API key from: https://www.themoviedb.org/settings/api")
        print("\nThis script only processes entries that:")
        print("  - Have a SUGGESTED_SEARCH property")
        print("  - Do NOT have a TMDB_ID property")
        sys.exit(1)

    org_path = Path(sys.argv[1])

    # If API key provided as argument, use it; otherwise get from pass
    if len(sys.argv) >= 3 and not sys.argv[2].endswith('.csv'):
        api_key = sys.argv[2]
        output_path = Path(sys.argv[3]) if len(sys.argv) > 3 else org_path.parent / f"{org_path.stem}_suggested_matches.csv"
    else:
        print("Retrieving API key from pass store (tmdb/api-key)...")
        api_key = get_api_key_from_pass()
        output_path = Path(sys.argv[2]) if len(sys.argv) > 2 else org_path.parent / f"{org_path.stem}_suggested_matches.csv"

    if not org_path.exists():
        print(f"Error: File not found: {org_path}")
        sys.exit(1)

    print(f"Processing: {org_path}")
    print(f"Output will be written to: {output_path}")
    print()

    try:
        # Check if rapidfuzz is available
        import rapidfuzz
    except ImportError:
        print("Error: rapidfuzz library not found")
        print("Install with: pip install rapidfuzz requests")
        sys.exit(1)

    matches = process_org_file(org_path, api_key)
    write_csv(matches, output_path)


if __name__ == '__main__':
    main()
