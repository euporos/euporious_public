#!/usr/bin/env python3
"""
Script to deduplicate properties in tv_liste.org

When a property appears multiple times within a :PROPERTIES: block,
this script keeps the second occurrence and removes the first one.
"""

import sys
from pathlib import Path


def deduplicate_properties(input_file: str, output_file: str) -> dict:
    """
    Deduplicate properties in an org-mode file.

    Args:
        input_file: Path to input file
        output_file: Path to output file

    Returns:
        Dictionary with statistics about the deduplication
    """
    stats = {
        'total_entries': 0,
        'entries_with_duplicates': 0,
        'total_duplicates_removed': 0,
        'duplicates_by_property': {},
        'duplicate_details': []  # List of (entry_title, property_name, first_value, second_value)
    }

    with open(input_file, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    result_lines = []
    in_properties = False
    current_properties = {}  # property_name -> (line_index, line_content)
    duplicates_in_current_block = []
    current_entry_title = None

    i = 0
    while i < len(lines):
        line = lines[i]

        # Track entry titles (lines starting with *)
        if line.strip().startswith('* ') and not in_properties:
            current_entry_title = line.strip()[2:].strip()

        # Check if we're entering a properties block
        if line.strip() == ':PROPERTIES:':
            in_properties = True
            current_properties = {}
            duplicates_in_current_block = []
            result_lines.append(line)
            stats['total_entries'] += 1
            i += 1
            continue

        # Check if we're exiting a properties block
        if line.strip() == ':END:':
            in_properties = False
            if duplicates_in_current_block:
                stats['entries_with_duplicates'] += 1
            current_properties = {}
            duplicates_in_current_block = []
            result_lines.append(line)
            i += 1
            continue

        # Process property lines
        if in_properties and line.strip().startswith(':') and line.strip().endswith(':') == False:
            # Extract property name (e.g., ":YEAR:" from ":YEAR: 2021")
            property_line = line.strip()
            if ':' in property_line[1:]:  # Skip first colon
                prop_name = property_line.split(':', 2)[1]  # Get the part between first and second colon
                prop_value = property_line.split(':', 2)[2].strip() if len(property_line.split(':', 2)) > 2 else ""

                # Check if this property was already seen
                if prop_name in current_properties:
                    # This is a duplicate - skip the FIRST occurrence
                    first_occurrence_idx, first_line_content = current_properties[prop_name]
                    first_value = first_line_content.strip().split(':', 2)[2].strip() if len(first_line_content.strip().split(':', 2)) > 2 else ""

                    # Log the duplicate
                    stats['duplicate_details'].append({
                        'entry': current_entry_title or '(unknown)',
                        'property': prop_name,
                        'first_value': first_value,
                        'second_value': prop_value,
                        'line_number': i + 1
                    })

                    # Mark that we found a duplicate
                    duplicates_in_current_block.append(prop_name)
                    stats['total_duplicates_removed'] += 1
                    stats['duplicates_by_property'][prop_name] = stats['duplicates_by_property'].get(prop_name, 0) + 1

                    # Remove the first occurrence from result_lines
                    # We need to find it by searching backwards from current position
                    for j in range(len(result_lines) - 1, -1, -1):
                        if result_lines[j] == lines[first_occurrence_idx]:
                            del result_lines[j]
                            break

                    # Update the property tracker with the new (second) occurrence
                    current_properties[prop_name] = (i, line)
                    result_lines.append(line)
                else:
                    # First occurrence of this property
                    current_properties[prop_name] = (i, line)
                    result_lines.append(line)
            else:
                # Not a standard property line, just append
                result_lines.append(line)
        else:
            # Not in properties block or not a property line
            result_lines.append(line)

        i += 1

    # Write the result
    with open(output_file, 'w', encoding='utf-8') as f:
        f.writelines(result_lines)

    return stats


def main():
    input_file = 'resources/tv_liste.org'
    output_file = 'resources/tv_liste_deduplicated.org'

    if not Path(input_file).exists():
        print(f"Error: Input file '{input_file}' not found")
        sys.exit(1)

    print(f"Deduplicating properties in {input_file}...")
    print(f"Output will be written to {output_file}")
    print()

    stats = deduplicate_properties(input_file, output_file)

    print("=" * 60)
    print("Deduplication complete!")
    print("=" * 60)
    print(f"Total entries processed: {stats['total_entries']}")
    print(f"Entries with duplicate properties: {stats['entries_with_duplicates']}")
    print(f"Total duplicates removed: {stats['total_duplicates_removed']}")
    print()

    if stats['duplicates_by_property']:
        print("Summary - Duplicates by property:")
        for prop, count in sorted(stats['duplicates_by_property'].items()):
            print(f"  :{prop}: - {count} duplicate(s) removed")
        print()

    if stats['duplicate_details']:
        print("Detailed log of all duplicates found:")
        print("-" * 60)
        for detail in stats['duplicate_details']:
            print(f"Entry: {detail['entry']}")
            print(f"  Property: :{detail['property']}:")
            print(f"  First value:  {detail['first_value']}")
            print(f"  Second value: {detail['second_value']} (kept)")
            print(f"  Line: {detail['line_number']}")
            if detail['first_value'] != detail['second_value']:
                print(f"  WARNING: Values differ!")
            print()

    print(f"Output written to: {output_file}")
    print()
    print("Please review the output file before replacing the original.")


if __name__ == '__main__':
    main()
