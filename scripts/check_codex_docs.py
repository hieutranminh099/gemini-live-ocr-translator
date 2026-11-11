"""Check local inline Markdown links in the five Codex entry documents.

Reads only those documents and checks destination existence; it never scans the
repository or writes files. Fragments, reference-style links, and full Markdown
syntax (including destinations with nested parentheses) are not validated.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit


DOCUMENTS = (
    "AGENTS.md",
    "ARCHITECTURE.md",
    "MODULE_INDEX.md",
    "TESTING.md",
    "INTEGRATION_GUIDE.md",
)
LINK = re.compile(r"!?\[[^\]\n]*\]\(([^)\n]*)\)")
TITLE = re.compile(r"\s+(?:\"[^\"]*\"|'[^']*')\s*$")
FENCE = re.compile(r"^\s*(`{3,}|~{3,})")


def destination(raw: str) -> str:
    raw = raw.strip()
    if raw.startswith("<") and ">" in raw:
        return raw[1:raw.index(">")]
    return TITLE.sub("", raw).strip()


def check(root: Path) -> tuple[int, list[str]]:
    root = root.resolve()
    checked = 0
    errors: list[str] = []
    for name in DOCUMENTS:
        document = root / name
        if not document.resolve().is_relative_to(root):
            errors.append(f"{name}:1: document is outside root: {document}")
            continue
        try:
            lines = document.read_text(encoding="utf-8-sig").splitlines()
        except (OSError, UnicodeError) as error:
            errors.append(f"{name}:1: cannot read document: {error}")
            continue
        fence = ""
        for line_number, line in enumerate(lines, 1):
            marker = FENCE.match(line)
            if marker:
                token = marker.group(1)
                if not fence:
                    fence = token
                elif token[0] == fence[0] and len(token) >= len(fence):
                    fence = ""
                continue
            if fence:
                continue
            for match in LINK.finditer(line):
                target = destination(match.group(1))
                if not target or target.startswith("#"):
                    continue
                location = f"{name}:{line_number}"
                try:
                    url = urlsplit(target)
                    if url.scheme.lower() in {"http", "https", "mailto"}:
                        continue
                    checked += 1
                    relative = Path(unquote(url.path))
                    if url.scheme or url.netloc or relative.drive or relative.root:
                        errors.append(f"{location}: expected a path inside root: {target}")
                        continue
                    resolved = (document.parent / relative).resolve()
                    if not resolved.is_relative_to(root):
                        errors.append(f"{location}: path is outside root: {target}")
                    elif not resolved.exists():
                        errors.append(f"{location}: missing local path: {target}")
                except (OSError, ValueError) as error:
                    errors.append(f"{location}: invalid local path {target!r}: {error}")
    return checked, errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    checked, errors = check(args.root)
    for error in errors:
        print(error)
    print(f"Checked {len(DOCUMENTS)} documents: {checked} local links, {len(errors)} errors.")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
