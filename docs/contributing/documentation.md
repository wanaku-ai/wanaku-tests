# Write documentation

This page gives the conventions of the contributor documentation in `docs/`, and the checks that CI runs on it. The
documentation is read on GitHub. It is not published on the Wanaku website.

## Where a page goes

| Folder | Content |
|--------|---------|
| `get-started/` | The overview and the first run |
| `integration-tests/` | How to run, debug and write the Java integration tests |
| `test-plans/` | How to run and write the Saci test plans |
| `fixtures/` | The fixtures and mocks |
| `load-tests/` | The k6 load tests |
| `reference/` | Look-up tables: system properties, workflows |
| `contributing/` | This page |

Each folder has an `index.md` hub. The hub lists every page of the folder, with one line about each page. The home
page (`docs/index.md`) links every hub.

Keep the documentation of a directory in `docs/`, not in a README in that directory. When you move a README into
`docs/`, leave a short pointer file at the old path, so that links and GitHub browsing still work. Do not move the
test plans in `plans/`: Saci runs them as files.

## Page conventions

- Use lowercase kebab-case file names of four words or fewer. Use a verb and an object for a how-to
  (`run-a-test-plan.md`), and a noun for a reference page (`system-properties.md`).
- Do not add frontmatter. GitHub shows it as a table.
- Use one H1. Do not skip heading levels. Stop at H3.
- Start with one to three sentences that say what the reader can do with the page.
- Do not use "Overview" or "Contents" headings.
- End each page, except the home page, with a "Next steps" section of two to four links. Put nothing after it.
- Use relative links with the `.md` extension. Links to plans, fixtures and source files can also be relative,
  because GitHub shows them.
- Use the name of the destination page as the link text, never "here".
- Use GitHub alerts (`> [!NOTE]`, `> [!WARNING]`) for callouts.
- Use ASD-STE100 Simplified Technical English for procedures, configuration and system behavior: short sentences,
  active voice, one instruction in each sentence, and the same term for the same concept.

## Claims

When you write a sentence about the behavior of the tests or of a workflow, check it against the code or the
workflow file. Do not copy a fact into a second page. Link the page that owns it.

## Checks

Run these checks before you push. The `Docs` workflow runs them on each pull request.

```bash
python3 .github/check-docs.py
npx -y markdownlint-cli2
lychee --no-progress --include-fragments --exclude-path specs --exclude-path .specify --exclude-path .bob '**/*.md'
```

`check-docs.py` checks the conventions of this page that a script can check. markdownlint uses
`.markdownlint-cli2.yaml`, which does not lint `plans/`. lychee checks the links and anchors of all Markdown files,
and skips the URLs in `.lycheeignore`.

## Next steps

- [Wanaku tests documentation](../index.md)
- [Write a test plan](../test-plans/write-a-test-plan.md)
