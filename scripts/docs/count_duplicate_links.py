#!/usr/bin/env python3
"""Report repeated internal Markdown destinations within each docs/ source."""

from __future__ import annotations

import argparse
import sys

# Running either CLI must not leave __pycache__ in the documentation tooling tree.
sys.dont_write_bytecode = True

from _markdown_links import (
    SYNTAX_HELP,
    markdown_sources,
    normalize_destination,
    parse_links,
    read_markdown,
    repository_root,
    tsv,
)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Report duplicate internal .md links within each source document.",
        allow_abbrev=False,
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""\
Scan every .md file under this script's repository docs/, including AGENTS.md
and untracked/ignored sources, independently of the current working directory.
Targets may be anywhere locally, including the root README.md, and need not
exist. Images, non-.md destinations and external URIs are excluded. Empty paths
(including #fragment) resolve to the source. /paths are repository-root-relative.

Paths normalize separators, dot segments and percent escapes, and compare
case-insensitively for Windows compatibility. Query strings are retained and
case-sensitive. Fragments are retained and case-sensitive by default. Groups
never merge different source documents. TSV includes all lines, including
repeated line numbers; multiline labels use the opening [ line. Sort: Count
descending, then Source and Target ascending. Extras = sum(count - 1).
Exit 0 even when duplicates exist; argument/IO errors exit 2. No files change.

""" + SYNTAX_HELP,
    )
    parser.add_argument(
        "--ignore-chapter-anchors",
        action="store_true",
        help="group destinations without #fragment differences; retain query strings",
    )
    arguments = parser.parse_args(argv)
    try:
        root = repository_root()
        sources = markdown_sources(root)
        duplicates: list[tuple[str, str, list[int]]] = []
        link_count = 0
        for source in sources:
            groups: dict[tuple, tuple[str, list[int]]] = {}
            for link in parse_links(read_markdown(source), source):
                if link.is_image:
                    continue
                target = normalize_destination(link.destination, source, root)
                if target is None or target.path.suffix.casefold() != ".md":
                    continue
                link_count += 1
                key = target.key(arguments.ignore_chapter_anchors)
                display = target.display(arguments.ignore_chapter_anchors)
                if key not in groups:
                    groups[key] = display, []
                groups[key][1].append(link.line)
            name = source.relative_to(root).as_posix()
            duplicates.extend((name, target, lines) for target, lines in groups.values() if len(lines) > 1)
        duplicates.sort(key=lambda row: (-len(row[2]), row[0], row[1]))
        print("# Duplicate internal Markdown links (per source)")
        print("# AnchorMode:", "ignore" if arguments.ignore_chapter_anchors else "preserve (case-sensitive)")
        print(f"# ScannedFiles: {len(sources)}")
        print(f"# DocumentLinks: {link_count}")
        print(f"# DuplicateGroups: {len(duplicates)}")
        print(f"# ExtraOccurrences: {sum(len(lines) - 1 for _, _, lines in duplicates)} (sum of count - 1)")
        print("Source\tTarget\tCount\tLines")
        for source, target, lines in duplicates:
            print(f"{tsv(source)}\t{tsv(target)}\t{len(lines)}\t{','.join(map(str, lines))}")
        return 0
    except (OSError, UnicodeError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
