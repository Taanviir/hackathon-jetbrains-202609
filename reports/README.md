# Reports

Eval benchmarks and any other HTML outputs, published at
**https://taanviir.github.io/hackathon-jetbrains-202609/**.

The site is **public**, even though this repository is private. Never put keys, tokens, customer
data or anything else you wouldn't post openly in a report.

## Add a report

1. Make a folder `reports/<name>/` on any branch, with an `index.html`. Use lowercase with
   hyphens for the name, e.g. `reports/refactor-bench/`.
2. Optionally add `reports/<name>/meta.json` so the index shows more than the page title:

   ```json
   {
     "title": "Refactor suggester benchmark",
     "author": "aikram42",
     "date": "2026-09-23",
     "description": "One or two sentences on what was measured and the headline number."
   }
   ```

3. Push. The site rebuilds and your report appears at `/<branch>/<name>/`. Branch names have `/`
   replaced with `-`, so `feat/x` becomes `feat-x`.

Keep a report self-contained: inline CSS and JS, or put assets next to `index.html` and use
relative paths. Links back to the index are `../../`.

## How it works

`.github/workflows/pages.yml` runs `reports/build_site.py`, which reads the `reports/` folder of
**every branch**. Nobody has to merge before their report is visible, and a push on one branch
doesn't remove anyone else's. The index lists `main` first, then other branches.

It runs on any push that touches `reports/`, and can also be started by hand from the Actions tab
(*Publish reports* → *Run workflow*). A branch whose copy of the workflow is older just waits for
the next build from any branch.

To preview locally: `python reports/build_site.py _site` (it reads `origin/*`, so fetch first),
then open `_site/index.html`.
