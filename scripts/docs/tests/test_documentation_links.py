"""Regression tests for the documentation Markdown link CLIs and scanner.

The expectations here come from the CLIs' documented contracts, not from the
current implementation's output: per-source duplicate grouping, anchor/query
handling, local target normalization, heading anchors, Git ignore semantics,
and process exit codes.  Git fixtures are disposable; the production scripts
are copied into each temporary repository so the copied scripts' __file__-based
repository discovery is exercised from unrelated working directories.
"""

from __future__ import annotations

import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from dataclasses import dataclass
from pathlib import Path

sys.dont_write_bytecode = True

REPO_ROOT = Path(__file__).resolve().parents[3]
DOCS_TOOLS = REPO_ROOT / "scripts" / "docs"
TEMP_BASE = Path(tempfile.gettempdir()) / "opencode"

sys.path.insert(0, str(DOCS_TOOLS))
from _markdown_links import heading_anchors, normalize_destination, parse_links  # noqa: E402

CLI_FILES = ("_markdown_links.py", "count_duplicate_links.py", "broken_link_check.py")


def _remove_tree(path: Path) -> None:
    def on_error(function, value, _error) -> None:
        try:
            os.chmod(value, stat.S_IWRITE)
        except OSError:
            pass
        try:
            function(value)
        except OSError:
            pass

    shutil.rmtree(path, onerror=on_error)


def _new_temp_root(prefix: str) -> Path:
    TEMP_BASE.mkdir(parents=True, exist_ok=True)
    return Path(tempfile.mkdtemp(prefix=prefix, dir=str(TEMP_BASE)))


def _copy_cli_tools(root: Path) -> None:
    destination = root / "scripts" / "docs"
    destination.mkdir(parents=True)
    for name in CLI_FILES:
        shutil.copyfile(DOCS_TOOLS / name, destination / name)


def _clean_environment(root: Path, **overrides: str) -> dict[str, str]:
    environment = os.environ.copy()
    environment.update(
        {
            "PYTHONDONTWRITEBYTECODE": "1",
            "PYTHONIOENCODING": "utf-8",
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": str(root / "empty-gitconfig"),
            # Keep a non-repository fixture from accidentally discovering a
            # repository in the host's temporary-directory ancestry.
            "GIT_CEILING_DIRECTORIES": str(root),
        }
    )
    environment.update(overrides)
    return environment


def _header_number(headers: dict[str, str], key: str) -> int:
    return int(headers[key].split()[0])


@dataclass
class DuplicateReport:
    headers: dict[str, str]
    rows: list[tuple[str, str, int, tuple[int, ...]]]

    @property
    def anchor_mode(self) -> str:
        return self.headers["AnchorMode"]

    @property
    def scanned_files(self) -> int:
        return _header_number(self.headers, "ScannedFiles")

    @property
    def document_links(self) -> int:
        return _header_number(self.headers, "DocumentLinks")

    @property
    def duplicate_groups(self) -> int:
        return _header_number(self.headers, "DuplicateGroups")

    @property
    def extra_occurrences(self) -> int:
        return _header_number(self.headers, "ExtraOccurrences")


@dataclass
class BrokenReport:
    headers: dict[str, str]
    problems: list[tuple[str, str, str, str]]
    external: list[tuple[str, str]]

    @property
    def scanned_files(self) -> int:
        return _header_number(self.headers, "ScannedFiles")

    @property
    def local_links(self) -> int:
        return _header_number(self.headers, "LocalLinks")

    @property
    def skipped_external(self) -> int:
        return _header_number(self.headers, "SkippedExternalURIs")

    @property
    def broken_links(self) -> int:
        return _header_number(self.headers, "BrokenLinks")


def _parse_duplicate_report(test: unittest.TestCase, output: str) -> DuplicateReport:
    headers: dict[str, str] = {}
    rows: list[tuple[str, str, int, tuple[int, ...]]] = []
    in_table = False
    for line in output.splitlines():
        if line == "Source\tTarget\tCount\tLines":
            in_table = True
            continue
        if not in_table:
            if line.startswith("# ") and ": " in line:
                key, value = line[2:].split(": ", 1)
                headers[key] = value
            continue
        if not line:
            continue
        fields = line.split("\t")
        test.assertEqual(len(fields), 4, line)
        source, target, count, lines = fields
        rows.append((source, target, int(count), tuple(int(value) for value in lines.split(","))))
    for key in ("AnchorMode", "ScannedFiles", "DocumentLinks", "DuplicateGroups", "ExtraOccurrences"):
        test.assertIn(key, headers)
    return DuplicateReport(headers, rows)


def _parse_broken_report(test: unittest.TestCase, output: str) -> BrokenReport:
    headers: dict[str, str] = {}
    problems: list[tuple[str, str, str, str]] = []
    external: list[tuple[str, str]] = []
    section: str | None = None
    for line in output.splitlines():
        if line == "Source:Line\tOriginal\tTarget\tReason":
            section = "problems"
            continue
        if line == "# Skipped external URIs (offline; not validated)":
            section = "external"
            continue
        if line == "Source:Line\tOriginal":
            continue
        if section is None:
            if line.startswith("# ") and ": " in line:
                key, value = line[2:].split(": ", 1)
                headers[key] = value
            continue
        if not line:
            continue
        fields = line.split("\t")
        if section == "problems":
            test.assertEqual(len(fields), 4, line)
            problems.append((fields[0], fields[1], fields[2], fields[3]))
        else:
            test.assertEqual(len(fields), 2, line)
            external.append((fields[0], fields[1]))
    for key in ("ScannedFiles", "LocalLinks", "SkippedExternalURIs", "BrokenLinks"):
        test.assertIn(key, headers)
    return BrokenReport(headers, problems, external)


class _CliFixture(unittest.TestCase):
    def setUp(self) -> None:
        self.root = _new_temp_root("pingforit-doc-cli-")
        self.addCleanup(_remove_tree, self.root)
        _copy_cli_tools(self.root)
        (self.root / "docs").mkdir()
        (self.root / "empty-gitconfig").write_text("", encoding="utf-8")

    def write_doc(self, relative: str, text: str) -> Path:
        path = self.root / "docs" / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def write_target(self, relative: str, text: str = "# Target\n") -> Path:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def git_environment(self, **overrides: str) -> dict[str, str]:
        return _clean_environment(self.root, **overrides)

    def init_git(self, environment: dict[str, str] | None = None) -> None:
        process = subprocess.run(
            ["git", "init", "-q"],
            cwd=self.root,
            env=environment or self.git_environment(),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        self.assertEqual(process.returncode, 0, process.stderr)

    def run_git(
        self,
        arguments: list[str],
        *,
        environment: dict[str, str] | None = None,
        check: bool = True,
    ) -> subprocess.CompletedProcess[str]:
        process = subprocess.run(
            ["git", *arguments],
            cwd=self.root,
            env=environment or self.git_environment(),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if check and process.returncode != 0:
            self.fail(f"git {' '.join(arguments)} failed: {process.stderr}")
        return process

    def run_cli(
        self,
        script: str,
        *arguments: str,
        cwd: Path | None = None,
        environment: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        child_environment = self.git_environment()
        if environment:
            child_environment.update(environment)
        return subprocess.run(
            [sys.executable, "-B", str(self.root / "scripts" / "docs" / script), *arguments],
            cwd=cwd or self.root,
            env=child_environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
        )

    def duplicate_report(self, *arguments: str, cwd: Path | None = None) -> DuplicateReport:
        process = self.run_cli("count_duplicate_links.py", *arguments, cwd=cwd)
        self.assertEqual(process.returncode, 0, process.stderr)
        return _parse_duplicate_report(self, process.stdout)

    def broken_report(
        self,
        *arguments: str,
        cwd: Path | None = None,
        environment: dict[str, str] | None = None,
        expected_exit: int | None = None,
    ) -> BrokenReport:
        process = self.run_cli(
            "broken_link_check.py", *arguments, cwd=cwd, environment=environment
        )
        if expected_exit is None:
            self.assertEqual(process.returncode, 0, process.stderr + process.stdout)
        else:
            self.assertEqual(process.returncode, expected_exit, process.stderr + process.stdout)
        return _parse_broken_report(self, process.stdout)


def _duplicate_rows_by_target(report: DuplicateReport) -> dict[str, tuple[int, tuple[int, ...]]]:
    return {target: (count, lines) for _, target, count, lines in report.rows}


def _problems_by_original(report: BrokenReport) -> dict[str, tuple[str, str, str]]:
    return {original: (location, target, reason) for location, original, target, reason in report.problems}


class MarkdownParsingTests(unittest.TestCase):
    source = Path("docs/page.md")

    def test_inline_multiline_code_span_labels_and_line_numbers(self) -> None:
        text = (
            "[first](one.md) and [`code`](two.md)\n"
            "\n"
            "[multi\n"
            "line](three.md)\n"
            "[paren](dir/a(b).md) [angle](<dir/space name.md>)\n"
        )
        links = parse_links(text, self.source)
        self.assertEqual(
            [(link.line, link.destination, link.is_image) for link in links],
            [
                (1, "one.md", False),
                (1, "two.md", False),
                (3, "three.md", False),
                (5, "dir/a(b).md", False),
                (5, "dir/space name.md", False),
            ],
        )

    def test_reference_uses_are_counted_but_definitions_are_not(self) -> None:
        text = (
            "[One][ref] [ref][] [ref]\n"
            "[again][REF]\n"
            "\n"
            "[ref]: target.md \"title\"\n"
            "[unused]: other.md\n"
        )
        links = parse_links(text, self.source)
        self.assertEqual(
            [(link.line, link.destination) for link in links],
            [(1, "target.md"), (1, "target.md"), (1, "target.md"), (2, "target.md")],
        )

    def test_images_are_distinguished_and_linked_images_keep_both_targets(self) -> None:
        text = (
            "![plain](plain.png)\n"
            "[![alt](thumb.png)](page.md)\n"
        )
        links = parse_links(text, self.source)
        self.assertEqual(
            sorted((link.line, link.destination, link.is_image) for link in links),
            sorted(
                [
                    (1, "plain.png", True),
                    (2, "page.md", False),
                    (2, "thumb.png", True),
                ]
            ),
        )

    def test_code_spans_fences_comments_and_indented_code_are_excluded(self) -> None:
        text = (
            "`[span](ghost-span.md)`\n"
            "\n"
            "<!-- [comment](ghost-comment.md)\n"
            "still hidden -->\n"
            "\n"
            "```\n"
            "[fenced](ghost-fence.md)\n"
            "```\n"
            "\n"
            "    [indented](ghost-indented.md)\n"
            "\n"
            "[real](real.md)\n"
        )
        self.assertEqual(
            [(link.line, link.destination, link.is_image) for link in parse_links(text, self.source)],
            [(12, "real.md", False)],
        )

    def test_indented_code_immediately_after_a_fence_is_excluded(self) -> None:
        # CommonMark starts an indented code block after a closed fenced block
        # without requiring an intervening blank line.  The public scanner help
        # says ordinary indented code is excluded, so this must not become a
        # documentation link merely because the fence ended on the prior line.
        text = (
            "```text\n"
            "[fenced](ghost-fence.md)\n"
            "```\n"
            "    [indented](ghost-indented.md)\n"
            "\n"
            "[real](real.md)\n"
        )
        self.assertEqual(
            [(link.line, link.destination) for link in parse_links(text, self.source)],
            [(6, "real.md")],
        )

    def test_nested_list_body_links_are_counted_and_list_code_is_excluded(self) -> None:
        text = (
            "- item [one](one.md)\n"
            "  continuation [two](two.md)\n"
            "\n"
            "      [code](ghost.md)\n"
            "\n"
            "  [three](three.md)\n"
        )
        self.assertEqual(
            [(link.line, link.destination) for link in parse_links(text, self.source)],
            [(1, "one.md"), (2, "two.md"), (6, "three.md")],
        )

    def test_backticks_in_separate_paragraphs_do_not_form_code_span(self) -> None:
        # A blank line ends a paragraph, so isolated backticks in different
        # paragraphs cannot pair.  A code span may still cross a non-blank
        # line ending, as the control case verifies.
        text = (
            "Intro `\n"
            "\n"
            "[a](missing.md) [b](missing.md)\n"
            "\n"
            "Outro `\n"
        )
        self.assertEqual(
            [(link.line, link.destination) for link in parse_links(text, self.source)],
            [(3, "missing.md"), (3, "missing.md")],
        )

        control = (
            "Before `inline code\n"
            "with [fake](ghost.md)` tail\n"
            "\n"
            "[real](real.md)\n"
        )
        self.assertEqual(
            [(link.line, link.destination) for link in parse_links(control, self.source)],
            [(4, "real.md")],
        )

    def test_indented_code_directly_after_atx_heading_is_excluded(self) -> None:
        # CommonMark example 115: an indented code block may begin directly
        # after an ATX heading without an intervening blank line.
        text = "# Title\n    [fake](missing.md)\n"
        self.assertEqual(parse_links(text, self.source), [])

    def test_only_ordered_lists_starting_at_one_interrupt_paragraph_code_span(self) -> None:
        # CommonMark example 304: a non-1 ordered marker cannot interrupt a
        # paragraph, so these three lines remain one valid code span.
        for number in (2, 14):
            with self.subTest(number=number):
                text = f"Text `code\n{number}. [fake](missing.md)\nend`\n"
                self.assertEqual(parse_links(text, self.source), [])

        # Starting at 1 does interrupt the paragraph: the backticks now belong
        # to separate blocks and cannot hide the real link in the list item.
        control = "Text `code\n1. [real](missing.md)\nend`\n"
        self.assertEqual(
            [(link.line, link.destination) for link in parse_links(control, self.source)],
            [(2, "missing.md")],
        )


class TargetNormalizationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.root = _new_temp_root("pingforit-doc-normalize-")
        self.addCleanup(_remove_tree, self.root)
        self.source = self.root / "docs" / "sub" / "page.md"

    def test_percent_encoded_path_query_and_fragment_components(self) -> None:
        target = normalize_destination("a%23b.md?view=1%232#part%23two", self.source, self.root)
        self.assertIsNotNone(target)
        assert target is not None
        self.assertEqual(target.relative, "docs/sub/a#b.md")
        self.assertEqual(target.query, "view=1#2")
        self.assertEqual(target.fragment, "part#two")
        self.assertEqual(target.display(), "docs/sub/a#b.md?view=1#2#part#two")
        self.assertEqual(target.display(ignore_fragment=True), "docs/sub/a#b.md?view=1#2")
        self.assertEqual(target.key(False), ("docs/sub/a#b.md", "view=1#2", "part#two"))
        self.assertEqual(target.key(True), ("docs/sub/a#b.md", "view=1#2", None))
        self.assertFalse(target.directory_hint)

    def test_root_relative_parent_dot_segments_and_directory_hint(self) -> None:
        cases = {
            "/README.md": ("README.md", None, None, False),
            "../sibling.md": ("docs/sibling.md", None, None, False),
            "./folder/": ("docs/sub/folder", None, None, True),
            "folder/.": ("docs/sub/folder", None, None, True),
            "folder/..": ("docs/sub", None, None, True),
            "..\\other.md": ("docs/other.md", None, None, False),
            "#section": ("docs/sub/page.md", None, "section", False),
            "?download=1": ("docs/sub/page.md", "download=1", None, False),
        }
        for destination, expected in cases.items():
            with self.subTest(destination=destination):
                target = normalize_destination(destination, self.source, self.root)
                self.assertIsNotNone(target)
                assert target is not None
                self.assertEqual(
                    (target.relative, target.query, target.fragment, target.directory_hint),
                    expected,
                )

    def test_external_uris_are_skipped_and_unsafe_local_paths_are_rejected(self) -> None:
        for destination in ("https://example.invalid/x", "mailto:docs@example.invalid", "//host/path"):
            with self.subTest(external=destination):
                self.assertIsNone(normalize_destination(destination, self.source, self.root))
        for destination, error in (
            ("bad%00name.md", ValueError),
            ("bad%FFname.md", UnicodeError),
            ("%5C%5Cserver%5Cshare", ValueError),
            ("%43%3A/x", ValueError),
        ):
            with self.subTest(unsafe=destination):
                with self.assertRaises(error):
                    normalize_destination(destination, self.source, self.root)

    def test_key_casefolds_paths_but_not_queries_or_fragments(self) -> None:
        upper_path = normalize_destination("Doc.MD?Q=1#Frag", self.source, self.root)
        lower_path = normalize_destination("doc.md?Q=1#Frag", self.source, self.root)
        lower_query = normalize_destination("doc.md?q=1#Frag", self.source, self.root)
        lower_fragment = normalize_destination("doc.md?Q=1#frag", self.source, self.root)
        assert upper_path is not None
        assert lower_path is not None
        assert lower_query is not None
        assert lower_fragment is not None
        self.assertEqual(upper_path.key(False), lower_path.key(False))
        self.assertNotEqual(upper_path.key(False), lower_query.key(False))
        self.assertNotEqual(upper_path.key(False), lower_fragment.key(False))
        self.assertEqual(upper_path.key(True), lower_fragment.key(True))


class HeadingAnchorTests(unittest.TestCase):
    def test_atx_setext_display_code_and_unicode_headings(self) -> None:
        text = (
            "# Simple ATX\n"
            "\n"
            "Setext Heading\n"
            "--------------\n"
            "\n"
            "## See [`code`](target.md) and **bold**\n"
            "\n"
            "### A/B (C)\n"
            "\n"
            "#### Héading Ünïcode\n"
        )
        self.assertEqual(
            heading_anchors(text),
            {
                "simple-atx",
                "setext-heading",
                "see-code-and-bold",
                "ab-c",
                "héading-ünïcode",
            },
        )

    def test_duplicate_slugs_are_disambiguated_and_fenced_headings_are_ignored(self) -> None:
        text = (
            "# Foo\n"
            "\n"
            "# Foo\n"
            "\n"
            "# Foo-1\n"
            "\n"
            "# Foo\n"
            "\n"
            "```text\n"
            "# Not a heading\n"
            "```\n"
        )
        self.assertEqual(heading_anchors(text), {"foo", "foo-1", "foo-1-1", "foo-2"})

    def test_code_span_edge_space_normalization_in_heading_anchor(self) -> None:
        # CommonMark removes one leading and one trailing space from a code
        # span whose content begins and ends in a space, unless the content is
        # entirely spaces.  Rendering "# foo ` bar ` baz" therefore yields
        # "foo bar baz" and the anchor "foo-bar-baz".
        self.assertEqual(heading_anchors("# foo ` bar ` baz\n"), {"foo-bar-baz"})

        # Two spaces on each edge lose only one, leaving two spaces between
        # the words and two hyphens in the slug; a blanket .strip() would
        # incorrectly produce "a-b-c".
        self.assertEqual(heading_anchors("# A `  B  ` C\n"), {"a--b--c"})

    def test_all_space_code_span_preserves_spaces_in_heading_anchor(self) -> None:
        # All-space code-span content is not trimmed.  The rendered heading
        # has five spaces between A and B, so its slug has five hyphens.
        self.assertEqual(heading_anchors("# A `   ` B\n"), {"a-----b"})


class DuplicateLinkCliTests(_CliFixture):
    def test_default_preserves_anchors_and_ignore_merges_them(self) -> None:
        self.write_target("docs/target.md")
        self.write_doc(
            "page.md",
            "[a](target.md#one)\n"
            "[b](target.md#One)\n"
            "[c](target.md#one)\n"
            "[d](target.md#One)\n",
        )
        preserved = self.duplicate_report()
        ignored = self.duplicate_report("--ignore-chapter-anchors")

        self.assertEqual(preserved.anchor_mode, "preserve (case-sensitive)")
        self.assertEqual(preserved.document_links, 4)
        self.assertEqual(preserved.duplicate_groups, 2)
        self.assertEqual(
            _duplicate_rows_by_target(preserved),
            {
                "docs/target.md#One": (2, (2, 4)),
                "docs/target.md#one": (2, (1, 3)),
            },
        )

        self.assertEqual(ignored.anchor_mode, "ignore")
        self.assertEqual(ignored.document_links, 4)
        self.assertEqual(ignored.duplicate_groups, 1)
        self.assertEqual(
            ignored.rows,
            [("docs/page.md", "docs/target.md", 4, (1, 2, 3, 4))],
        )
        self.assertGreaterEqual(ignored.extra_occurrences, preserved.extra_occurrences)

    def test_sources_are_independent_and_groups_sort_by_count_source_target(self) -> None:
        self.write_target("docs/z.md")
        self.write_doc(
            "a.md",
            "[z1](z.md)\n"
            "[z2](z.md) [z3](z.md)\n"
            "[a1](a.md)\n"
            "[a2](a.md)\n",
        )
        self.write_doc(
            "b.md",
            "[z1](z.md)\n"
            "[z2](z.md)\n",
        )
        report = self.duplicate_report()

        self.assertEqual(report.scanned_files, 3)
        self.assertEqual(report.document_links, 7)
        self.assertEqual(report.duplicate_groups, 3)
        self.assertEqual(report.extra_occurrences, 4)
        self.assertEqual(
            report.rows,
            [
                ("docs/a.md", "docs/z.md", 3, (1, 2, 2)),
                ("docs/a.md", "docs/a.md", 2, (3, 4)),
                ("docs/b.md", "docs/z.md", 2, (1, 2)),
            ],
        )

    def test_query_strings_are_retained_and_case_sensitive(self) -> None:
        self.write_target("docs/target.md")
        self.write_doc(
            "page.md",
            "[a](target.md?x=1#one)\n"
            "[b](target.md?x=1#two)\n"
            "[c](target.md?x=1#one)\n"
            "[d](target.md?x=2#one)\n"
            "[e](target.md?x=2#one)\n"
            "[f](target.md?X=1#one)\n"
            "[g](target.md?X=1#one)\n",
        )
        preserved = self.duplicate_report()
        ignored = self.duplicate_report("--ignore-chapter-anchors")

        self.assertEqual(
            _duplicate_rows_by_target(preserved),
            {
                "docs/target.md?X=1#one": (2, (6, 7)),
                "docs/target.md?x=1#one": (2, (1, 3)),
                "docs/target.md?x=2#one": (2, (4, 5)),
            },
        )
        self.assertEqual(
            _duplicate_rows_by_target(ignored),
            {
                "docs/target.md?X=1": (2, (6, 7)),
                "docs/target.md?x=1": (3, (1, 2, 3)),
                "docs/target.md?x=2": (2, (4, 5)),
            },
        )

    def test_images_non_markdown_and_external_targets_are_not_counted(self) -> None:
        self.write_target("docs/target.md")
        self.write_target("docs/target.txt")
        self.write_doc(
            "page.md",
            "![image-md](target.md)\n"
            "![image-png](pic.png)\n"
            "[doc](target.md)\n"
            "[doc](target.md)\n"
            "[text](target.txt)\n"
            "[external](https://example.invalid/x)\n"
            "[mail](mailto:docs@example.invalid)\n",
        )
        report = self.duplicate_report()

        self.assertEqual(report.document_links, 2)
        self.assertEqual(report.duplicate_groups, 1)
        self.assertEqual(
            report.rows,
            [("docs/page.md", "docs/target.md", 2, (3, 4))],
        )

    def test_cli_is_independent_of_working_directory_and_duplicates_exit_zero(self) -> None:
        self.write_target("docs/target.md")
        self.write_doc(
            "page.md",
            "[a](target.md)\n"
            "[b](target.md)\n",
        )
        elsewhere = self.root / "elsewhere"
        elsewhere.mkdir()
        outputs = []
        for cwd in (self.root, self.root / "docs", elsewhere):
            process = self.run_cli("count_duplicate_links.py", cwd=cwd)
            self.assertEqual(process.returncode, 0, process.stderr)
            outputs.append(process.stdout)
        self.assertEqual(len(set(outputs)), 1)


class BrokenLinkCliTests(_CliFixture):
    def test_clean_check_accepts_root_readme_directory_query_and_external_targets(self) -> None:
        self.write_target("README.md", "# Root\n")
        self.write_target("docs/ok.md", "# Section\n")
        self.write_target("docs/image.png", "")
        self.write_target("docs/folder/.keep", "")
        self.write_target("docs/data.txt", "data\n")
        self.write_doc(
            "page.md",
            "[home](/README.md)\n"
            "[ok](ok.md)\n"
            "[image](image.png)\n"
            "[dir](folder/)\n"
            "[query](ok.md?download=1)\n"
            "[anchor](ok.md#section)\n"
            "[empty](#)\n"
            "[self](#section)\n"
            "[text](data.txt#not-a-heading)\n"
            "[external](https://example.invalid/resource)\n"
            "[mail](mailto:docs@example.invalid)\n"
            "\n"
            "# Section\n",
        )
        self.init_git()
        report = self.broken_report()

        self.assertEqual(report.scanned_files, 2)
        self.assertEqual(report.local_links, 9)
        self.assertEqual(report.skipped_external, 2)
        self.assertEqual(report.broken_links, 0)
        self.assertEqual(report.problems, [])
        self.assertEqual(
            report.external,
            [
                ("docs/page.md:10", "https://example.invalid/resource"),
                ("docs/page.md:11", "mailto:docs@example.invalid"),
            ],
        )

    def test_missing_file_and_invalid_anchor_are_reported_separately(self) -> None:
        self.write_target("docs/target.md", "# Good Heading\n")
        self.write_doc(
            "page.md",
            "# Page\n"
            "\n"
            "[good](target.md#good-heading)\n"
            "[missing-file](missing.md)\n"
            "[missing-anchor](target.md#absent)\n"
            "[missing-file-anchor](missing.md#absent)\n"
            "[top](#)\n"
            "[self-ok](#page)\n"
            "[self-bad](#nope)\n",
        )
        self.init_git()
        report = self.broken_report(expected_exit=1)

        self.assertEqual(report.broken_links, 4)
        self.assertEqual(
            _problems_by_original(report),
            {
                "missing.md": (
                    "docs/page.md:4",
                    "docs/missing.md",
                    "missing target",
                ),
                "target.md#absent": (
                    "docs/page.md:5",
                    "docs/target.md#absent",
                    "missing anchor #absent",
                ),
                "missing.md#absent": (
                    "docs/page.md:6",
                    "docs/missing.md#absent",
                    "missing target",
                ),
                "#nope": (
                    "docs/page.md:9",
                    "docs/page.md#nope",
                    "missing anchor #nope",
                ),
            },
        )

    def test_atx_setext_code_unicode_punctuation_and_collision_anchors(self) -> None:
        self.write_target(
            "docs/target.md",
            "# Hello World\n"
            "\n"
            "Setext Heading\n"
            "--------------\n"
            "\n"
            "## Use `code` and [link](target.md)\n"
            "\n"
            "### A/B (C)\n"
            "\n"
            "#### Héading Ünïcode\n"
            "\n"
            "#### Dup\n"
            "\n"
            "#### Dup\n",
        )
        self.write_doc(
            "page.md",
            "[atx](target.md#hello-world)\n"
            "[setext](target.md#setext-heading)\n"
            "[code](target.md#use-code-and-link)\n"
            "[slash-paren](target.md#ab-c)\n"
            "[unicode](target.md#héading-ünïcode)\n"
            "[dup](target.md#dup)\n"
            "[dup-collision](target.md#dup-1)\n"
            "[wrong-case](target.md#Hello-World)\n",
        )
        self.init_git()
        report = self.broken_report(expected_exit=1)

        self.assertEqual(report.broken_links, 1)
        self.assertEqual(
            _problems_by_original(report),
            {
                "target.md#Hello-World": (
                    "docs/page.md:8",
                    "docs/target.md#Hello-World",
                    "missing anchor #Hello-World",
                ),
            },
        )

    def test_percent_encoded_hash_parent_root_and_parenthesized_paths(self) -> None:
        self.write_target("README.md", "# Root\n")
        self.write_target("docs/a#b.md", "# Hash\n")
        self.write_target("docs/file(1).md", "# Paren\n")
        self.write_target("docs/shared/target.md", "# Shared\n")
        self.write_target("docs/space name.md", "# Space\n")
        self.write_doc(
            "sub/page.md",
            "[encoded-hash](../a%23b.md)\n"
            "[encoded-slash](..%2Fshared%2Ftarget.md)\n"
            "[paren](../file(1).md)\n"
            "[parent](../shared/target.md)\n"
            "[root](/README.md)\n"
            "[space](<../space name.md>)\n"
            "[query](../a%23b.md?download=1)\n",
        )
        self.init_git()
        report = self.broken_report()

        self.assertEqual(report.broken_links, 0)
        self.assertEqual(report.problems, [])

    def test_images_are_checked_but_code_text_is_not(self) -> None:
        self.write_target("docs/real.md", "# Real\n")
        self.write_doc(
            "page.md",
            "![missing-image](missing.png)\n"
            "`[code](ghost-code.md)`\n"
            "<!-- [comment](ghost-comment.md) -->\n"
            "```text\n"
            "[fenced](ghost-fence.md)\n"
            "```\n"
            "\n"
            "    [indented](ghost-indented.md)\n"
            "\n"
            "[real](real.md)\n",
        )
        self.init_git()
        report = self.broken_report(expected_exit=1)

        self.assertEqual(report.local_links, 2)
        self.assertEqual(report.broken_links, 1)
        self.assertEqual(
            _problems_by_original(report),
            {
                "missing.png": (
                    "docs/page.md:1",
                    "docs/missing.png",
                    "missing target",
                ),
            },
        )

    def test_outside_repository_and_invalid_destinations_are_broken_not_tool_errors(self) -> None:
        self.write_doc(
            "page.md",
            "[outside](../../outside.md)\n"
            "[invalid-nul](bad%00name.md)\n"
            "[invalid-utf8](bad%FFname.md)\n",
        )
        self.init_git()
        report = self.broken_report(expected_exit=1)

        self.assertEqual(report.broken_links, 3)
        problems = _problems_by_original(report)
        self.assertEqual(
            problems["../../outside.md"],
            (
                "docs/page.md:1",
                "../outside.md",
                "outside repository (not checked)",
            ),
        )
        for original in ("bad%00name.md", "bad%FFname.md"):
            location, target, reason = problems[original]
            self.assertEqual(location, "docs/page.md:" + ("2" if "00" in original else "3"))
            self.assertEqual(target, "(invalid)")
            self.assertTrue(reason.startswith("invalid local destination:"), reason)

    def test_broken_cli_is_independent_of_working_directory(self) -> None:
        self.write_doc("page.md", "[missing](missing.md)\n")
        self.init_git()
        outputs = []
        for cwd in (self.root, self.root / "docs"):
            process = self.run_cli("broken_link_check.py", cwd=cwd)
            self.assertEqual(process.returncode, 1, process.stderr)
            outputs.append(process.stdout)
        self.assertEqual(len(set(outputs)), 1)


class CombinedRegressionCliTests(_CliFixture):
    def test_code_span_and_indentation_regressions_report_only_real_links(self) -> None:
        self.write_target("docs/ok.md", "# Ok\n")
        self.write_doc(
            "page.md",
            "# foo ` bar ` baz\n"
            "\n"
            "Intro `\n"
            "\n"
            "[a](ok.md) [b](ok.md)\n"
            "\n"
            "Outro `\n"
            "\n"
            "[go](#foo-bar-baz)\n"
            "\n"
            "# Title\n"
            "    [fake](missing.md)\n",
        )
        self.init_git()

        duplicates = self.duplicate_report()
        self.assertEqual(duplicates.scanned_files, 2)
        self.assertEqual(duplicates.document_links, 3)
        self.assertEqual(duplicates.duplicate_groups, 1)
        self.assertEqual(
            duplicates.rows,
            [("docs/page.md", "docs/ok.md", 2, (5, 5))],
        )

        broken = self.broken_report(expected_exit=0)
        self.assertEqual(broken.broken_links, 0)
        self.assertEqual(broken.problems, [])


class GitIgnoreTests(_CliFixture):
    def test_project_gitignore_reports_existing_file_directory_and_parent_rules(self) -> None:
        self.write_target("ignored-file.md", "# Ignored\n")
        self.write_target("ignored-dir/inside.md", "# Ignored\n")
        self.write_target("parent/child.md", "# Ignored\n")
        self.write_target("clean.md", "# Clean\n")
        (self.root / ".gitignore").write_text(
            "ignored-file.md\n"
            "ignored-dir/\n"
            "parent/\n",
            encoding="utf-8",
        )
        self.write_doc(
            "page.md",
            "[file](../ignored-file.md)\n"
            "[dir](../ignored-dir/)\n"
            "[parent](../parent/child.md)\n"
            "[clean](../clean.md)\n",
        )
        self.init_git()
        report = self.broken_report(expected_exit=1)

        self.assertEqual(report.broken_links, 3)
        self.assertEqual(
            _problems_by_original(report),
            {
                "../ignored-file.md": (
                    "docs/page.md:1",
                    "ignored-file.md",
                    "ignored by .gitignore:1: ignored-file.md",
                ),
                "../ignored-dir/": (
                    "docs/page.md:2",
                    "ignored-dir",
                    "ignored by .gitignore:2: ignored-dir/",
                ),
                "../parent/child.md": (
                    "docs/page.md:3",
                    "parent/child.md",
                    "ignored by .gitignore:3: parent/",
                ),
            },
        )

    def test_missing_ignored_target_reports_both_ignore_and_missing_reasons(self) -> None:
        (self.root / ".gitignore").write_text("missing-ignored.md\n", encoding="utf-8")
        self.write_doc("page.md", "[missing](../missing-ignored.md)\n")
        self.init_git()
        report = self.broken_report(expected_exit=1)

        self.assertEqual(report.broken_links, 1)
        _location, target, reason = _problems_by_original(report)["../missing-ignored.md"]
        self.assertEqual(target, "missing-ignored.md")
        self.assertIn("ignored by .gitignore:1: missing-ignored.md", reason)
        self.assertIn("missing target", reason)

    def test_negated_gitignore_rule_keeps_target_valid(self) -> None:
        self.write_target("generated/other.md", "# Other\n")
        self.write_target("generated/keep.md", "# Keep\n")
        (self.root / ".gitignore").write_text(
            "generated/*.md\n"
            "!generated/keep.md\n",
            encoding="utf-8",
        )
        self.write_doc(
            "page.md",
            "[other](../generated/other.md)\n"
            "[keep](../generated/keep.md)\n",
        )
        self.init_git()
        report = self.broken_report(expected_exit=1)

        self.assertEqual(report.broken_links, 1)
        self.assertEqual(
            _problems_by_original(report),
            {
                "../generated/other.md": (
                    "docs/page.md:1",
                    "generated/other.md",
                    "ignored by .gitignore:1: generated/*.md",
                ),
            },
        )

    def test_tracked_target_is_not_exempt_from_project_gitignore(self) -> None:
        self.write_target("tracked.md", "# Tracked\n")
        (self.root / ".gitignore").write_text("tracked.md\n", encoding="utf-8")
        self.write_doc("page.md", "[tracked](../tracked.md)\n")
        self.init_git()
        self.run_git(["add", "-f", "tracked.md"])
        self.assertEqual(self.run_git(["ls-files", "--error-unmatch", "tracked.md"]).returncode, 0)
        report = self.broken_report(expected_exit=1)

        self.assertEqual(
            _problems_by_original(report)["../tracked.md"],
            (
                "docs/page.md:1",
                "tracked.md",
                "ignored by .gitignore:1: tracked.md",
            ),
        )

    def test_personal_info_and_global_excludes_are_not_project_gitignore(self) -> None:
        self.write_target("info-secret.md", "# Info\n")
        self.write_target("global-secret.md", "# Global\n")
        self.write_target("clean.md", "# Clean\n")
        self.write_doc(
            "page.md",
            "[info](../info-secret.md)\n"
            "[global](../global-secret.md)\n"
            "[clean](../clean.md)\n",
        )
        self.init_git()
        (self.root / ".git" / "info" / "exclude").write_text(
            "info-secret.md\n",
            encoding="utf-8",
        )
        global_ignore = self.root / "global-excludes"
        global_ignore.write_text("global-secret.md\n", encoding="utf-8")
        global_config = self.root / "global.gitconfig"
        global_config.write_text(
            "[core]\n"
            f"\texcludesFile = {global_ignore.as_posix()}\n",
            encoding="utf-8",
        )
        environment = self.git_environment(GIT_CONFIG_GLOBAL=str(global_config))

        direct = self.run_git(
            [
                "check-ignore",
                "--no-index",
                "--verbose",
                "--non-matching",
                "info-secret.md",
                "global-secret.md",
                "clean.md",
            ],
            environment=environment,
        )
        self.assertIn(".git/info/exclude", direct.stdout)
        self.assertIn("global-excludes", direct.stdout)
        report = self.broken_report(expected_exit=0, environment=environment)
        self.assertEqual(report.broken_links, 0)
        self.assertEqual(report.problems, [])


class CliFailureModeTests(_CliFixture):
    def test_unknown_options_exit_two_for_both_tools(self) -> None:
        for script in ("count_duplicate_links.py", "broken_link_check.py"):
            with self.subTest(script=script):
                process = self.run_cli(script, "--definitely-not-an-option")
                self.assertEqual(process.returncode, 2)
                self.assertIn("unrecognized arguments", process.stderr)

    def test_duplicate_tool_missing_docs_directory_exits_two(self) -> None:
        shutil.rmtree(self.root / "docs")
        process = self.run_cli("count_duplicate_links.py")
        self.assertEqual(process.returncode, 2)
        self.assertIn("Documentation directory does not exist", process.stderr)

    def test_broken_tool_without_git_repository_exits_two(self) -> None:
        self.write_doc("page.md", "[external](https://example.invalid/x)\n")
        process = self.run_cli("broken_link_check.py")
        self.assertEqual(process.returncode, 2)
        self.assertIn("git check-ignore failed", process.stderr)


if __name__ == "__main__":
    unittest.main()
