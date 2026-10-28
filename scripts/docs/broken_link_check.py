#!/usr/bin/env python3
"""Check local documentation destinations and project .gitignore exclusions."""

from __future__ import annotations

import argparse
import stat
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

sys.dont_write_bytecode = True

from _markdown_links import (
    SYNTAX_HELP,
    LocalTarget,
    MarkdownLink,
    heading_anchors,
    markdown_sources,
    normalize_destination,
    parse_links,
    read_markdown,
    repository_root,
    tsv,
)


@dataclass(frozen=True)
class IgnoreRule:
    source: str
    line: int
    pattern: str


def project_ignored_targets(root: Path, paths: list[str]) -> dict[str, IgnoreRule]:
    """Use Git's final matching rule, including negation and tracked targets."""
    paths = sorted(set(paths))
    process = subprocess.run(
        ["git", "check-ignore", "--no-index", "--stdin", "-z", "--verbose", "--non-matching"],
        cwd=root,
        input=b"".join(path.encode("utf-8", errors="surrogateescape") + b"\0" for path in paths),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if process.returncode not in (0, 1):
        detail = process.stderr.decode("utf-8", errors="replace").strip()
        raise OSError(f"git check-ignore failed (exit {process.returncode}): {detail}")
    if process.stdout and not process.stdout.endswith(b"\0"):
        raise OSError("git check-ignore returned an incomplete NUL record")
    fields = process.stdout[:-1].split(b"\0") if process.stdout else []
    if len(fields) != len(paths) * 4:
        raise OSError("git check-ignore returned an unexpected number of four-field NUL records")
    expected = set(paths)
    seen: set[str] = set()
    ignored: dict[str, IgnoreRule] = {}
    for index in range(0, len(fields), 4):
        source, number, pattern, path = [field.decode("utf-8", errors="surrogateescape") for field in fields[index:index + 4]]
        if path not in expected or path in seen:
            raise OSError("git check-ignore returned an unexpected or duplicate target")
        seen.add(path)
        if not source and not number and not pattern:
            continue
        if not source or not number.isdecimal() or not pattern:
            raise OSError("git check-ignore returned a malformed rule record")
        rule_path = Path(source)
        if not rule_path.is_absolute():
            rule_path = root / rule_path
        rule_path = rule_path.resolve()
        if pattern.startswith("!") or rule_path.name != ".gitignore" or not rule_path.is_relative_to(root.resolve()):
            continue
        ignored[path] = IgnoreRule(rule_path.relative_to(root.resolve()).as_posix(), int(number), pattern)
    return ignored


def _inside_repository(target: LocalTarget, root: Path) -> bool:
    return target.path.is_relative_to(root) and target.path.resolve().is_relative_to(root.resolve())


def _git_path(target: LocalTarget) -> str:
    return target.relative + ("/" if target.directory_hint and target.relative != "." else "")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Offline check of local links and images in every repository docs/**/*.md file.",
        allow_abbrev=False,
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""\
The repository is located from __file__, not the current working directory.
Include AGENTS.md and untracked/ignored sources; targets may be outside docs/,
including the root README.md. Check files/directories of any extension, even
when tracked or already present. /paths are repository-root-relative; empty
paths and #fragments resolve to the source. Normalize dot segments, separators
and percent escapes after splitting path/query/fragment. Queries are preserved
in diagnostics but are not filesystem names. External URIs are not fetched:
report their skipped count and source/target, never claim they are valid.

For .md targets, check case-sensitive fragments against GitHub-style ATX and
Setext heading slugs (display text, code spans, Unicode and duplicate-slug
disambiguation). An empty # means the document top. Non-Markdown fragments are
not interpreted. Explicit HTML id/name anchors are not supported, and heading
generation is not a complete GFM renderer. Paths escaping the repository,
including symlinks to outside targets, are reported as outside repository;
they are not read or sent to Git.

Batch git check-ignore --no-index --stdin -z --verbose --non-matching from the
repository root. Only final positive rules from a repository .gitignore make
a target ignored; negations, personal global excludes and .git/info/exclude
do not. --no-index means tracked targets are not exempt. No documents change.
Exit 0: clean local check; 1: broken links; 2: argument, Git or IO/tool error.

""" + SYNTAX_HELP,
    )
    parser.parse_args(argv)
    try:
        root = repository_root()
        sources = markdown_sources(root)
        texts = {source: read_markdown(source) for source in sources}
        local: list[tuple[MarkdownLink, LocalTarget, bool]] = []
        external: list[MarkdownLink] = []
        problems: list[tuple[MarkdownLink, str, str]] = []
        for source, text in texts.items():
            for link in parse_links(text, source):
                try:
                    target = normalize_destination(link.destination, source, root)
                except (ValueError, UnicodeError) as error:
                    problems.append((link, "(invalid)", f"invalid local destination: {error}"))
                    continue
                if target is None:
                    external.append(link)
                else:
                    local.append((link, target, _inside_repository(target, root)))
        ignored = project_ignored_targets(root, [_git_path(target) for _, target, inside in local if inside])
        anchors: dict[Path, set[str]] = {}
        states: dict[Path, int | None] = {}
        for link, target, inside in local:
            reasons: list[str] = []
            if not inside:
                reasons.append("outside repository (not checked)")
            else:
                rule = ignored.get(_git_path(target))
                if rule:
                    reasons.append(f"ignored by {rule.source}:{rule.line}: {rule.pattern}")
                if target.path not in states:
                    try:
                        states[target.path] = target.path.stat().st_mode
                    except (FileNotFoundError, NotADirectoryError):
                        states[target.path] = None
                mode = states[target.path]
                if mode is None:
                    reasons.append("missing target")
                elif target.directory_hint and not stat.S_ISDIR(mode):
                    reasons.append("target is not a directory")
                elif target.path.suffix.casefold() == ".md" and target.fragment:
                    if not stat.S_ISREG(mode):
                        reasons.append("Markdown anchor target is not a file")
                    else:
                        if target.path not in anchors:
                            text = texts[target.path] if target.path in texts else read_markdown(target.path)
                            anchors[target.path] = heading_anchors(text)
                        if target.fragment not in anchors[target.path]:
                            reasons.append(f"missing anchor #{target.fragment}")
            if reasons:
                problems.append((link, target.display(), "; ".join(reasons)))
        problems.sort(key=lambda row: (row[0].source.as_posix(), row[0].line, row[1], row[0].destination))
        print("# Offline documentation link check")
        print(f"# ScannedFiles: {len(sources)}")
        print(f"# LocalLinks: {len(local) + sum(target == '(invalid)' for _, target, _ in problems)}")
        print(f"# SkippedExternalURIs: {len(external)} (not validated)")
        print(f"# BrokenLinks: {len(problems)}")
        print("Source:Line\tOriginal\tTarget\tReason")
        for link, target, reason in problems:
            location = f"{link.source.relative_to(root).as_posix()}:{link.line}"
            print(f"{tsv(location)}\t{tsv(link.destination)}\t{tsv(target)}\t{tsv(reason)}")
        if external:
            print("# Skipped external URIs (offline; not validated)")
            print("Source:Line\tOriginal")
            for link in external:
                location = f"{link.source.relative_to(root).as_posix()}:{link.line}"
                print(f"{tsv(location)}\t{tsv(link.destination)}")
        return 1 if problems else 0
    except (OSError, UnicodeError, ValueError, RuntimeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
