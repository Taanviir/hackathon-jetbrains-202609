"""Rewrite A/B task subjects the way a developer would ask, without naming code identifiers.

Commit subjects often name the exact class or field ("Map cachedContentTokenCount ..."), which hands
grep the answer. Real requests usually don't. Product names (Ollama, Langfuse) stay: people say those.

    uv run python vague.py --offset 40 --tasks 10
"""

import argparse
import json
import os
import re
import sys
from pathlib import Path

import httpx
from dotenv import load_dotenv

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "spike"))
import koog  # noqa: E402

load_dotenv(koog.ROOT.parents[1] / ".env")
PROMPT = """Rewrite this commit subject as a short request a developer might type to a coding assistant.
Do NOT name any code identifier: no class, function, method, field, parameter, variable, file or module
names, nothing in camelCase, snake_case or backticks. Describe the behaviour instead. Product or
provider names such as OpenAI, Ollama or Langfuse are fine. Keep the meaning. One sentence, no quotes.

Commit subject: {task}"""
IDENT = re.compile(r"`|\b[a-z]+[A-Z]\w*|\b\w+_\w+\b|\b[A-Z][a-z]+[A-Z]\w*|\w+\.\w+\(|\.kt\b")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--offset", type=int, default=40)
    ap.add_argument("--tasks", type=int, default=10)
    args = ap.parse_args()
    tasks = koog.load_tasks(limit=args.offset + args.tasks)[args.offset:]
    out, cost = [], 0.0
    with httpx.Client(timeout=120, headers={"Authorization": f"Bearer {os.environ['OPENROUTER_API_KEY']}"}) as http:
        for t in tasks:
            r = http.post("https://openrouter.ai/api/v1/chat/completions", json={
                "model": "z-ai/glm-5.3-flash", "temperature": 0,
                "messages": [{"role": "user", "content": PROMPT.format(task=t.task)}]}).json()
            cost += (r.get("usage") or {}).get("cost") or 0.0
            vague = r["choices"][0]["message"]["content"].strip().strip('"')
            leaks = IDENT.findall(vague)
            out.append({"sha": t.sha[:8], "original": t.task, "vague": vague, "identifier_leaks": leaks})
            print(f"{t.sha[:8]}  {'LEAK ' + str(leaks) if leaks else 'clean'}\n   was: {t.task}\n   now: {vague}")
    (HERE / "results").mkdir(exist_ok=True)
    (HERE / "results" / "vague_tasks.json").write_text(json.dumps(out, indent=1))
    print(f"\ncost ${cost:.4f}")


if __name__ == "__main__":
    main()
