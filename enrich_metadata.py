#!/usr/bin/env python3
"""
Phase 2: Enrich org file entries with detailed metadata from TMDB.
Requires entries to already have TMDB_ID from Phase 1.
"""

import re
import sys
import time
import subprocess
from pathlib import Path
from typing import Optional, List, Dict
import requests

# TMDB API Configuration
TMDB_BASE_URL = "https://api.themoviedb.org/3"


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
        print("Error: 'pass' command not found.", file=sys.stderr)
        sys.exit(1)


def fetch_movie_details(tmdb_id: int, api_key: str) -> Optional[Dict]:
    """
    Fetch detailed movie metadata from TMDB.

    Returns dict with:
    - year, runtime, original_title, original_language
    - director, top_actors (list)
    - countries (list), production_companies (list)
    - genres (list)
    - imdb_id, tmdb_rating, vote_count
    """
    url = f"{TMDB_BASE_URL}/movie/{tmdb_id}"
    params = {
        'api_key': api_key,
        'language': 'de-DE',
        'append_to_response': 'credits,external_ids'
    }

    try:
        response = requests.get(url, params=params, timeout=10)
        response.raise_for_status()
        data = response.json()

        # Extract metadata
        metadata = {}

        # Basic info
        metadata['year'] = data.get('release_date', '').split('-')[0] if data.get('release_date') else None
        metadata['runtime'] = data.get('runtime')
        metadata['original_title'] = data.get('original_title')
        metadata['original_language'] = data.get('original_language', '').upper()

        # Director (from credits)
        credits = data.get('credits', {})
        crew = credits.get('crew', [])
        directors = [person['name'] for person in crew if person.get('job') == 'Director']
        metadata['director'] = directors[0] if directors else None

        # Top 3 actors (from cast)
        cast = credits.get('cast', [])
        metadata['top_actors'] = [actor['name'] for actor in cast[:3]]

        # Countries
        countries = data.get('production_countries', [])
        metadata['countries'] = [c['iso_3166_1'] for c in countries]

        # Production companies
        companies = data.get('production_companies', [])
        metadata['production_companies'] = [c['name'] for c in companies[:3]]  # Top 3

        # Genres
        genres = data.get('genres', [])
        metadata['genres'] = [g['name'] for g in genres]

        # IMDB ID and rating
        external_ids = data.get('external_ids', {})
        metadata['imdb_id'] = external_ids.get('imdb_id')
        metadata['tmdb_rating'] = data.get('vote_average')
        metadata['vote_count'] = data.get('vote_count')

        return metadata

    except requests.RequestException as e:
        print(f"Error fetching details for TMDB ID {tmdb_id}: {e}", file=sys.stderr)
        return None


def write_lines_to_file(org_path: Path, lines: List[str]):
    """Write lines to org file atomically."""
    temp_path = org_path.with_suffix('.org.tmp')
    with open(temp_path, 'w', encoding='utf-8') as f:
        f.writelines(lines)
    temp_path.replace(org_path)


def format_property(key: str, value) -> str:
    """Format a property line for org-mode."""
    if value is None or value == '' or value == []:
        return None

    if isinstance(value, list):
        # Join list with commas
        value_str = ', '.join(str(v) for v in value)
    else:
        value_str = str(value)

    return f":{key.upper()}: {value_str}\n"


def enrich_org_file(org_path: Path, api_key: str, backup: bool = True) -> Dict:
    """
    Enrich org file with detailed metadata from TMDB.
    Processes entries that have TMDB_ID but are missing enrichment.
    """
    # Backup original file
    if backup:
        backup_path = org_path.with_suffix('.org.bak2')
        if not backup_path.exists():
            with open(org_path, 'r', encoding='utf-8') as f:
                backup_lines = f.readlines()
            with open(backup_path, 'w', encoding='utf-8') as f:
                f.writelines(backup_lines)
            print(f"Backup created: {backup_path}\n")
        else:
            print(f"Backup already exists: {backup_path}\n")

    with open(org_path, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    stats = {
        'total_entries': 0,
        'enriched': 0,
        'skipped_no_tmdb_id': 0,
        'skipped_needs_review': 0,
        'skipped_already_enriched': 0,
        'errors': 0
    }

    output_lines = []
    i = 0

    while i < len(lines):
        line = lines[i]

        # Check if this is a movie heading
        if line.strip().startswith('*'):
            stats['total_entries'] += 1

            # Check if next line starts properties block
            has_properties = (i + 1 < len(lines) and
                            lines[i + 1].strip() == ':PROPERTIES:')

            if not has_properties:
                # No properties, skip this entry
                output_lines.append(line)
                i += 1
                continue

            # Parse properties to find TMDB_ID
            output_lines.append(line)  # heading
            output_lines.append(lines[i + 1])  # :PROPERTIES:
            i += 2

            tmdb_id = None
            already_enriched = False
            needs_review = False
            props_lines = []

            # Read all properties
            while i < len(lines) and not lines[i].strip() == ':END:':
                prop_line = lines[i].strip()

                # Check for TMDB_ID
                if prop_line.startswith(':TMDB_ID:'):
                    match = re.search(r':TMDB_ID:\s*(\d+)', prop_line)
                    if match:
                        tmdb_id = int(match.group(1))

                # Check if already enriched (has BACKFILLED property)
                if prop_line.startswith(':BACKFILLED:'):
                    already_enriched = True

                # Check if needs manual review
                if prop_line.startswith(':NEEDS_REVIEW:') and 'true' in prop_line.lower():
                    needs_review = True

                props_lines.append(lines[i])
                i += 1

            # Determine if we should enrich
            if not tmdb_id:
                # No TMDB_ID, skip enrichment
                stats['skipped_no_tmdb_id'] += 1
                output_lines.extend(props_lines)
                if i < len(lines):
                    output_lines.append(lines[i])  # :END:
                    i += 1

                # Copy rest of entry
                while i < len(lines) and not lines[i].strip().startswith('*'):
                    output_lines.append(lines[i])
                    i += 1
                continue

            if needs_review:
                # Needs manual review, skip enrichment
                stats['skipped_needs_review'] += 1
                print(f"[{stats['total_entries']}] Skipping (needs review): TMDB ID {tmdb_id}")
                output_lines.extend(props_lines)
                if i < len(lines):
                    output_lines.append(lines[i])  # :END:
                    i += 1

                # Copy rest of entry
                while i < len(lines) and not lines[i].strip().startswith('*'):
                    output_lines.append(lines[i])
                    i += 1
                continue

            if already_enriched:
                # Already has enrichment, skip
                stats['skipped_already_enriched'] += 1
                print(f"[{stats['total_entries']}] Skipping (already enriched): TMDB ID {tmdb_id}")
                output_lines.extend(props_lines)
                if i < len(lines):
                    output_lines.append(lines[i])  # :END:
                    i += 1

                # Copy rest of entry
                while i < len(lines) and not lines[i].strip().startswith('*'):
                    output_lines.append(lines[i])
                    i += 1
                continue

            # Fetch and enrich
            print(f"[{stats['total_entries']}] Enriching: TMDB ID {tmdb_id}", end='')
            metadata = fetch_movie_details(tmdb_id, api_key)

            if not metadata:
                print(f" → ERROR")
                stats['errors'] += 1
                # Keep existing properties
                output_lines.extend(props_lines)
                if i < len(lines):
                    output_lines.append(lines[i])  # :END:
                    i += 1

                # Copy rest of entry
                while i < len(lines) and not lines[i].strip().startswith('*'):
                    output_lines.append(lines[i])
                    i += 1
                continue

            print(f" → ✓")
            stats['enriched'] += 1

            # Add existing properties
            output_lines.extend(props_lines)

            # Add new metadata properties (Tier 1)
            # Define order of properties for consistency
            property_order = [
                ('year', metadata.get('year')),
                ('runtime', metadata.get('runtime')),
                ('original_title', metadata.get('original_title')),
                ('original_language', metadata.get('original_language')),
                ('director', metadata.get('director')),
                ('actors', metadata.get('top_actors')),
                ('countries', metadata.get('countries')),
                ('production_companies', metadata.get('production_companies')),
                ('genres', metadata.get('genres')),
                ('imdb_id', metadata.get('imdb_id')),
                ('tmdb_rating', metadata.get('tmdb_rating')),
                ('vote_count', metadata.get('vote_count'))
            ]

            for key, value in property_order:
                prop_line = format_property(key, value)
                if prop_line:
                    output_lines.append(prop_line)

            # Mark as backfilled
            output_lines.append(":BACKFILLED: true\n")

            # Add :END:
            if i < len(lines):
                output_lines.append(lines[i])  # :END:
                i += 1

            # Copy rest of entry content
            while i < len(lines) and not lines[i].strip().startswith('*'):
                output_lines.append(lines[i])
                i += 1

            # Write incrementally
            remaining_lines = lines[i:]
            write_lines_to_file(org_path, output_lines + remaining_lines)

            # Rate limiting
            time.sleep(0.25)
            continue

        # Not a heading, copy as-is
        output_lines.append(line)
        i += 1

    # Final write
    if output_lines:
        write_lines_to_file(org_path, output_lines)

    return stats


def print_statistics(stats: Dict):
    """Print enrichment statistics."""
    print(f"\n{'='*60}")
    print(f"Enrichment Complete!")
    print(f"{'='*60}")
    print(f"Total entries:           {stats['total_entries']}")
    print(f"Enriched:                {stats['enriched']}")
    print(f"Skipped (no TMDB_ID):    {stats['skipped_no_tmdb_id']}")
    print(f"Skipped (needs review):  {stats['skipped_needs_review']}")
    print(f"Skipped (already done):  {stats['skipped_already_enriched']}")
    print(f"Errors:                  {stats['errors']}")
    print(f"\nNext steps:")
    print(f"1. Review entries marked with :NEEDS_REVIEW: and fix TMDB_ID")
    print(f"2. Re-run this script after fixing reviewed entries")
    print(f"3. Use org tags (genres) for searching")
    print(f"4. Run Phase 3 to generate plot summaries with AI")


def main():
    if len(sys.argv) < 2:
        print("Usage: python enrich_metadata.py <org_file> [tmdb_api_key] [--no-backup]")
        print("\nIf tmdb_api_key is not provided, will retrieve from 'pass tmdb/api-key'")
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
        import requests
    except ImportError:
        print("Error: requests library not found")
        print("Install with: pip install requests")
        sys.exit(1)

    print(f"Processing: {org_path}\n")

    stats = enrich_org_file(org_path, api_key, backup)
    print_statistics(stats)


if __name__ == '__main__':
    main()
