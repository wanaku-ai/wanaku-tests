#!/usr/bin/env python3
"""Check the docs/ tree against the page conventions in docs/contributing/documentation.md."""
import re
import sys
from pathlib import Path

DOCS = Path("docs")
errors = []

for page in sorted(DOCS.rglob("*.md")):
    text = page.read_text()
    body = re.sub(r"^(`{3,}|~{3,}).*?^\1", "", text, flags=re.S | re.M)
    h1 = re.findall(r"^# ", body, re.M)
    if len(h1) != 1:
        errors.append(f"{page}: has {len(h1)} H1 headings, expected 1")
    if text.startswith("---\n"):
        errors.append(f"{page}: has frontmatter, which GitHub shows as a table")
    if page != DOCS / "index.md":
        headings = re.findall(r"^## (.+)$", body, re.M)
        if not headings or headings[-1] != "Next steps":
            errors.append(f"{page}: the last H2 is not 'Next steps'")
    if re.search(r"^#+ (Contents|Table of contents|Overview|Related docs)\s*$", body, re.M | re.I):
        errors.append(f"{page}: has a 'Contents', 'Overview' or 'Related docs' heading")
    if re.search(r"^> \*\*(Note|Tip|Important|Warning):?\*\*", body, re.M):
        errors.append(f"{page}: has a bold callout, use GitHub alert syntax such as '> [!NOTE]'")
    if re.search(r"\]\((\./)?here\)|\[here\]\(", body, re.I):
        errors.append(f"{page}: has a link with the text 'here'")
    hub = page.parent / "index.md"
    if page.name != "index.md":
        if not hub.exists():
            errors.append(f"{page}: its folder has no index.md hub")
        elif f"]({page.name})" not in hub.read_text():
            errors.append(f"{page}: not listed in {hub}")

home = (DOCS / "index.md").read_text()
for hub in sorted(DOCS.glob("*/index.md")):
    if f"]({hub.parent.name}/index.md)" not in home:
        errors.append(f"{DOCS / 'index.md'}: does not link the section {hub}")

print("\n".join(errors) or f"OK: {len(list(DOCS.rglob('*.md')))} pages")
sys.exit(1 if errors else 0)
