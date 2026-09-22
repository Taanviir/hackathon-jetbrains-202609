"""Build the reports site from the `reports/` folder of every branch.

Each branch's reports are published at /<branch>/<report>/, and the index lists them all, so a
teammate's report shows up without a merge. Standard library only.

    python reports/build_site.py _site
"""

import html
import io
import json
import re
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "_site")
TITLE_RE = re.compile(r"<title>(.*?)</title>", re.I | re.S)


def git(*args: str) -> bytes:
    return subprocess.run(["git", *args], check=True, capture_output=True).stdout


def branches() -> list[str]:
    refs = git("for-each-ref", "--format=%(refname:short)", "refs/remotes/origin").decode().split()
    names = sorted({r.removeprefix("origin/") for r in refs if r not in ("origin", "origin/HEAD")})
    return sorted(names, key=lambda b: (b != "main", b))


def slug(branch: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "-", branch)


def extract(branch: str, dest: Path) -> bool:
    """Copy origin/<branch>:reports/ into dest. False if the branch has no reports/."""
    try:
        archive = git("archive", "--format=tar", f"origin/{branch}", "reports")
    except subprocess.CalledProcessError:
        return False
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for m in tar.getmembers():
            rel = Path(m.name).relative_to("reports")
            if not m.isfile() or len(rel.parts) < 2:  # skip reports/README.md and the builder itself
                continue
            target = dest / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(tar.extractfile(m).read())
    return True


def describe(report_dir: Path) -> dict:
    meta = {}
    if (report_dir / "meta.json").is_file():
        try:
            meta = json.loads((report_dir / "meta.json").read_text())
        except ValueError:
            pass
    if "title" not in meta:
        found = TITLE_RE.search((report_dir / "index.html").read_text(errors="replace"))
        meta["title"] = found.group(1).strip() if found else report_dir.name
    return meta


def main():
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    groups = []
    for branch in branches():
        dest = OUT / slug(branch)
        if not extract(branch, dest):
            continue
        reports = []
        for d in sorted(p for p in dest.iterdir() if p.is_dir() and (p / "index.html").is_file()):
            reports.append({"path": f"{slug(branch)}/{d.name}/", **describe(d)})
        reports.sort(key=lambda r: r.get("date", ""), reverse=True)
        if reports:
            groups.append((branch, reports))
    (OUT / "index.html").write_text(index_page(groups))
    (OUT / ".nojekyll").write_text("")
    print(f"built {sum(len(r) for _, r in groups)} reports from {len(groups)} branches into {OUT}")


def index_page(groups) -> str:
    e = html.escape
    sections = []
    for branch, reports in groups:
        items = "".join(
            f'<li><a href="{e(r["path"])}">{e(r["title"])}</a>'
            f'<span class="m">{e(" · ".join(x for x in (r.get("author", ""), r.get("date", "")) if x))}</span>'
            + (f'<p>{e(r["description"])}</p>' if r.get("description") else "") + "</li>"
            for r in reports)
        sections.append(f'<h2><code>{e(branch)}</code></h2><ul>{items}</ul>')
    body = "".join(sections) or "<p>No reports yet. See reports/README.md in the repo for how to add one.</p>"
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Hackathon reports</title>
<style>
:root {{ --page:#f9f9f7; --surface:#fcfcfb; --ink:#0b0b0b; --ink-2:#52514e; --ink-3:#75746f; --rule:#e4e3de; --link:#2a78d6; --code:#f0efec; }}
@media (prefers-color-scheme: dark) {{ :root:not([data-theme="light"]) {{ --page:#0d0d0d; --surface:#1a1a19; --ink:#fff; --ink-2:#c3c2b7; --ink-3:#9b9a92; --rule:#34332f; --link:#3987e5; --code:#262624; }} }}
:root[data-theme="dark"] {{ --page:#0d0d0d; --surface:#1a1a19; --ink:#fff; --ink-2:#c3c2b7; --ink-3:#9b9a92; --rule:#34332f; --link:#3987e5; --code:#262624; }}
body {{ margin:0; background:var(--page); color:var(--ink); font:16px/1.55 ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif; }}
main {{ max-width:760px; margin:0 auto; padding:40px 16px 80px; }}
h1 {{ font-size:28px; margin:0 0 6px; }} h2 {{ font-size:15px; margin:32px 0 8px; color:var(--ink-3); font-weight:600; }}
p {{ color:var(--ink-2); }} a {{ color:var(--link); font-weight:600; text-decoration:none; }} a:hover {{ text-decoration:underline; }}
code {{ font:13px ui-monospace,SFMono-Regular,Menlo,monospace; background:var(--code); padding:2px 6px; border-radius:4px; color:var(--ink-2); }}
ul {{ list-style:none; padding:0; margin:0; }}
li {{ background:var(--surface); border:1px solid var(--rule); border-radius:10px; padding:14px 16px; margin:10px 0; }}
li p {{ margin:4px 0 0; font-size:14px; }} .m {{ color:var(--ink-3); font-size:13px; margin-left:10px; }}
</style></head><body><main>
<h1>Hackathon reports</h1>
<p>Eval benchmarks and other HTML outputs from the team, collected from every branch. To add one, put
<code>reports/&lt;name&gt;/index.html</code> on any branch; see <code>reports/README.md</code>.</p>
{body}
</main></body></html>
"""


if __name__ == "__main__":
    main()
