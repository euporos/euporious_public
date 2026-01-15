#!/usr/bin/env python3
"""
Match org file movie entries to TMDB and write IDs directly to org file.
Adds TMDB_ID to properties blocks for review and future enrichment.

Features:
- Uses SUGGESTED_SEARCH property when present (from AI verification)
- Skips entries already verified by AI (AI_VERIFIED: true, NEEDS_REVIEW: false)
- Removes SUGGESTED_SEARCH property after successful match
- Skips entries that already have TMDB_ID
"""

import re
import sys
import time
import subprocess
from pathlib import Path
from typing import Optional, List, Dict, Tuple
import requests
from rapidfuzz import fuzz

# TMDB API Configuration
TMDB_BASE_URL = "https://api.themoviedb.org/3"
TMDB_SEARCH_URL = f"{TMDB_BASE_URL}/search/movie"

# Matching thresholds
HIGH_CONFIDENCE_THRESHOLD = 85
MEDIUM_CONFIDENCE_THRESHOLD = 70


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


def parse_org_title(line: str) -> Optional[Tuple[str, str, Optional[int]]]:
    """
    Parse an org-mode heading to extract movie title and metadata hints.
    Returns: (original_title, cleaned_title, year_hint) or None
    """
    match = re.match(r'^\*\s+(.+)$', line.strip())
    if not match:
        return None

    full_title = match.group(1).strip()

    # Extract year from various patterns: (1964), ´1964, '2016
    # Only accept years between 1920 and 2025
    year_match = re.search(r"[´']?(\d{4})", full_title)
    if year_match:
        potential_year = int(year_match.group(1))
        year = potential_year if 1920 <= potential_year <= 2025 else None
    else:
        year = None

    # Remove parenthetical metadata for cleaned title
    cleaned = re.sub(r'\([^)]*\)', '', full_title).strip()
    # Remove quotes and special characters
    cleaned = re.sub(r"[´']", '', cleaned)
    # Remove trailing underscores and slashes
    cleaned = re.sub(r'[_/]\s*$', '', cleaned)
    # Normalize spaces
    cleaned = re.sub(r'\s+', ' ', cleaned).strip()

    return (full_title, cleaned, year)


def search_tmdb(title: str, year: Optional[int], api_key: str) -> List[Dict]:
    """Search TMDB for a movie title."""
    params = {
        'api_key': api_key,
        'query': title,
        'language': 'de-DE',
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
    """Calculate confidence score for a TMDB match."""
    score_title = fuzz.ratio(original.lower(), tmdb_title.lower())
    score_original = fuzz.ratio(original.lower(), tmdb_original_title.lower())
    base_score = max(score_title, score_original)

    # Boost score if years match
    if year_hint and tmdb_year:
        tmdb_year_int = int(tmdb_year.split('-')[0]) if tmdb_year else None
        if tmdb_year_int == year_hint:
            base_score = min(100, base_score + 10)
        elif abs(tmdb_year_int - year_hint) <= 1:
            base_score = min(100, base_score + 5)

    return base_score


def find_best_match(cleaned_title: str, year_hint: Optional[int], api_key: str) -> Dict:
    """Find the best TMDB match for a movie title."""
    # Try with year first if available
    results = search_tmdb(cleaned_title, year_hint, api_key)

    # If no results with year, try without
    if not results and year_hint:
        time.sleep(0.25)
        results = search_tmdb(cleaned_title, None, api_key)

    if not results:
        return {
            'tmdb_id': None,
            'confidence': 0,
            'tmdb_title': None,
            'needs_review': True,
            'match_status': 'NO_RESULTS'
        }

    # Score all results
    scored_results = []
    for result in results[:10]:
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

    needs_review = best_score < HIGH_CONFIDENCE_THRESHOLD
    match_status = 'HIGH_CONFIDENCE' if best_score >= HIGH_CONFIDENCE_THRESHOLD else \
                   'MEDIUM_CONFIDENCE' if best_score >= MEDIUM_CONFIDENCE_THRESHOLD else \
                   'LOW_CONFIDENCE'

    return {
        'tmdb_id': best_result.get('id'),
        'confidence': round(best_score, 2),
        'tmdb_title': best_result.get('title'),
        'tmdb_original_title': best_result.get('original_title'),
        'needs_review': needs_review,
        'match_status': match_status
    }


def write_lines_to_file(org_path: Path, lines: List[str]):
    """Write lines to org file atomically."""
    # Write to temp file first, then rename for atomicity
    temp_path = org_path.with_suffix('.org.tmp')
    with open(temp_path, 'w', encoding='utf-8') as f:
        f.writelines(lines)
    temp_path.replace(org_path)


def process_org_file(org_path: Path, api_key: str, backup: bool = True) -> Dict:
    """
    Process org file, match movies to TMDB, and write IDs to properties.
    Writes incrementally after each movie for resumability.
    Returns statistics about the processing.
    """
    # Backup original file on first run only
    if backup:
        backup_path = org_path.with_suffix('.org.bak')
        if not backup_path.exists():
            with open(org_path, 'r', encoding='utf-8') as f:
                backup_lines = f.readlines()
            with open(backup_path, 'w', encoding='utf-8') as f:
                f.writelines(backup_lines)
            print(f"Backup created: {backup_path}\n")
        else:
            print(f"Backup already exists: {backup_path}\n")

    # Parse and process
    stats = {
        'total': 0,
        'high_confidence': 0,
        'medium_confidence': 0,
        'low_confidence': 0,
        'no_match': 0,
        'skipped': 0,
        'skipped_verified': 0,
        'used_suggested_search': 0
    }

    # Re-read file each time to get latest state (in case of previous incremental writes)
    with open(org_path, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    output_lines = []
    i = 0
    while i < len(lines):
        line = lines[i]

        # Check if this is a movie heading
        if line.strip().startswith('*'):
            parsed = parse_org_title(line)

            if parsed:
                original_title, cleaned_title, year_hint = parsed
                stats['total'] += 1

                # Check if next line starts properties block
                has_properties = (i + 1 < len(lines) and
                                lines[i + 1].strip() == ':PROPERTIES:')

                # Parse properties if they exist
                properties = {}
                if has_properties:
                    j = i + 2
                    while j < len(lines) and not lines[j].strip() == ':END:':
                        prop_match = re.match(r'^:(\w+):\s*(.*)$', lines[j].strip())
                        if prop_match:
                            key = prop_match.group(1)
                            value = prop_match.group(2).strip()
                            properties[key] = value
                        j += 1

                # Check if already verified by AI
                if properties.get('AI_VERIFIED') == 'true' and properties.get('NEEDS_REVIEW') == 'false':
                    print(f"[{stats['total']}] Skipping (AI verified): {cleaned_title}")
                    stats['skipped_verified'] += 1
                    output_lines.append(line)
                    i += 1
                    while i < len(lines) and not lines[i].strip().startswith('*'):
                        output_lines.append(lines[i])
                        i += 1
                    continue

                # Check if already has TMDB_ID
                if 'TMDB_ID' in properties:
                    print(f"[{stats['total']}] Skipping (already has TMDB_ID): {cleaned_title}")
                    stats['skipped'] += 1
                    output_lines.append(line)
                    i += 1
                    while i < len(lines) and not lines[i].strip().startswith('*'):
                        output_lines.append(lines[i])
                        i += 1
                    continue

                # Check for SUGGESTED_SEARCH property
                search_query = cleaned_title
                if 'SUGGESTED_SEARCH' in properties:
                    search_query = properties['SUGGESTED_SEARCH']
                    stats['used_suggested_search'] += 1
                    print(f"[{stats['total']}] Processing (using suggested): {search_query}", end='')
                else:
                    print(f"[{stats['total']}] Processing: {cleaned_title}", end='')

                # Search TMDB
                match_info = find_best_match(search_query, year_hint, api_key)

                # Update stats
                if match_info['tmdb_id'] is None:
                    stats['no_match'] += 1
                elif match_info['confidence'] >= HIGH_CONFIDENCE_THRESHOLD:
                    stats['high_confidence'] += 1
                elif match_info['confidence'] >= MEDIUM_CONFIDENCE_THRESHOLD:
                    stats['medium_confidence'] += 1
                else:
                    stats['low_confidence'] += 1

                # Print result
                status_icon = "✓" if not match_info['needs_review'] else "⚠"
                print(f" → {status_icon} {match_info['match_status']} ({match_info['confidence']}%)")

                # Write heading
                output_lines.append(line)

                # Handle properties block
                if has_properties:
                    # Properties exist, add TMDB info
                    output_lines.append(lines[i + 1])  # :PROPERTIES:
                    i += 2

                    # Copy existing properties, but skip SUGGESTED_SEARCH if match found
                    props_lines = []
                    while i < len(lines) and not lines[i].strip() == ':END:':
                        # Skip SUGGESTED_SEARCH if we found a match
                        if not (lines[i].strip().startswith(':SUGGESTED_SEARCH:') and match_info['tmdb_id']):
                            props_lines.append(lines[i])
                        i += 1

                    # Add all existing properties
                    output_lines.extend(props_lines)

                    # Add TMDB properties
                    if match_info['tmdb_id']:
                        output_lines.append(f":TMDB_ID: {match_info['tmdb_id']}\n")
                        output_lines.append(f":TMDB_TITLE: {match_info['tmdb_title']}\n")
                        output_lines.append(f":TMDB_CONFIDENCE: {match_info['confidence']}\n")
                    if match_info['needs_review']:
                        output_lines.append(":NEEDS_REVIEW: true\n")

                    # Add :END:
                    if i < len(lines):
                        output_lines.append(lines[i])  # :END:
                        i += 1

                    # Copy remaining content for this entry (body text, blank lines, etc.)
                    while i < len(lines) and not lines[i].strip().startswith('*'):
                        output_lines.append(lines[i])
                        i += 1

                    # Write incrementally after processing this movie
                    remaining_lines = lines[i:]
                    write_lines_to_file(org_path, output_lines + remaining_lines)

                    # Rate limiting
                    time.sleep(0.25)
                    continue

                else:
                    # No properties block, create one
                    i += 1
                    output_lines.append(':PROPERTIES:\n')
                    if match_info['tmdb_id']:
                        output_lines.append(f":TMDB_ID: {match_info['tmdb_id']}\n")
                        output_lines.append(f":TMDB_TITLE: {match_info['tmdb_title']}\n")
                        output_lines.append(f":TMDB_CONFIDENCE: {match_info['confidence']}\n")
                    if match_info['needs_review']:
                        output_lines.append(":NEEDS_REVIEW: true\n")
                    output_lines.append(':END:\n')

                # Copy remaining content for this entry (body text, blank lines, etc.)
                while i < len(lines) and not lines[i].strip().startswith('*'):
                    output_lines.append(lines[i])
                    i += 1

                # Write incrementally after processing this movie
                # Append remaining unprocessed lines
                remaining_lines = lines[i:]
                write_lines_to_file(org_path, output_lines + remaining_lines)

                # Rate limiting
                time.sleep(0.25)
                continue

        # Not a movie heading or not parsed, copy as-is
        output_lines.append(line)
        i += 1

    # Final write for any remaining non-movie lines at end of file
    if output_lines:
        write_lines_to_file(org_path, output_lines)

    return stats


def print_statistics(stats: Dict):
    """Print processing statistics."""
    total = stats['total']
    if total == 0:
        print("\nNo movie entries found.")
        return

    print(f"\n{'='*60}")
    print(f"Processing Complete!")
    print(f"{'='*60}")
    print(f"Total entries:        {total}")

    if stats.get('skipped_verified', 0) > 0:
        print(f"Skipped (AI verified): {stats['skipped_verified']}")
    if stats['skipped'] > 0:
        print(f"Skipped (has ID):     {stats['skipped']}")

    processed = total - stats['skipped'] - stats.get('skipped_verified', 0)
    if processed > 0:
        print(f"\nProcessed:            {processed}")
        if stats.get('used_suggested_search', 0) > 0:
            print(f"  Used AI suggestions: {stats['used_suggested_search']}")
        print(f"  High confidence:     {stats['high_confidence']} ({stats['high_confidence']/processed*100:.1f}%)")
        print(f"  Medium confidence:   {stats['medium_confidence']} ({stats['medium_confidence']/processed*100:.1f}%)")
        print(f"  Low confidence:      {stats['low_confidence']} ({stats['low_confidence']/processed*100:.1f}%)")
        print(f"  No match found:      {stats['no_match']} ({stats['no_match']/processed*100:.1f}%)")

    print(f"\nReview needed:        {stats['medium_confidence'] + stats['low_confidence'] + stats['no_match']}")
    print(f"\nNext steps:")
    print(f"1. Review entries with :NEEDS_REVIEW: true in the org file")
    print(f"2. Manually correct :TMDB_ID: where needed")
    print(f"3. Search TMDB manually for entries with no match")
    print(f"4. Remove :NEEDS_REVIEW: property when verified")
    print(f"5. Run Phase 2 enrichment script")


def main():
    if len(sys.argv) < 2:
        print("Usage: python enrich_org_tmdb.py <org_file> [tmdb_api_key] [--no-backup]")
        print("\nIf tmdb_api_key is not provided, will attempt to retrieve from 'pass tmdb/api-key'")
        print("Use --no-backup to skip creating a backup file")
        sys.exit(1)

    org_path = Path(sys.argv[1])
    backup = '--no-backup' not in sys.argv

    # Get API key
    if len(sys.argv) >= 3 and sys.argv[2] != '--no-backup':
        api_key = sys.argv[2]
    else:
        print("Retrieving API key from pass store (tmdb/api-key)...")
        api_key = get_api_key_from_pass()

    if not org_path.exists():
        print(f"Error: File not found: {org_path}")
        sys.exit(1)

    try:
        import rapidfuzz
    except ImportError:
        print("Error: rapidfuzz library not found")
        print("Install with: pip install rapidfuzz requests")
        sys.exit(1)

    print(f"Processing: {org_path}\n")

    stats = process_org_file(org_path, api_key, backup)
    print_statistics(stats)


if __name__ == '__main__':
    main()
