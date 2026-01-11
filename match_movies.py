#!/usr/bin/env python3
"""
Phase 1: Match org file movie entries to TMDB records.
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


def parse_org_entry(line: str, line_number: int) -> Optional[Dict]:
    """
    Parse an org-mode heading to extract movie title and metadata hints.

    Example inputs:
    - "* 007 - Quantum Trost (Ein_Daniel Craig)"
    - "* 100.000 Dollar in der Sonne (F/IT ´1964_/Trailer)"
    - "* 1.000 Mexikaner (D '2016_/Trailer)"
    """
    # Match org-mode heading
    match = re.match(r'^\*\s+(.+)$', line.strip())
    if not match:
        return None

    full_title = match.group(1).strip()

    # Extract year from various patterns: (1964), ´1964, '2016
    # Only accept years between 1920 and 2025 (excludes numbers like 1001)
    year_match = re.search(r"[´']?(\d{4})", full_title)
    if year_match:
        potential_year = int(year_match.group(1))
        year = potential_year if 1920 <= potential_year <= 2025 else None
    else:
        year = None

    # Remove parenthetical metadata for cleaned title
    # Remove patterns like (Ein_Daniel Craig), (D '2016_/Trailer), (F/IT ´1964_/Trailer)
    cleaned = re.sub(r'\([^)]*\)', '', full_title).strip()

    # Remove quotes and special characters
    cleaned = re.sub(r"[´']", '', cleaned)

    # Remove trailing underscores and slashes
    cleaned = re.sub(r'[_/]\s*$', '', cleaned)

    # Normalize spaces
    cleaned = re.sub(r'\s+', ' ', cleaned).strip()

    return {
        'line_number': line_number,
        'original_title': full_title,
        'cleaned_title': cleaned,
        'year_hint': year
    }


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


def calculate_match_score(original: str, tmdb_title: str, tmdb_original_title: str,
                         year_hint: Optional[int], tmdb_year: Optional[str]) -> float:
    """
    Calculate confidence score for a TMDB match.
    Uses fuzzy string matching and year validation.
    """
    # Compare with both German title and original title
    score_title = fuzz.ratio(original.lower(), tmdb_title.lower())
    score_original = fuzz.ratio(original.lower(), tmdb_original_title.lower())

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


def find_best_match(parsed: Dict, api_key: str) -> Dict:
    """
    Find the best TMDB match for a parsed org entry.
    Returns match info with confidence score.
    """
    cleaned_title = parsed['cleaned_title']
    year_hint = parsed['year_hint']

    # Try with year first if available
    results = search_tmdb(cleaned_title, year_hint, api_key)

    # If no results with year, try without
    if not results and year_hint:
        time.sleep(0.25)  # Rate limiting
        results = search_tmdb(cleaned_title, None, api_key)

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
            cleaned_title,
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
    Process entire org file and match all movie entries.
    """
    matches = []

    with open(org_path, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    total_entries = sum(1 for line in lines if line.strip().startswith('*'))
    print(f"Found {total_entries} movie entries to process...")

    processed = 0
    for line_num, line in enumerate(lines, start=1):
        if not line.strip().startswith('*'):
            continue

        parsed = parse_org_entry(line, line_num)
        if not parsed:
            continue

        processed += 1
        print(f"[{processed}/{total_entries}] Processing: {parsed['cleaned_title']}", end='')

        match_info = find_best_match(parsed, api_key)

        result = {
            **parsed,
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
    fieldnames = [
        'line_number',
        'original_title',
        'cleaned_title',
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
    print(f"3. Use verified CSV for Phase 2 enrichment")


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
        print("Usage: python match_movies.py <org_file> [tmdb_api_key] [output_csv]")
        print("\nIf tmdb_api_key is not provided, will attempt to retrieve from 'pass tmdb/api-key'")
        print("Get your TMDB API key from: https://www.themoviedb.org/settings/api")
        sys.exit(1)

    org_path = Path(sys.argv[1])

    # If API key provided as argument, use it; otherwise get from pass
    if len(sys.argv) >= 3 and not sys.argv[2].endswith('.csv'):
        api_key = sys.argv[2]
        output_path = Path(sys.argv[3]) if len(sys.argv) > 3 else org_path.with_suffix('.csv')
    else:
        print("Retrieving API key from pass store (tmdb/api-key)...")
        api_key = get_api_key_from_pass()
        output_path = Path(sys.argv[2]) if len(sys.argv) > 2 else org_path.with_suffix('.csv')

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
