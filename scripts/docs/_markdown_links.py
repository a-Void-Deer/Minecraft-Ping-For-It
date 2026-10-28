"""Focused, offline Markdown scanning shared by the documentation CLIs.

This is deliberately not a CommonMark renderer. Offsets always refer to the
original text, so masking code/comments does not change source line numbers.
"""

from __future__ import annotations

import html
import os
import re
import string
import unicodedata
from bisect import bisect_right
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import unquote


SYNTAX_HELP = """\
Supported Markdown: inline links (multiline labels, code-span labels, balanced
or escaped destination parentheses, angle-bracket destinations, optional titles)
and full/collapsed/shortcut references, counted at uses rather than definitions.
Code spans, HTML comments, top-level fenced code and ordinary indented code are
excluded. Indentation handling preserves ordinary nested-list continuations;
it is not a complete CommonMark list/blockquote container engine. Raw HTML
links, autolinks and general HTML-block parsing are not supported.
"""


@dataclass(frozen=True)
class MarkdownLink:
    source: Path
    line: int
    destination: str
    is_image: bool


@dataclass(frozen=True)
class LocalTarget:
    path: Path
    relative: str
    query: str | None
    fragment: str | None
    directory_hint: bool = False

    def display(self, ignore_fragment: bool = False) -> str:
        query = "" if self.query is None else "?" + self.query
        fragment = "" if ignore_fragment or self.fragment is None else "#" + self.fragment
        return self.relative + query + fragment

    def key(self, ignore_fragment: bool = False) -> tuple[str, str | None, str | None]:
        # Windows-compatible path comparison; query and fragment stay case-sensitive.
        return self.relative.casefold(), self.query, None if ignore_fragment else self.fragment


@dataclass(frozen=True)
class _LinkUse:
    start: int
    end: int
    label_end: int
    destination: str
    is_image: bool


def repository_root() -> Path:
    return Path(__file__).resolve().parents[2]


def markdown_sources(root: Path) -> list[Path]:
    docs = root / "docs"
    if not docs.is_dir():
        raise OSError(f"Documentation directory does not exist: {docs}")

    def fail(error: OSError) -> None:
        raise error

    # Do not enumerate Git's index: ignored/untracked Markdown is also a source.
    files = [
        Path(directory) / name
        for directory, _, names in os.walk(docs, onerror=fail)
        for name in names
        if name.casefold().endswith(".md")
    ]
    return sorted(files, key=lambda path: path.relative_to(root).as_posix())


def read_markdown(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig")


def tsv(value: object) -> str:
    return str(value).replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")


def _escaped(text: str, index: int) -> bool:
    start = index
    while start > 0 and text[start - 1] == "\\":
        start -= 1
    return (index - start) % 2 == 1


def _hide(mask: list[str], start: int, end: int) -> None:
    for index in range(start, end):
        if mask[index] not in "\r\n":
            mask[index] = " "


def _block_kind(content: str, paragraph_open: bool) -> str:
    """Classify the block boundaries understood by this focused scanner."""
    if not content.strip():
        return "blank"
    if re.match(r"^ {0,3}#{1,6}(?:[ \t]|$)", content):
        return "atx"
    if paragraph_open and re.fullmatch(r" {0,3}(?:=+|-+)[ \t]*", content):
        return "setext"
    opening = re.match(r"^ {0,3}(`{3,}|~{3,})(.*)$", content)
    if opening and not (opening[1][0] == "`" and "`" in opening[2]):
        return "fence"
    if re.fullmatch(r" {0,3}(?:(?:\*[ \t]*){3,}|(?:_[ \t]*){3,}|(?:-[ \t]*){3,})", content):
        return "thematic"
    if re.match(r"^ {0,3}[-+*][ \t]+", content):
        return "list"
    ordered = re.match(r"^ {0,3}(\d{1,9})[.)][ \t]+", content)
    # Only an ordered list starting at 1 may interrupt an open paragraph.
    if ordered and (not paragraph_open or int(ordered[1]) == 1):
        return "list"
    return "paragraph"


def _inline_end(text: str, start: int) -> int:
    """Find the current inline block's end before matching code delimiters.

    Paragraph continuations may contain newlines (and extra indentation), but
    blank lines and supported interrupting blocks cannot be part of a code span.
    ATX headings are single-line inline blocks; Setext text ends at its underline.
    """
    line_start = text.rfind("\n", 0, start) + 1
    line_end = text.find("\n", start)
    if line_end < 0:
        return len(text)
    if _block_kind(text[line_start:line_end].rstrip("\r"), False) == "atx":
        return line_end
    while line_end < len(text):
        next_start = line_end + 1
        next_end = text.find("\n", next_start)
        if next_end < 0:
            next_end = len(text)
        content = text[next_start:next_end].rstrip("\r")
        if _block_kind(content, True) != "paragraph":
            return line_end
        line_end = next_end
    return len(text)


def _code_span(text: str, start: int) -> tuple[int, int] | None:
    end = start + 1
    while end < len(text) and text[end] == "`":
        end += 1
    delimiter = text[start:end]
    for match in re.finditer(r"`+", text[end:_inline_end(text, start)]):
        if match.group() == delimiter:
            close = end + match.start()
            return close, close + len(delimiter)
    return None


def _block_mask(text: str) -> str:
    mask = list(text)
    fence: tuple[str, int] | None = None
    skip_until = 0
    paragraph_open = False
    list_columns: list[int] = []
    offset = 0
    for line in text.splitlines(keepends=True):
        start, end = offset, offset + len(line)
        offset = end
        content = line.rstrip("\r\n")
        if fence is not None:
            _hide(mask, start, end)
            character, length = fence
            if re.fullmatch(r" {0,3}" + re.escape(character) + "{" + str(length) + r",}[ \t]*", content):
                fence = None
            paragraph_open = False
            continue
        if start >= skip_until:
            kind = _block_kind(content, paragraph_open)
            if kind == "fence":
                opening = re.match(r"^ {0,3}(`{3,}|~{3,})", content)
                assert opening is not None
                fence = opening[1][0], len(opening[1])
                _hide(mask, start, end)
                paragraph_open = False
                continue
            expanded = content.expandtabs(4)
            indent = len(expanded) - len(expanded.lstrip(" "))
            blank = not content.strip()
            if not blank:
                while list_columns and indent < list_columns[-1]:
                    list_columns.pop()
                threshold = (list_columns[-1] if list_columns else 0) + 4
                # Indented code cannot interrupt a paragraph, but it can start
                # immediately after any completed block, not just a blank line.
                if indent >= threshold and not paragraph_open:
                    _hide(mask, start, end)
                    continue
                marker = re.match(r"^ *(?:[-+*]|\d{1,9}[.)])[ \t]+", expanded)
                if marker:
                    list_columns.append(marker.end())
            paragraph_open = kind in ("paragraph", "list")
        index = max(start, skip_until)
        while index < end:
            if _escaped(text, index):
                index += 1
                continue
            if text.startswith("<!--", index):
                close = text.find("-->", index + 4)
                skip_until = len(text) if close < 0 else close + 3
                _hide(mask, index, skip_until)
                index = skip_until
            elif text[index] == "`":
                span = _code_span(text, index)
                if span:
                    skip_until = span[1]
                    index = skip_until
                else:
                    while index < end and text[index] == "`":
                        index += 1
            else:
                index += 1
    return "".join(mask)


def _inline_mask(text: str) -> str:
    mask = list(text)
    index = 0
    while index < len(text):
        if text[index] == "`" and not _escaped(text, index):
            span = _code_span(text, index)
            if span:
                _hide(mask, index, span[1])
                index = span[1]
                continue
            while index < len(text) and text[index] == "`":
                index += 1
        else:
            index += 1
    return "".join(mask)


def _unescape(value: str, destination: bool = False) -> str:
    def replace(match: re.Match[str]) -> str:
        # Preserve a Windows separator immediately before a . or .. segment.
        if destination and match[1] == "." and re.match(r"\.{1,2}(?:[\\/]|$)", value[match.start() + 1:]):
            return match[0]
        return match[1]

    return html.unescape(re.sub(r"\\([" + re.escape(string.punctuation) + r"])", replace, value))


def _closing_bracket(text: str, start: int, limit: int) -> int:
    depth = 1
    for index in range(start + 1, limit):
        if _escaped(text, index):
            continue
        if text[index] == "[":
            depth += 1
        elif text[index] == "]":
            depth -= 1
            if depth == 0:
                return index
    return -1


def _destination(text: str, start: int) -> tuple[str, int] | None:
    index = start
    if index < len(text) and text[index] == "<":
        index += 1
        while index < len(text):
            if text[index] in "\r\n" or (text[index] == "<" and not _escaped(text, index)):
                return None
            if text[index] == ">" and not _escaped(text, index):
                return text[start + 1:index], index + 1
            index += 1
        return None
    depth = 0
    while index < len(text):
        character = text[index]
        if _escaped(text, index):
            index += 1
            continue
        if character.isspace():
            break
        if character == "<":
            return None
        if character == "(":
            depth += 1
        elif character == ")":
            if depth == 0:
                break
            depth -= 1
        index += 1
    return None if depth else (text[start:index], index)


def _title_end(text: str, start: int) -> int:
    if start >= len(text) or text[start] not in "\"'(":
        return -1
    closing = ")" if text[start] == "(" else text[start]
    for index in range(start + 1, len(text)):
        if _escaped(text, index):
            continue
        if text[index] == closing:
            return index + 1
        if closing == ")" and text[index] == "(":
            return -1
    return -1


def _inline_link(text: str, start: int) -> tuple[str, int] | None:
    index = start + 1
    while index < len(text) and text[index].isspace():
        index += 1
    destination = _destination(text, index)
    if destination is None:
        return None
    value, end = destination
    index = end
    while index < len(text) and text[index].isspace():
        index += 1
    if index > end and index < len(text) and text[index] != ")":
        index = _title_end(text, index)
        if index < 0:
            return None
        while index < len(text) and text[index].isspace():
            index += 1
    if index < len(text) and text[index] == ")":
        return value, index + 1
    return None


def _reference_key(label: str) -> str:
    return " ".join(_unescape(label).split()).casefold()


def _prepare(text: str) -> tuple[str, dict[str, str], str]:
    blocks = list(_block_mask(text))
    visible = list(_inline_mask("".join(blocks)))
    references: dict[str, str] = {}
    pattern = r"(?m)^ {0,3}\[((?:\\.|[^\[\]\\\r\n])+)\]:[ \t]*(?:\r?\n[ \t]*)?"
    for match in re.finditer(pattern, "".join(visible)):
        if visible[match.start() + match[0].index("[")] != "[":
            continue
        destination = _destination(text, match.end())
        if destination is None or not destination[0]:
            continue
        value, end = destination
        index = end
        while index < len(text) and text[index] in " \t\r":
            index += 1
        title_start = index
        if index < len(text) and text[index] == "\n":
            next_line = index + 1
            while next_line < len(text) and text[next_line] in " \t":
                next_line += 1
            if next_line < len(text) and text[next_line] in "\"'(":
                title_start = next_line
        if title_start > end and title_start < len(text) and text[title_start] in "\"'(":
            index = _title_end(text, title_start)
            if index < 0:
                continue
            while index < len(text) and text[index] in " \t\r":
                index += 1
        if index < len(text) and text[index] != "\n":
            continue
        references.setdefault(_reference_key(text[match.start(1):match.end(1)]), value)
        _hide(visible, match.start(), index)
        _hide(blocks, match.start(), index)
    return "".join(visible), references, "".join(blocks)


def _link_uses(
    text: str, visible: str, references: dict[str, str], start: int = 0, limit: int | None = None
) -> Iterator[_LinkUse]:
    limit = len(text) if limit is None else limit
    index = start
    while index < limit:
        if visible[index] != "[" or _escaped(visible, index):
            index += 1
            continue
        close = _closing_bracket(visible, index, limit)
        if close < 0 or re.search(r"\n[ \t]*\n", visible[index:close]):
            index += 1
            continue
        next_index = close + 1
        link = _inline_link(text, next_index) if next_index < limit and visible[next_index] == "(" else None
        if link is None:
            label = text[index + 1:close]
            end = next_index
            if next_index < limit and visible[next_index] == "[":
                reference_close = _closing_bracket(visible, next_index, limit)
                if reference_close >= 0:
                    label = text[next_index + 1:reference_close] or label
                    end = reference_close + 1
            key = _reference_key(label)
            if key in references:
                link = references[key], end
        if link is None or link[1] > limit:
            index += 1
            continue
        image = index > 0 and visible[index - 1] == "!" and not _escaped(visible, index - 1)
        yield _LinkUse(index, link[1], close, link[0], image)
        # A linked image still needs its image destination checked.
        if not image:
            for nested in _link_uses(text, visible, references, index + 1, close):
                if nested.is_image:
                    yield nested
        index = link[1]


def parse_links(text: str, source: Path) -> list[MarkdownLink]:
    visible, references, _ = _prepare(text)
    starts = [0] + [match.end() for match in re.finditer("\n", text)]
    return [
        MarkdownLink(source, bisect_right(starts, use.start), use.destination, use.is_image)
        for use in _link_uses(text, visible, references)
    ]


def normalize_destination(destination: str, source: Path, root: Path) -> LocalTarget | None:
    """Return None for external URIs; split URI components before percent decoding."""
    value = _unescape(destination, destination=True)
    if re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", value) or value.startswith(("//", "\\\\")):
        return None
    path_query, has_fragment, fragment = value.partition("#")
    raw_path, has_query, query = path_query.partition("?")
    path = unquote(raw_path, errors="strict").replace("\\", "/")
    query_value = unquote(query, errors="strict") if has_query else None
    fragment_value = unquote(fragment, errors="strict") if has_fragment else None
    if "\0" in path or re.match(r"^[A-Za-z]:", path) or path.startswith("//"):
        raise ValueError("invalid local path (NUL, drive-qualified or UNC path)")
    if not path:
        full_path = source
    else:
        base = root if path.startswith("/") else source.parent
        full_path = Path(os.path.abspath(base / path.lstrip("/")))
    relative = os.path.relpath(full_path, root).replace("\\", "/")
    return LocalTarget(full_path, relative, query_value, fragment_value, path.endswith(("/", "/.", "/..")))


def _display_text(text: str, references: dict[str, str]) -> str:
    # Preserve code contents while removing their delimiters and Markdown styling.
    visible = _inline_mask(text)
    uses = list(_link_uses(text, visible, references))
    by_start = {use.start: use for use in uses}
    pieces: list[str] = []
    protected: list[str] = []
    index = 0
    while index < len(text):
        use = by_start.get(index)
        if use:
            if use.is_image and pieces and pieces[-1] == "!":
                pieces.pop()
            pieces.append(_display_text(text[index + 1:use.label_end], references))
            index = use.end
        elif text[index] == "`" and not _escaped(text, index) and (span := _code_span(text, index)):
            delimiter_end = index + 1
            while delimiter_end < len(text) and text[delimiter_end] == "`":
                delimiter_end += 1
            code = re.sub(r"\r\n?|\n", " ", text[delimiter_end:span[0]])
            if code.startswith(" ") and code.endswith(" ") and code.strip(" "):
                code = code[1:-1]
            protected.append(code)
            pieces.append(f"\x00{len(protected) - 1}\x00")
            index = span[1]
        elif text[index] == "\\" and index + 1 < len(text) and text[index + 1] in string.punctuation:
            protected.append(text[index + 1])
            pieces.append(f"\x00{len(protected) - 1}\x00")
            index += 2
        else:
            pieces.append(text[index])
            index += 1
    result = "".join(pieces)
    result = re.sub(r"<[^>]*>", "", result)
    result = re.sub(r"(\*{1,3}|~~)(?=\S)(.+?)(?<=\S)\1", r"\2", result)
    # Underscores inside words are literal, unlike ordinary emphasis delimiters.
    result = re.sub(r"(?<!\w)(_{1,3})(?=\S)(.+?)(?<=\S)\1(?!\w)", r"\2", result)
    result = html.unescape(result)
    return re.sub(r"\x00(\d+)\x00", lambda match: protected[int(match[1])], result)


def heading_anchors(text: str) -> set[str]:
    """GitHub-style heading slugs, not a claim of complete GFM equivalence.

    Supports ATX and Setext headings, display text, Unicode and duplicate-slug
    collisions. Explicit HTML id/name anchors are intentionally not interpreted.
    """
    _, references, blocks = _prepare(text)
    headings: list[str] = []
    paragraph: list[str] = []
    for line in blocks.splitlines():
        atx = re.match(r"^ {0,3}#{1,6}(?:[ \t]+(.*)|[ \t]*)$", line)
        if atx:
            content = atx[1] or ""
            content = re.sub(r"[ \t]+#+[ \t]*$", "", content)
            headings.append(content)
            paragraph = []
        elif re.fullmatch(r" {0,3}(?:=+|-+)[ \t]*", line) and paragraph:
            headings.append(" ".join(paragraph))
            paragraph = []
        elif not line.strip() or re.match(r"^(?: {4}|\t| {0,3}(?:>|[-+*][ \t]|\d+[.)][ \t]|<|\|))", line):
            paragraph = []
        else:
            paragraph.append(line.strip())
    anchors: set[str] = set()
    counters: dict[str, int] = {}
    for heading in headings:
        display = _display_text(heading, references).strip().lower()
        base = "".join(
            character for character in display
            if character in "-_" or character.isspace() or unicodedata.category(character)[0] in "LNM"
        )
        base = re.sub(r"\s", "-", base)
        slug = base
        suffix = counters.get(base, 0)
        while slug in anchors:
            suffix += 1
            slug = f"{base}-{suffix}"
        counters[base] = suffix
        anchors.add(slug)
    return anchors
