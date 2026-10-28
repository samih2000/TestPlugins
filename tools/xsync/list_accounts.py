#!/usr/bin/env python3
"""Counts which X accounts your Raindrop bookmarks come from.

Reads every bookmark through the Raindrop API, extracts the account from each tweet link
and writes tools/xsync/accounts_report.csv (most bookmarked first). Also prints the top
accounts to the GitHub job summary. Nothing in Raindrop is changed.

Env var: RAINDROP_TOKEN
"""
import collections
import csv
import json
import os
import re
import sys
import time
from pathlib import Path

import requests

HERE = Path(__file__).parent
TOKEN = os.environ.get("RAINDROP_TOKEN", "")
if not TOKEN:
    sys.exit("Set RAINDROP_TOKEN")

LINK_RX = re.compile(
    r"(?:x|twitter|vxtwitter|fxtwitter|fixupx|fixvx)\.com/([A-Za-z0-9_]{1,15})/status/(\d+)",
    re.IGNORECASE,
)
RESERVED = {
    "i", "home", "search", "intent", "share", "hashtag", "explore", "messages",
    "settings", "notifications", "compose", "login", "tos", "privacy",
}


def main():
    tracked = set()
    try:
        cfg = json.loads((HERE / "accounts.json").read_text(encoding="utf-8"))
        tracked = {a.lower() for a in cfg.get("accounts", [])}
    except (FileNotFoundError, json.JSONDecodeError):
        pass

    tweets_by_user = collections.defaultdict(set)   # lowercase name -> tweet ids
    display = {}                                     # lowercase name -> spelling as bookmarked
    total, non_tweet, page = 0, 0, 0

    while page < 400:
        r = requests.get(
            "https://api.raindrop.io/rest/v1/raindrops/0",
            headers={"Authorization": f"Bearer {TOKEN}"},
            params={"perpage": 50, "page": page, "sort": "-created"},
            timeout=30,
        )
        if r.status_code != 200:
            sys.exit(f"Raindrop HTTP {r.status_code}: {r.text[:200]}")
        items = r.json().get("items", [])
        if not items:
            break
        for it in items:
            total += 1
            m = LINK_RX.search(it.get("link", ""))
            if not m or m.group(1).lower() in RESERVED:
                non_tweet += 1
                continue
            key = m.group(1).lower()
            tweets_by_user[key].add(m.group(2))
            display.setdefault(key, m.group(1))
        page += 1
        time.sleep(0.6)

    rows = sorted(tweets_by_user.items(), key=lambda kv: (-len(kv[1]), kv[0]))
    out = HERE / "accounts_report.csv"
    with out.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["rank", "username", "bookmarked_tweets", "already_tracked"])
        for i, (key, ids) in enumerate(rows, 1):
            w.writerow([i, display[key], len(ids), "yes" if key in tracked else ""])

    lines = [
        f"Read {total} bookmarks: {total - non_tweet} tweet links from {len(rows)} accounts, "
        f"{non_tweet} other links.",
        "",
        "| # | account | bookmarks | tracked |",
        "|---|---------|-----------|---------|",
    ]
    for i, (key, ids) in enumerate(rows[:60], 1):
        lines.append(f"| {i} | @{display[key]} | {len(ids)} | {'yes' if key in tracked else ''} |")
    text = "\n".join(lines)
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        Path(summary).write_text(text + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
