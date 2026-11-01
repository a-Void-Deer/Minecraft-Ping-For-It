"""Focused, offline Markdown scanning shared by the documentation CLIs.

This is deliberately not a CommonMark renderer. Offsets always refer to the
original text, so masking code/comments does not change source line numbers.
"""

from __future__ import annotations

import io
import os
import re
import string
import sys
import unicodedata
from bisect import bisect_right
from collections.abc import Iterator
from dataclasses import dataclass
from html.entities import html5
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

Standalone CLI stdout/stderr use UTF-8, including redirected output.
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


def configure_cli_output() -> None:
    """Use lossless UTF-8 for standalone CLI output, including Windows pipes.

    Keep the existing streams and error policies; imported main() callers own
    their streams and do not invoke this entrypoint-only configuration.
    """
    for stream in (sys.stdout, sys.stderr):
        if isinstance(stream, io.TextIOWrapper):
            stream.reconfigure(encoding="utf-8", errors=stream.errors)


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
    if re.match(r"^ {0,3}<!--", content):
        return "comment"
    if re.match(r"^ {0,3}[-+*][ \t]+", content):
        return "list"
    ordered = re.match(r"^ {0,3}(\d{1,9})[.)][ \t]+", content)
    # Only an ordered list starting at 1 may interrupt an open paragraph.
    if ordered and (not paragraph_open or int(ordered[1]) == 1):
        return "list"
    return "paragraph"


class _InlineBounds:
    """Index inline limits once instead of rescanning a suffix for each label.

    A continuation is classified with an open paragraph (not a fresh block):
    Setext underlines and interrupting lists/fences/comments stop it. A query
    starting on an ATX line ends there; other starting lines may continue into
    subsequent paragraph lines, even when their own kind would interrupt one.
    """

    def __init__(self, text: str) -> None:
        self.starts = [0] + [match.end() for match in re.finditer("\n", text)]
        self.ends = [len(text)] * len(self.starts)
        continuation_end = len(text)
        for line in range(len(self.starts) - 1, -1, -1):
            start = self.starts[line]
            end = self.starts[line + 1] - 1 if line + 1 < len(self.starts) else len(text)
            kind = _block_kind(text[start:end].rstrip("\r"), True)
            self.ends[line] = end if kind == "atx" else continuation_end
            continuation_end = self.ends[line] if kind == "paragraph" else start - 1

    def end(self, start: int) -> int:
        return self.ends[bisect_right(self.starts, start) - 1]


_CODE_DELIMITERS = re.compile(r"`+")


def _code_span(text: str, start: int, bounds: _InlineBounds) -> tuple[int, int] | None:
    end = start + 1
    while end < len(text) and text[end] == "`":
        end += 1
    delimiter = text[start:end]
    for match in _CODE_DELIMITERS.finditer(text, end, bounds.end(start)):
        if match.group() == delimiter:
            return match.start(), match.end()
    return None


def _block_mask(
    text: str, references: dict[str, str] | None = None, bounds: _InlineBounds | None = None
) -> tuple[str, dict[str, str]]:
    """Discover block definitions before reference-dependent inline masking.

    Definitions cannot interrupt paragraphs. Code spans and inline comments
    cannot cross inline-block boundaries, so resolving a reference cannot turn
    a paragraph continuation into a definition or hide a later block definition.
    """
    bounds = _InlineBounds(text) if bounds is None else bounds
    mask = list(text)
    definitions: dict[str, str] = {}
    fence: tuple[str, int] | None = None
    skip_until = 0
    paragraph_open = False
    list_columns: list[int] = []
    brackets: list[int] = []
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
            if kind == "paragraph" and not paragraph_open:
                definition = _reference_definition(text, start)
                if definition is not None and definition[2] <= bounds.end(start):
                    key, value, skip_until = definition
                    definitions.setdefault(key, value)
                    _hide(mask, start, skip_until)
                    continue
            paragraph_open = kind in ("paragraph", "list")
        index = max(start, skip_until)
        while index < end:
            if _escaped(text, index):
                index += 1
                continue
            if text.startswith("<!--", index):
                block_comment = re.fullmatch(r" {0,3}", text[start:index]) is not None
                limit = len(text) if block_comment else bounds.end(index)
                close = text.find("-->", index + 4, limit)
                if close < 0 and not block_comment:
                    index += 4
                    continue
                skip_until = len(text) if close < 0 else close + 3
                _hide(mask, index, skip_until)
                if block_comment:
                    paragraph_open = False
                index = skip_until
            elif text[index] == "`":
                span = _code_span(text, index, bounds)
                if span:
                    skip_until = span[1]
                    index = skip_until
                else:
                    while index < end and text[index] == "`":
                        index += 1
            else:
                payload_end = _link_payload_end(text, index, brackets, references, bounds)
                if payload_end is not None:
                    skip_until = payload_end
                    index = payload_end
                else:
                    index += 1
    return "".join(mask), definitions


def _link_payload_end(
    text: str, index: int, brackets: list[int], references: dict[str, str] | None, bounds: _InlineBounds
) -> int | None:
    """Consume literal destinations/titles only after a live link label.

    Earlier code spans win, including spans inside labels. Once a label closes,
    a valid payload wins over backticks and comments within that inline block.
    A resolved full/collapsed reference consumes its second label, not any
    parenthesized text that follows it.
    """
    if text[index] == "[":
        brackets.append(index)
    elif text[index] == "]" and brackets:
        opening = brackets.pop()
        next_index = index + 1
        limit = bounds.end(opening)
        if next_index >= limit:
            return None
        if text[next_index] == "(":
            link = _inline_link(text, next_index)
            if link is not None and link[1] <= limit:
                return link[1]
        elif text[next_index] == "[" and references:
            close = _closing_bracket(text, next_index, limit)
            if close >= 0:
                label = text[next_index + 1:close] or text[opening + 1:index]
                if _reference_key(label) in references:
                    return close + 1
    return None


def _inline_mask(
    text: str, references: dict[str, str] | None = None, bounds: _InlineBounds | None = None
) -> str:
    bounds = _InlineBounds(text) if bounds is None else bounds
    mask = list(text)
    brackets: list[int] = []
    index = 0
    while index < len(text):
        if _escaped(text, index):
            index += 1
        elif text[index] == "`":
            span = _code_span(text, index, bounds)
            if span:
                _hide(mask, index, span[1])
                index = span[1]
                continue
            while index < len(text) and text[index] == "`":
                index += 1
        else:
            payload_end = _link_payload_end(text, index, brackets, references, bounds)
            index = payload_end if payload_end is not None else index + 1
    return "".join(mask)


_CHARACTER_REFERENCE = re.compile(r"&(?:#[0-9]{1,7}|#[xX][0-9A-Fa-f]{1,6}|[A-Za-z][A-Za-z0-9]*);")
_ESCAPE_OR_REFERENCE = re.compile(r"\\([" + re.escape(string.punctuation) + r"])|" + _CHARACTER_REFERENCE.pattern)


def _decode_character_reference(match: re.Match[str]) -> str:
    token = match[0]
    if token.startswith("&#"):
        hexadecimal = token[2] in "xX"
        codepoint = int(token[3:-1] if hexadecimal else token[2:-1], 16 if hexadecimal else 10)
        if codepoint == 0 or 0xD800 <= codepoint <= 0xDFFF or codepoint > 0x10FFFF:
            return "\uFFFD"
        return chr(codepoint)
    # Exact names with a semicolon only: HTML's legacy partial/semicolon-less
    # references are not valid Markdown character references.
    return html5.get(token[1:], token)


def _unescape(value: str, destination: bool = False) -> str:
    def replace(match: re.Match[str]) -> str:
        if match[1] is None:
            return _decode_character_reference(match)
        # Preserve a Windows separator immediately before a . or .. segment.
        if destination and match[1] == "." and re.match(r"\.{1,2}(?:[\\/]|$)", value[match.start() + 1:]):
            return match[0]
        return match[1]

    # Consume escapes and references together so an escaped literal ampersand
    # cannot become a character reference in a later decoding pass.
    return _ESCAPE_OR_REFERENCE.sub(replace, value)


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


_REFERENCE_START = re.compile(
    r"(?m)^ {0,3}\[((?:\\.|[^\[\]\\\r\n]|\r?\n(?![ \t]*\r?\n))+)\]:[ \t]*(?:\r?\n[ \t]*)?"
)


def _reference_definition(text: str, start: int) -> tuple[str, str, int] | None:
    match = _REFERENCE_START.match(text, start)
    if match is None:
        return None
    destination = _destination(text, match.end())
    # <> consumes a valid empty destination; no consumed token means failure.
    if destination is None or destination[1] == match.end():
        return None
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
            return None
        while index < len(text) and text[index] in " \t\r":
            index += 1
    if index < len(text) and text[index] != "\n":
        return None
    return _reference_key(match[1]), value, index


def _prepare(text: str) -> tuple[str, dict[str, str], str]:
    bounds = _InlineBounds(text)
    blocks, references = _block_mask(text, bounds=bounds)
    if references:
        # Block discovery is independent of reference resolution. A second pass
        # can therefore resolve forward uses without a definition/mask fixed point.
        blocks, references = _block_mask(text, references, bounds)
    return _inline_mask(blocks, references), references, blocks


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
    bounds = _InlineBounds(text)
    visible = _inline_mask(text, references, bounds)
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
            # A recursively rendered label already decoded its references and
            # restored literal escapes/code. Do not decode that display again.
            protected.append(_display_text(text[index + 1:use.label_end], references))
            pieces.append(f"\x00{len(protected) - 1}\x00")
            index = use.end
        elif text[index] == "`" and not _escaped(text, index) and (span := _code_span(text, index, bounds)):
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
    result = _CHARACTER_REFERENCE.sub(_decode_character_reference, result)
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
        kind = _block_kind(line, bool(paragraph))
        atx = re.match(r"^ {0,3}#{1,6}(?:[ \t]+(.*)|[ \t]*)$", line)
        if atx:
            content = atx[1] or ""
            content = re.sub(r"[ \t]+#+[ \t]*$", "", content)
            headings.append(content)
            paragraph = []
        elif kind == "setext":
            headings.append(" ".join(paragraph))
            paragraph = []
        elif kind != "paragraph" or re.match(r"^(?: {4}|\t| {0,3}(?:>|[-+*][ \t]|\d+[.)][ \t]|<|\|))", line):
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
