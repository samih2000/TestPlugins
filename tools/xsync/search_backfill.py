#!/usr/bin/env python3
"""Backfills OLD video tweets from X accounts using X's search (SearchTimeline).

The normal sync reads each account's media/posts tab, which X cuts off after a few
hundred tweets. Search ("from:name filter:native_video") can page much further back.
Reuses everything from sync.py (login, parsing, Raindrop saving, duplicate check), so
bookmarks look exactly the same. Tweets keep their ORIGINAL date in Raindrop.

Env vars: RAINDROP_TOKEN, X_AUTH_TOKEN, X_CT0
Usage:    python search_backfill.py [--accounts a,b,c] [--pages N] [--until YYYY-MM-DD] [--dry]
"""
import argparse
import os
import sys
import time

import sync  # same folder: tools/xsync/sync.py

SEARCH_QUERY_ID = "3fEctOs2-FvTB7_mvm1hrQ"  # copied from x.com's own search request
EMPTY_PAGES_ALLOWED = 3


_ct = None  # x-client-transaction-id generator, built once


def init_transaction_ids():
    """X's search endpoint answers 404 unless the request carries a signed
    x-client-transaction-id header (a value the website computes in JavaScript).
    The 'x-client-transaction' package re-creates it. Optional: if it is missing or
    breaks, the script still runs and says so (search will most likely 404 then).
    """
    global _ct
    try:
        import bs4
        import requests
        from x_client_transaction import ClientTransaction
        from x_client_transaction.utils import (
            generate_headers, get_ondemand_file_url, handle_x_migration,
        )
        s = requests.Session()
        s.headers = generate_headers()
        home = handle_x_migration(s)
        url = get_ondemand_file_url(response=home)
        js = bs4.BeautifulSoup(s.get(url, timeout=30).content, "html.parser")
        _ct = ClientTransaction(home_page_response=home, ondemand_file_response=js)
        sync.log("transaction id generator: ready")
    except Exception as e:  # noqa: BLE001
        _ct = None
        sync.log(f"transaction id generator: NOT available ({type(e).__name__}: {e})")


def search_page(x, query, cursor=None, count=40):
    """One page of search results as (tweet results, next cursor). Same request shape as
    sync.XClient.gql, plus the transaction-id header and a readable error on failure."""
    variables = {
        "rawQuery": query,
        "count": count,
        "querySource": "typed_query",
        "product": "Latest",
    }
    if cursor:
        variables["cursor"] = cursor
    path = f"/i/api/graphql/{x.ids['SearchTimeline']}/SearchTimeline"
    for _ in range(4):
        headers = {}
        if _ct is not None:
            headers["x-client-transaction-id"] = _ct.generate_transaction_id(
                method="GET", path=path
            )
        r = x.s.get(
            "https://x.com" + path,
            params={
                "variables": sync.json.dumps(variables, separators=(",", ":")),
                "features": sync.json.dumps(x.features, separators=(",", ":")),
                "fieldToggles": sync.json.dumps({"withArticlePlainText": False}),
            },
            headers=headers,
            timeout=30,
        )
        if r.status_code != 200:
            raise RuntimeError(
                f"SearchTimeline HTTP {r.status_code} "
                f"(transaction id sent: {'yes' if _ct is not None else 'no'}) "
                f"body: {r.text[:200]!r}"
            )
        data = r.json()
        errs = data.get("errors") or []
        missing = []
        for e in errs:
            m = sync.re.search(r"features cannot be null: ([\w, ]+)", e.get("message", ""))
            if m:
                missing += [k.strip() for k in m.group(1).split(",")]
        if missing:  # X names the feature flags it wants; add them and retry
            for k in missing:
                x.features[k] = True
            continue
        if errs and not data.get("data"):
            raise RuntimeError(f"SearchTimeline: {errs[0].get('message')}")
        return list(sync.iter_tweets(data)), sync.find_cursor(data)
    raise RuntimeError("SearchTimeline: gave up after retries")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--accounts", default="", help="comma separated; empty = every account in accounts.json")
    ap.add_argument("--pages", type=int, default=10, help="search pages per account (~20-40 tweets each)")
    ap.add_argument("--until", default="", help="only tweets before this date, YYYY-MM-DD")
    ap.add_argument("--dry", action="store_true", help="print what would be added, change nothing")
    args = ap.parse_args()

    rd_token = os.environ.get("RAINDROP_TOKEN", "")
    x_auth = os.environ.get("X_AUTH_TOKEN", "")
    x_ct0 = os.environ.get("X_CT0", "")
    if not (rd_token and x_auth and x_ct0):
        sys.exit("Set RAINDROP_TOKEN, X_AUTH_TOKEN and X_CT0")

    cfg = sync.load_json(sync.CONFIG_PATH, {})
    priority = cfg.get("priority", [])
    priority_set = {a.lower() for a in priority}
    collection_id = os.environ.get("RAINDROP_COLLECTION_ID") or cfg.get("collection_id")

    wanted = [a.strip().lstrip("@") for a in args.accounts.split(",") if a.strip()]
    if wanted:
        accounts = wanted
    else:
        accounts, listed = [], set()
        for a in priority + cfg.get("accounts", []):
            if a.lower() not in listed:
                listed.add(a.lower())
                accounts.append(a)

    known_ids = sync.bookmarked_tweet_ids(rd_token)
    sync.log(f"{len(known_ids)} tweets already bookmarked in Raindrop")

    x = sync.XClient(x_auth, x_ct0)
    x.ids["SearchTimeline"] = SEARCH_QUERY_ID  # no bundle discovery: it finds nothing anyway
    init_transaction_ids()

    total_added = 0
    failures = 0
    for name in accounts:
        query = f"from:{name} filter:native_video"
        if args.until:
            query += f" until:{args.until}"
        try:
            collected, cursor, empty, pages_done = {}, None, 0, 0
            while pages_done < args.pages and empty <= EMPTY_PAGES_ALLOWED:
                batch, nxt = search_page(x, query, cursor)
                pages_done += 1
                got = 0
                for r in batch:
                    lg = r.get("legacy") or {}
                    if "retweeted_status_result" in lg:
                        continue
                    t = sync.parse_tweet(r, name)
                    if t:
                        collected[t["id"]] = t
                        got += 1
                empty = 0 if got else empty + 1
                if not nxt or nxt == cursor:
                    break
                cursor = nxt
                time.sleep(4)  # search limits are tighter than timeline limits

            new = [t for t in sorted(collected.values(), key=lambda t: int(t["id"]))
                   if t["id"] not in known_ids]
            oldest = min((t["created"] for t in collected.values() if t["created"]), default="-")
            sync.log(f"@{name}: {pages_done} pages, {len(collected)} video tweets found "
                     f"(oldest {oldest[:10]}), {len(new)} new")

            if new and not args.dry:
                sync.post_to_raindrop(
                    [sync.to_raindrop_item(t, name, collection_id, name.lower() in priority_set)
                     for t in new],
                    rd_token,
                )
                known_ids.update(t["id"] for t in new)
            elif new:
                for t in new[:5]:
                    sync.log(f"  would add {t['url']}")
                if len(new) > 5:
                    sync.log(f"  ... and {len(new) - 5} more")
            total_added += len(new)
        except Exception as e:  # noqa: BLE001
            failures += 1
            msg = str(e)
            sync.log(f"@{name}: FAILED - {msg}")
            if "HTTP 429" in msg:
                sync.log("rate limited: stopping here. Run again later with fewer accounts or pages.")
                break
            if "HTTP 404" in msg or "HTTP 403" in msg:
                sync.log("  hint: 404 usually means a missing/invalid x-client-transaction-id, "
                         "less often a changed query id.")
                if failures >= 2:
                    break
        time.sleep(6)

    sync.log(f"done: {total_added} {'would be ' if args.dry else ''}added, {failures} failed")
    if accounts and failures == len(accounts):
        sys.exit(1)


if __name__ == "__main__":
    main()
