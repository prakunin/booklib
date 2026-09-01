# Vendored foliate-js — provenance and local patches

This directory is a vendored copy of [foliate-js](https://github.com/johnfactotum/foliate-js)
(MIT), the EPUB/FB2/MOBI rendering engine used by the ebook reader
(`src/app/features/readers/ebook-reader/`). It is **not** an npm dependency: the files are
served as raw assets and injected at runtime, so nothing here is bundled, linted or type-checked.

The copy was inherited from upstream Booklore (before the Grimmory and BookLib forks); the exact
upstream foliate-js commit it was taken from is not recorded. Treat every file as diverged and
diff against upstream before replacing any of them.

## Files that are not upstream

| File | Origin |
|---|---|
| `continuous-scroller.js` | Written for BookLib in `866c73dca` (2026-07-18, "improve ebook reader scrolling", PR #13). Continuous vertical scrolling mode; `view.js` was extended in the same commit to host it. |

## Locally patched upstream files

| File | Commits | What changed |
|---|---|---|
| `view.js` | `866c73dca` | Hooks for the continuous scroller (+88/−16). |
| `view.js`, `epub.js`, `epubcfi.js`, `fb2.js`, `fixed-layout.js`, `mobi.js`, `paginator.js`, `search.js`, `continuous-scroller.js` | `d28890420` (2026-07-19, PR #150) | Quality-gate cleanups only (no behaviour change intended). |
| `fb2.js`, `continuous-scroller.js` | `d00839046` (2026-07-22, PR #159) | Footnote popover and working external links: FB2 note links resolve to their target, external `http(s)` links open instead of being swallowed, and the scroller exposes the target for the popover. |
| `fixed-layout.js`, `paginator.js` | `30434e4dc` (2026-04-25, upstream Booklore #848) | Protect the reader iframe with CSP instead of `sandbox`. |
| `paginator.js` | `f359e49a6` (2026-04-28, upstream Booklore #956) | Avoid a console error when leaving the reader. |

Untouched since vendoring: `comic-book.js`, `dict.js`, `overlayer.js`, `progress.js`,
`streaming-loader.js`, `text-walker.js`, `tts.js`, `vendor/`.

## Updating

1. Diff the target upstream version against this directory file by file.
2. Re-apply the patches listed above (use `git show <commit> -- frontend/src/assets/foliate/<file>` for the exact hunks).
3. Add a row to the table above and run the ebook-reader specs plus a manual FB2/EPUB smoke test
   (footnote popover, external link, continuous scroll).
