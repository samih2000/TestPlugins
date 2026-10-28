#!/usr/bin/env python3
"""Saves new video tweets from a list of X accounts as Raindrop.io bookmarks.

Each bookmark gets: link = tweet URL, title = tweet text, excerpt = tweet text,
cover = video thumbnail, note = direct mp4 URL(s) (one per line),
tags = [account, "xsync"]. The CloudStream "Raindrop Feed" plugin reads exactly these fields.

Env vars: RAINDROP_TOKEN, X_AUTH_TOKEN, X_CT0
Usage:    python sync.py [--dry] [--limit N]
"""
import argparse
import json
import os
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import requests

HERE = Path(__file__).parent
CONFIG_PATH = HERE / "accounts.json"
STATE_PATH = HERE / "state.json"

# Public web-client bearer token (same one the X website itself uses).
BEARER = (
    "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D"
    "1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
)
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

# X rotates these ids. These are fallbacks; discover_query_ids() tries to read fresh ones
# from the X web bundle first. If both fail, update them by hand (see README notes).
QUERY_IDS = {
    "UserByScreenName": "xc8f1g7BYqr6VTzTbvNlGw",
    "UserMedia": "dexO_2tohK86JDudXXG3Yw",
}

FEATURES = {k: False for k in [
    "hidden_profile_subscriptions_enabled",
    "rweb_tipjar_consumption_enabled",
    "verified_phone_label_enabled",
    "subscriptions_verification_info_is_identity_verified_enabled",
    "subscriptions_verification_info_verified_since_enabled",
    "highlights_tweets_tab_ui_enabled",
    "responsive_web_twitter_article_notes_tab_enabled",
    "tweet_awards_web_tipping_enabled",
]}
FEATURES.update({k: True for k in [
    "responsive_web_graphql_exclude_directive_enabled",
    "creator_subscriptions_tweet_preview_api_enabled",
    "responsive_web_graphql_timeline_navigation_enabled",
    "responsive_web_graphql_skip_user_profile_image_extensions_enabled",
    "communities_web_enable_tweet_community_results_fetch",
    "c9s_tweet_anatomy_moderator_badge_enabled",
    "articles_preview_enabled",
    "responsive_web_edit_tweet_api_enabled",
    "graphql_is_translatable_rweb_tweet_is_translatable_enabled",
    "view_counts_everywhere_api_enabled",
    "longform_notetweets_consumption_enabled",
    "responsive_web_twitter_article_tweet_consumption_enabled",
    "freedom_of_speech_not_reach_fetch_enabled",
    "standardized_nudges_misinfo",
    "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled",
    "longform_notetweets_rich_text_read_enabled",
    "longform_notetweets_inline_media_enabled",
    "responsive_web_enhance_cards_enabled",
    "rweb_video_timestamps_enabled",
]})


def log(msg):
    print(msg, flush=True)


def load_json(path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return default


class XClient:
    def __init__(self, auth_token, ct0):
        self.s = requests.Session()
        self.s.headers.update({
            "authorization": f"Bearer {BEARER}",
            "cookie": f"auth_token={auth_token}; ct0={ct0}",
            "x-csrf-token": ct0,
            "x-twitter-auth-type": "OAuth2Session",
            "x-twitter-active-user": "yes",
            "x-twitter-client-language": "en",
            "user-agent": UA,
        })
        self.features = dict(FEATURES)
        self.ids = dict(QUERY_IDS)

    def discover_query_ids(self):
        """Best effort: read current GraphQL query ids from X's JS bundle."""
        try:
            html = self.s.get("https://x.com", timeout=20).text
            bundles = re.findall(
                r'https://abs\.twimg\.com/responsive-web/client-web[^"\']*?\.js', html
            )
            for url in bundles:
                js = self.s.get(url, timeout=30).text
                for op in self.ids:
                    m = re.search(r'queryId:"([\w-]+)",operationName:"%s"' % op, js)
                    if m:
                        self.ids[op] = m.group(1)
            log(f"query ids: {self.ids}")
        except Exception as e:  # noqa: BLE001
            log(f"query id discovery failed ({e}); using fallbacks")

    def gql(self, op, variables):
        url = f"https://x.com/i/api/graphql/{self.ids[op]}/{op}"
        for _ in range(4):
            params = {
                "variables": json.dumps(variables, separators=(",", ":")),
                "features": json.dumps(self.features, separators=(",", ":")),
                "fieldToggles": json.dumps({"withArticlePlainText": False}),
            }
            r = self.s.get(url, params=params, timeout=30)
            if r.status_code == 429:
                raise RuntimeError("X rate limit (HTTP 429)")
            if r.status_code in (401, 403):
                raise RuntimeError(f"X rejected the cookies (HTTP {r.status_code})")
            if r.status_code == 404:
                raise RuntimeError(f"{op}: query id is stale (HTTP 404)")
            data = r.json()
            errs = data.get("errors") or []
            missing = []
            for e in errs:
                m = re.search(r"features cannot be null: ([\w, ]+)", e.get("message", ""))
                if m:
                    missing += [x.strip() for x in m.group(1).split(",")]
            if missing:  # X told us which feature flags it wants; add them and retry
                for k in missing:
                    self.features[k] = True
                continue
            if errs and not data.get("data"):
                raise RuntimeError(f"{op}: {errs[0].get('message')}")
            return data
        raise RuntimeError(f"{op}: gave up after retries")

    def user_id(self, screen_name):
        data = self.gql("UserByScreenName", {
            "screen_name": screen_name,
            "withSafetyModeUserFields": True,
        })
        rid = data.get("data", {}).get("user", {}).get("result", {}).get("rest_id")
        if not rid:
            raise RuntimeError(f"@{screen_name}: user not found")
        return rid

    def media_tweets(self, user_id, count=40, cursor=None):
        """Returns (tweet results, cursor for the next older page or None)."""
        variables = {
            "userId": user_id,
            "count": count,
            "includePromotedContent": False,
            "withClientEventToken": False,
            "withBirdwatchNotes": False,
            "withVoice": True,
            "withV2Timeline": True,
        }
        if cursor:
            variables["cursor"] = cursor
        data = self.gql("UserMedia", variables)
        return list(iter_tweets(data)), find_cursor(data)


def iter_tweets(node):
    """Walks the timeline JSON and yields every tweet result, whatever the layout."""
    if isinstance(node, dict):
        res = node.get("tweet_results", {}).get("result") if "tweet_results" in node else None
        if isinstance(res, dict):
            if res.get("__typename") == "TweetWithVisibilityResults":
                res = res.get("tweet", res)
            yield res
        for v in node.values():
            yield from iter_tweets(v)
    elif isinstance(node, list):
        for v in node:
            yield from iter_tweets(v)


def find_cursor(node):
    """Finds the 'Bottom' cursor that points at the next (older) page of the timeline."""
    if isinstance(node, dict):
        if node.get("cursorType") == "Bottom" and node.get("value"):
            return node["value"]
        for v in node.values():
            c = find_cursor(v)
            if c:
                return c
    elif isinstance(node, list):
        for v in node:
            c = find_cursor(v)
            if c:
                return c
    return None


def bookmarked_tweet_ids(token):
    """IDs of every tweet already bookmarked in Raindrop (by hand or by this job).

    Read once per run, so nothing you saved yourself is ever added a second time.
    """
    ids, page = set(), 0
    while page < 400:
        r = requests.get(
            "https://api.raindrop.io/rest/v1/raindrops/0",
            headers={"Authorization": f"Bearer {token}"},
            params={"perpage": 50, "page": page},
            timeout=30,
        )
        if r.status_code != 200:
            raise RuntimeError(f"Raindrop HTTP {r.status_code} while listing bookmarks")
        items = r.json().get("items", [])
        if not items:
            break
        for i in items:
            m = re.search(r"status/(\d+)", i.get("link", ""))
            if m:
                ids.add(m.group(1))
        page += 1
        time.sleep(0.6)
    return ids


def parse_tweet(result, username):
    """Returns a dict for tweets that contain video, else None."""
    legacy = result.get("legacy") or {}
    tid = result.get("rest_id") or legacy.get("id_str")
    media = (legacy.get("extended_entities") or {}).get("media") or []
    videos, thumb = [], None
    for m in media:
        if m.get("type") not in ("video", "animated_gif"):
            continue
        variants = [
            v for v in (m.get("video_info") or {}).get("variants", [])
            if v.get("content_type") == "video/mp4" and v.get("url")
        ]
        if not variants:
            continue
        best = max(variants, key=lambda v: v.get("bitrate", 0))
        videos.append(best["url"])
        thumb = thumb or m.get("media_url_https")
    if not tid or not videos:
        return None
    try:
        created = datetime.strptime(
            legacy.get("created_at", ""), "%a %b %d %H:%M:%S %z %Y"
        ).astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    except ValueError:
        created = None
    text = re.sub(r"https?://\S+", "", legacy.get("full_text", "")).strip()
    text = re.sub(r"\s+", " ", text)
    return {
        "id": tid,
        "url": f"https://x.com/{username}/status/{tid}",
        "text": text,
        "videos": videos,
        "thumb": thumb,
        "created": created,
    }


def to_raindrop_item(t, username, collection_id, is_priority=False):
    title = (t["text"][:80] + "…") if len(t["text"]) > 80 else t["text"]
    item = {
        "link": t["url"],
        "title": title or f"@{username}",
        "excerpt": t["text"],
        "note": "\n".join(t["videos"]),
        "tags": [username, "xsync"] + (["priority"] if is_priority else []),
    }
    if t["thumb"]:
        item["cover"] = t["thumb"]
    if t["created"]:
        item["created"] = t["created"]
    if collection_id is not None:
        item["collection"] = {"$id": int(collection_id)}
    return item


def post_to_raindrop(items, token):
    for i in range(0, len(items), 100):
        chunk = items[i:i + 100]
        r = requests.post(
            "https://api.raindrop.io/rest/v1/raindrops",
            headers={"Authorization": f"Bearer {token}"},
            json={"items": chunk},
            timeout=60,
        )
        if r.status_code != 200:
            raise RuntimeError(f"Raindrop HTTP {r.status_code}: {r.text[:200]}")
        time.sleep(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry", action="store_true", help="print what would be added, change nothing")
    ap.add_argument("--limit", type=int, default=None, help="override the first-run backfill size")
    ap.add_argument(
        "--pages", type=int, default=int(os.environ.get("BACKFILL_PAGES") or 0),
        help="history depth: pages of ~40 media tweets per account (0/1 = normal run)",
    )
    args = ap.parse_args()

    cfg = load_json(CONFIG_PATH, {})
    priority = cfg.get("priority", [])
    priority_set = {a.lower() for a in priority}
    accounts, listed = [], set()
    for a in priority + cfg.get("accounts", []):  # priority accounts go first
        if a.lower() not in listed:
            listed.add(a.lower())
            accounts.append(a)
    backfill = args.limit or cfg.get("backfill", 20)
    collection_id = os.environ.get("RAINDROP_COLLECTION_ID") or cfg.get("collection_id")

    rd_token = os.environ.get("RAINDROP_TOKEN", "")
    x_auth = os.environ.get("X_AUTH_TOKEN", "")
    x_ct0 = os.environ.get("X_CT0", "")
    if not (rd_token and x_auth and x_ct0):
        sys.exit("Set RAINDROP_TOKEN, X_AUTH_TOKEN and X_CT0")

    state = load_json(STATE_PATH, {"user_ids": {}, "seen": {}})
    state.setdefault("user_ids", {})
    state.setdefault("seen", {})

    known_ids = bookmarked_tweet_ids(rd_token)
    log(f"{len(known_ids)} tweets already bookmarked in Raindrop")

    x = XClient(x_auth, x_ct0)
    x.discover_query_ids()

    failures = 0
    for name in accounts:
        key = name.lower()
        try:
            uid = state["user_ids"].get(key) or x.user_id(name)
            state["user_ids"][key] = uid
            collected, cursor = {}, None
            for _ in range(max(1, args.pages)):
                batch, nxt = x.media_tweets(uid, cursor=cursor)
                if not batch:
                    break
                for r in batch:
                    t = parse_tweet(r, name)
                    if t:
                        collected[t["id"]] = t
                if not nxt or nxt == cursor:
                    break
                cursor = nxt
                time.sleep(2)
            tweets = sorted(collected.values(), key=lambda t: int(t["id"]))

            deep = args.pages > 1
            first_run = key not in state["seen"]
            seen = set(state["seen"].get(key, []))
            # Anything already in Raindrop (even bookmarked by hand) is never added again.
            skip = seen | known_ids
            new = [t for t in tweets if t["id"] not in skip]
            if first_run and not deep:
                new = new[-backfill:]  # newest N only

            log(f"@{name}: {len(tweets)} video tweets fetched, {len(new)} new")
            if new and not args.dry:
                post_to_raindrop(
                    [to_raindrop_item(t, name, collection_id, key in priority_set) for t in new],
                    rd_token,
                )
            elif new:
                for t in new:
                    log(f"  would add {t['url']}  ({len(t['videos'])} video)")

            if not args.dry:
                seen.update(t["id"] for t in tweets)  # also marks skipped backfill as seen
                state["seen"][key] = sorted(seen, key=int)[-500:]
        except Exception as e:  # noqa: BLE001
            failures += 1
            log(f"@{name}: FAILED - {e}")
        time.sleep(3)  # be gentle between accounts

    if not args.dry:
        STATE_PATH.write_text(json.dumps(state, indent=2), encoding="utf-8")
    if accounts and failures == len(accounts):
        sys.exit(1)


if __name__ == "__main__":
    main()
