#!/usr/bin/env python3
"""Summarise how a player plays, from Human Autopilot recordings (numpad 8 in game).

Usage:  python tools/analyze_recording.py [--bot] [file-or-folder ...]
        (default: %APPDATA%/.minecraft/humanautopilot-recordings)

Each recording is one player's: rec-<time>-you.jsonl.gz is yours, rec-<time>-bot.jsonl.gz the bot's (a new file
starts whenever the other one takes over). A folder gives your files only; --bot gives the bot's instead. Older files
(no -you/-bot) are read up to where the bot's building started being recorded in them.

Prints the numbers the bot is tuned from: camera turn speeds / overshoot / settling, how often the view
changes direction, where the eyes go while walking (gaze height, glances), key habits (sprint, jump, sneak),
hotbar layout, when you eat, what you do at night, how you fight, how often you peek in the inventory,
and how the cursor moves in menus. Also writes <file>.summary.json next to each recording.
"""
import gzip
import json
import math
import os
import statistics as st
import sys
import zlib
from collections import Counter, defaultdict


def read_lines(path):
    """Yield parsed JSON lines; tolerate a recording cut short by a crash."""
    raw = open(path, "rb").read()
    try:
        data = gzip.decompress(raw)
    except Exception:
        data = zlib.decompressobj(16 + zlib.MAX_WBITS).decompress(raw)  # truncated stream: take what's there
    for line in data.decode("utf-8", "replace").splitlines():
        try:
            yield json.loads(line)
        except Exception:
            pass


def r(x, nd=None):
    """round() that survives NaN (no data for that number)."""
    if x is None or (isinstance(x, float) and math.isnan(x)):
        return None
    return round(x, nd) if nd is not None else round(x)


def wrap(d):
    d = (d + 180.0) % 360.0 - 180.0
    return d


def pct(xs, p):
    if not xs:
        return float("nan")
    xs = sorted(xs)
    k = (len(xs) - 1) * p / 100.0
    f, c = math.floor(k), math.ceil(k)
    return xs[f] if f == c else xs[f] + (xs[c] - xs[f]) * (k - f)


def camera(frames):
    """Split the camera trace into 'turns' (moving) and 'rests', like a hand on a mouse."""
    speeds, reversals, turns = [], 0, []
    in_turn, cur, last_dir = False, None, 0
    rest_ms = 0.0
    total_ms = frames[-1][0] - frames[0][0] if len(frames) > 1 else 0
    for a, b in zip(frames, frames[1:]):
        if a[3] >= 0 or b[3] >= 0:  # in a menu: camera isn't moving
            in_turn = False
            continue
        dt = (b[0] - a[0]) / 1000.0
        if dt <= 0 or dt > 0.25:
            in_turn = False
            continue
        dy, dp = wrap(b[1] - a[1]), b[2] - a[2]
        dist = math.hypot(dy, dp)
        v = dist / dt  # deg/s
        speeds.append(v)
        if abs(dy) > 0.05:
            d = 1 if dy > 0 else -1
            if last_dir and d != last_dir:
                reversals += 1
            last_dir = d
        if v > 15:  # moving
            if not in_turn:
                in_turn, cur = True, {"start": a[0], "dist": 0.0, "peak": 0.0, "path": []}
            cur["dist"] += dist
            cur["peak"] = max(cur["peak"], v)
            cur["path"].append((dy, dp))
            cur["end"] = b[0]
        else:
            rest_ms += dt * 1000
            if in_turn:
                in_turn = False
                turns.append(cur)
    big = [t for t in turns if t["dist"] > 10]
    # Overshoot: in a big turn, how far it went past where it ended up (sum of steps opposite to the net direction).
    overs = []
    for t in big:
        ny = sum(p[0] for p in t["path"])
        if abs(ny) < 5:
            continue
        back = sum(-p[0] for p in t["path"] if (p[0] > 0) != (ny > 0))
        overs.append(back / abs(ny))
    mins = max(total_ms / 60000.0, 1e-6)
    return {
        "minutes": r(mins, 1),
        "turns_per_min": r(len(turns) / mins, 1),
        "big_turns_per_min": r(len(big) / mins, 1),
        "turn_peak_speed_deg_s": {p: r(pct([t["peak"] for t in big], p), 1) for p in (25, 50, 75, 95)},
        "turn_duration_ms": {p: r(pct([t["end"] - t["start"] for t in big], p)) for p in (25, 50, 75, 95)},
        "turn_size_deg": {p: r(pct([t["dist"] for t in big], p), 1) for p in (25, 50, 75, 95)},
        "overshoot_fraction_median": r(pct(overs, 50), 3) if overs else None,
        "yaw_reversals_per_min": r(reversals / mins, 1),
        "resting_share": r(rest_ms / max(total_ms, 1), 2),
        "speed_deg_s": {p: r(pct(speeds, p), 1) for p in (50, 90, 99)},
    }


def cursor(frames):
    """Cursor moves in menus: glide speed and how long it rests before a click."""
    moves, cur = [], None
    for a, b in zip(frames, frames[1:]):
        if a[3] < 0 or b[3] < 0:
            cur = None
            continue
        dt = (b[0] - a[0]) / 1000.0
        if dt <= 0:
            continue
        d = math.hypot(b[3] - a[3], b[4] - a[4])
        if d / dt > 40:
            if cur is None:
                cur = {"t0": a[0], "dist": 0.0, "peak": 0.0}
            cur["dist"] += d
            cur["peak"] = max(cur["peak"], d / dt)
            cur["t1"] = b[0]
        elif cur is not None:
            if cur["dist"] > 8:
                moves.append(cur)
            cur = None
    return {
        "moves": len(moves),
        "glide_ms": {p: r(pct([m["t1"] - m["t0"] for m in moves], p)) for p in (25, 50, 75)},
        "glide_dist_px": {p: r(pct([m["dist"] for m in moves], p)) for p in (25, 50, 75)},
        "peak_px_s": {p: r(pct([m["peak"] for m in moves], p)) for p in (50, 90)},
    }


def sprint_by_speed(ticks):
    """Share of forward-moving ticks at walking / sprinting / sprint-jumping speed (works on any recording)."""
    walk = run = hop = 0
    for a, b in zip(ticks, ticks[1:]):
        dt = (b["ms"] - a["ms"]) / 1000
        if not (0 < dt < 0.08) or not (b["k"] & 1):
            continue
        sp = ((b["x"] - a["x"]) ** 2 + (b["z"] - a["z"]) ** 2) ** 0.5 / dt
        if sp > 20 or sp < 1:  # teleport / standing
            continue
        if sp < 4.8: walk += 1
        elif sp < 6.2: run += 1
        else: hop += 1
    tot = max(1, walk + run + hop)
    return {"walk": round(walk / tot, 2), "sprint": round(run / tot, 2), "sprint_jump": round(hop / tot, 2)}


def habits(ticks, events, sits):
    n = max(len(ticks), 1)
    bit = lambda m: sum(1 for t in ticks if t["k"] & m) / n
    toggles = sum(1 for a, b in zip(ticks, ticks[1:]) if (a["k"] & 15) != (b["k"] & 15))
    mins = n / 1200.0
    slot_items = defaultdict(Counter)
    for e in events:
        if e["kind"] == "slot":
            slot_items[e["data"]["to"]][e["data"]["item"]] += 1
    layout = {s: c.most_common(2) for s, c in sorted(slot_items.items())}
    switches = sum(1 for e in events if e["kind"] == "slot")
    screens = Counter(e["data"] for e in events if e["kind"] == "screen" and not str(e["data"]).startswith("closed"))
    # Eating: hunger when a food item count went down while not in a menu.
    foods = {"bread", "cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton", "apple", "baked_potato", "carrot",
             "cooked_cod", "cooked_salmon", "beef", "porkchop", "chicken", "mutton", "rabbit", "cooked_rabbit", "sweet_berries", "melon_slice"}
    eat_at = []
    last_food = None
    tick_at = {}
    for t in ticks:
        tick_at[int(t["ms"] // 250)] = t["food"]
    for e in events:
        if e["kind"] == "items":
            for item, d in e["data"].items():
                if item in foods and d == -1:
                    f = tick_at.get(int(e["ms"] // 250)) or tick_at.get(int(e["ms"] // 250) - 1)
                    if f is not None:
                        eat_at.append(f)
    dark = [s for s in sits if s["dark"]]
    under = lambda s: s["y"] < s["surfaceY"] - 2
    return {
        "minutes_played": r(mins, 1),
        "share_walking": r(bit(1), 2),
        # Newer recordings log the body state ("st"); older ones only the sprint key, which misses double-tap-W.
        "share_sprinting": r(sum(1 for t in ticks if t.get("st", 0) & 1) / n, 2) if any("st" in t for t in ticks) else None,
        "share_sprinting_by_speed": sprint_by_speed(ticks),
        "share_in_water": r(sum(1 for t in ticks if t.get("st", 0) & 2) / n, 3) if any("st" in t for t in ticks) else None,
        "lowest_air_before_surfacing": min((t["air"] for t in ticks if "air" in t), default=None),
        "minutes_asleep": r(sum(1 for t in ticks if t.get("st", 0) & 8) / 1200, 1),
        "share_sneaking": r(bit(32), 2),
        "jumps_per_min": r(sum(1 for a, b in zip(ticks, ticks[1:]) if (b["k"] & 16) and not (a["k"] & 16)) / max(mins, 1e-6), 1),
        "move_key_toggles_per_min": r(toggles / max(mins, 1e-6), 1),
        "hotbar_switches_per_min": r(switches / max(mins, 1e-6), 1),
        "hotbar_layout": {str(k): v for k, v in layout.items()},
        "screens_opened": dict(screens.most_common(8)),
        "eats_at_hunger": {"median": pct(eat_at, 50), "min": min(eat_at) if eat_at else None, "max": max(eat_at) if eat_at else None, "count": len(eat_at)},
        "night_share_underground": r(sum(1 for s in dark if under(s)) / len(dark), 2) if dark else None,
        "day_share_underground": r(sum(1 for s in sits if not s["dark"] and under(s)) / max(1, sum(1 for s in sits if not s["dark"])), 2),
        "deaths": sum(1 for e in events if e["kind"] == "death"),
        "hurt_by": dict(Counter(e["data"]["by"] for e in events if e["kind"] == "hurt").most_common(6)),
    }


def walking_eyes(frames, ticks):
    """Where the eyes go while walking (W held, not mining): gaze height, and glances away and back."""
    import bisect
    fms = [f[0] for f in frames]
    if not fms:
        return {}
    def cam(ms):
        i = min(max(bisect.bisect_left(fms, ms), 0), len(frames) - 1)
        return frames[i]
    segs, cur, pitch = [], [], []
    for t in ticks:
        f = cam(t["ms"])
        if f[3] < 0 and (t["k"] & 1) and not (t["k"] & 128):
            pitch.append(f[2])
            cur.append((t["ms"], f[1]))
        elif cur:
            segs.append(cur)
            cur = []
    if cur:
        segs.append(cur)
    walk_s = sum(s[-1][0] - s[0][0] for s in segs if len(s) > 1) / 1000
    glances = []
    for s in segs:
        j = 0
        while j < len(s):
            ms0, y0 = s[j]
            k, found = j + 1, None
            while k < len(s) and s[k][0] - ms0 < 1000:
                if abs(wrap(s[k][1] - y0)) > 25:
                    found = k
                    break
                k += 1
            if found is None:
                j += 1
                continue
            peak, ret, m = 0, None, found
            while m < len(s) and s[m][0] - ms0 < 4000:
                d = abs(wrap(s[m][1] - y0))
                peak = max(peak, d)
                if d < 12:
                    ret = m
                    break
                m += 1
            if ret is not None:
                glances.append((peak, (s[ret][0] - ms0) / 1000))
                j = ret + 1
            else:
                j = found + 5
    per_min = len(glances) / (walk_s / 60) if walk_s > 0 else None
    return {
        "walking_seconds": r(walk_s),
        "gaze_pitch_while_walking": {p: r(pct(pitch, p)) for p in (10, 25, 50, 75, 90)},
        "glances_per_walking_min": r(per_min, 1),
        "glance_size_deg": {p: r(pct([g[0] for g in glances], p)) for p in (25, 50, 75)},
        "glance_there_and_back_s": {p: r(pct([g[1] for g in glances], p), 2) for p in (25, 50, 75)},
    }


def fighting(ticks, events):
    """How mobs get hit: distance, time between hits, W held / let go, backing off, jump crits."""
    import bisect
    tms = [t["ms"] for t in ticks]
    hits = [e for e in events if e["kind"] == "attack" and isinstance(e["data"], dict) and "mob" in e["data"]]
    if not hits or not tms:
        return {"hits": 0}
    w_held = let_go = backs = airborne = 0
    for h in hits:
        i = min(bisect.bisect_left(tms, h["ms"]), len(ticks) - 1)
        t, nxt = ticks[i], ticks[i:i + 10]
        w_held += bool(t["k"] & 1)
        let_go += bool((t["k"] & 1) and any(not (x["k"] & 1) for x in nxt))
        backs += bool(any(x["k"] & 2 for x in nxt))
        airborne += not t["ground"]
    gaps = [b["ms"] - a["ms"] for a, b in zip(hits, hits[1:]) if b["ms"] - a["ms"] < 3000]
    n = len(hits)
    return {
        "hits": n,
        "mobs": dict(Counter(h["data"]["mob"] for h in hits).most_common(6)),
        "hit_distance": {p: r(pct([h["data"]["d"] for h in hits], p), 1) for p in (25, 50, 75)},
        "ms_between_hits": {p: r(pct(gaps, p)) for p in (25, 50, 75)},
        "share_w_held": r(w_held / n, 2),
        "share_w_let_go_after": r(let_go / n, 2),
        "share_backed_off_after": r(backs / n, 2),
        "share_airborne": r(airborne / n, 2),
    }


def bag_peeks(events, minutes):
    """Inventory opened just to look (nothing crafted or moved in, closed within 5 s)."""
    peeks, opened, gained = [], None, False
    for e in events:
        if e["kind"] == "screen" and e["data"] == "InventoryScreen":
            opened, gained = e["ms"], False
        elif e["kind"] == "items" and opened is not None and any(v > 0 for v in e["data"].values()):
            gained = True
        elif e["kind"] == "screen" and str(e["data"]).startswith("closed InventoryScreen") and opened is not None:
            dur = (e["ms"] - opened) / 1000
            if not gained and dur < 5:
                peeks.append(dur)
            opened = None
    return {"per_min": r(len(peeks) / max(minutes, 1e-6), 2), "seconds": {p: r(pct(peeks, p), 1) for p in (25, 50, 75)}}


def hands(frames, ticks, events, minutes):
    """v2 recordings: how blocks get broken and placed, menu clicks, the recipe book, the hotbar."""
    ev = lambda k: [e["data"] for e in events if e["kind"] == k and isinstance(e["data"], dict)]
    broke, placed, clicks, recipes, slots = ev("broke"), ev("place"), ev("click"), ev("recipe"), ev("slot")
    if not (broke or placed or clicks):
        return {}
    out = {}
    if broke:
        by_tool = defaultdict(list)
        for b in broke:
            if b.get("ms"):
                by_tool[b.get("tool", "?")].append(b["ms"])
        rel_y = Counter((b.get("rel") or [0, 0, 0])[1] for b in broke)
        switches = sum(1 for a, b in zip(broke, broke[1:]) if a.get("tool") != b.get("tool"))
        hits = [b["hit"] for b in broke if "hit" in b]
        out["mining"] = {
            "blocks_per_min": r(len(broke) / max(minutes, 1e-6), 1),
            "break_ms_by_tool": {t: r(pct(v, 50)) for t, v in sorted(by_tool.items(), key=lambda kv: -len(kv[1]))[:6]},
            "block_height_vs_feet": {str(k): v for k, v in sorted(rel_y.items())},
            "tool_changes_per_100_blocks": r(100 * switches / max(1, len(broke) - 1), 1),
            "share_moving_while_mining": r(sum(1 for b in broke if b.get("moving")) / len(broke), 2),
            "share_sneaking_while_mining": r(sum(1 for b in broke if b.get("sneak")) / len(broke), 2),
            "pitch_when_mining": {p: r(pct([b["pitch"] for b in broke if "pitch" in b], p)) for p in (10, 50, 90)},
            "aim_spot_on_face_spread": r(st.pstdev([h[0] for h in hits]) if len(hits) > 2 else None, 2),
        }
    if placed:
        torches = [p for p in placed if "torch" in p.get("item", "")]
        out["placing"] = {
            "per_min": r(len(placed) / max(minutes, 1e-6), 1),
            "items": dict(Counter(p.get("item") for p in placed).most_common(8)),
            "share_sneaking": r(sum(1 for p in placed if p.get("sneak")) / len(placed), 2),
            "share_at_an_edge": r(sum(1 for p in placed if p.get("edge")) / len(placed), 2),
            "share_while_airborne": r(sum(1 for p in placed if not p.get("ground", True)) / len(placed), 2),
            "torch_block_light_before": {p: r(pct([t.get("blockLight", 0) for t in torches], p)) for p in (25, 50, 75)} if torches else None,
        }
    if clicks or recipes:
        out["crafting_and_menus"] = {
            "slot_clicks_by_type": dict(Counter(c.get("type") for c in clicks).most_common()),
            "recipe_book_clicks": len(recipes),
            "recipe_book_shift_share": r(sum(1 for x in recipes if x.get("shift")) / len(recipes), 2) if recipes else None,
            "recipe_results": dict(Counter(x.get("result") for x in recipes).most_common(10)),
        }
    if slots and any("via" in x for x in slots):
        out["hotbar"] = {"share_wheel": r(sum(1 for x in slots if x.get("via") == "wheel") / len(slots), 2)}
    hits_ = ev("hit")
    if hits_:
        out["hits"] = {
            "count": len(hits_),
            "share_crit": r(sum(1 for h in hits_ if h.get("crit")) / len(hits_), 2),
            "share_sprinting": r(sum(1 for h in hits_ if h.get("sprint")) / len(hits_), 2),
            "charge_median": r(pct([h.get("charge", 1) for h in hits_], 50), 2),
            "distance_median": r(pct([h.get("d", 0) for h in hits_], 50), 1),
        }
    drops = ev("drop")
    if drops:
        out["drops"] = dict(Counter(d.get("item") for d in drops).most_common(8))
    return out


def ears_and_eyes(frames, events):
    """v2: after a sound off to the side, do they turn toward it, and how fast? Which seen things do they look at?"""
    import bisect
    if not frames:
        return {}
    fms = [f[0] for f in frames]
    def view_at(ms):
        i = min(max(bisect.bisect_left(fms, ms), 0), len(frames) - 1)
        return frames[i]
    def angle_to(f, rel):
        yaw = math.degrees(math.atan2(rel[2], rel[0])) - 90.0
        pitch = -math.degrees(math.atan2(rel[1] - 1.6, math.hypot(rel[0], rel[2])))
        return math.hypot(wrap(yaw - f[1]), pitch - f[2])
    sounds = [e for e in events if e["kind"] == "sound" and isinstance(e["data"], dict)]
    turned, times, considered = 0, [], 0
    by_kind = Counter()
    for e in sounds:
        d = e["data"]
        if d.get("off", 0) < 45:
            continue
        considered += 1
        start = angle_to(view_at(e["ms"]), d["rel"])
        for k in range(1, 40):
            ms = e["ms"] + k * 50
            f = view_at(ms)
            if f[3] >= 0:
                break
            if angle_to(f, d["rel"]) < max(25, start * 0.35):
                turned += 1
                times.append(k * 50)
                by_kind[d["id"].split(".")[1] if "." in d["id"] else d["id"]] += 1
                break
    seen = [e for e in events if e["kind"] == "seen" and isinstance(e["data"], dict)]
    looked, shown = Counter(), Counter()
    for e in seen:
        for m in e["data"].get("mobs", []) + e["data"].get("ores", []):
            kind = m[0]
            shown[kind] += 1
            if m[-1] < 15:
                looked[kind] += 1
    top = sorted(shown, key=lambda k: -shown[k])[:10]
    return {
        "sounds_off_to_the_side": considered,
        "share_turned_to_look": r(turned / considered, 2) if considered else None,
        "turn_reaction_ms": {p: r(pct(times, p)) for p in (25, 50, 75)} if times else None,
        "turned_for": dict(by_kind.most_common(8)),
        "in_view_share_by_thing": {k: r(looked[k] / shown[k], 2) for k in top},
    }


def analyse(path):
    header, frames, ticks, events, sits = None, [], [], [], []
    for o in read_lines(path):
        # An older file (no "who" in it): the bot's building was recorded on into it - that part isn't the player's.
        if "e" in o and header is not None and "who" not in header and o["e"].get("kind") == "bot"                 and o["e"].get("data") == "recording the builder":
            break
        if "f" in o:
            frames.append(o["f"])
        elif "t" in o:
            ticks.append(o["t"])
        elif "e" in o:
            events.append(o["e"])
        elif "s" in o:
            sits.append(o["s"])
        elif "h" in o:
            header = o["h"]
    out = {"file": os.path.basename(path), "who": (header or {}).get("who", "you (older file)"), "settings": header,
           "frames": len(frames)}
    if len(frames) > 10:
        out["camera"] = camera(frames)
        out["menu_cursor"] = cursor(frames)
    if ticks:
        out["habits"] = habits(ticks, events, sits)
        out["habits"]["inventory_peeks"] = bag_peeks(events, len(ticks) / 1200.0)
        out["fighting"] = fighting(ticks, events)
        if len(frames) > 10:
            out["walking_eyes"] = walking_eyes(frames, ticks)
        h = hands(frames, ticks, events, len(ticks) / 1200.0)
        if h:
            out["hands"] = h
            out["ears_and_eyes"] = ears_and_eyes(frames, events)
    return out


def main(args):
    bot = "--bot" in args
    args = [a for a in args if a != "--bot"]
    if not args:
        args = [os.path.join(os.environ.get("APPDATA", ""), ".minecraft", "humanautopilot-recordings")]
    files = []
    for a in args:
        if os.path.isdir(a):
            # Yours (-you, or older untagged ones), or with --bot the bot's (-bot).
            files += sorted(os.path.join(a, f) for f in os.listdir(a) if f.endswith(".jsonl.gz")
                            and f.endswith("-bot.jsonl.gz") == bot)
        else:
            files.append(a)
    if not files:
        print("No recordings of the bot found." if bot else "No recordings found. Press numpad 8 in game to record.")
        return
    for f in files:
        s = analyse(f)
        with open(f + ".summary.json", "w", encoding="utf-8") as fh:
            json.dump(s, fh, indent=2)
        print(json.dumps(s, indent=2))


if __name__ == "__main__":
    main(sys.argv[1:])
