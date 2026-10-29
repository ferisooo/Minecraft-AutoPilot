"""A short report on a schematic-building session from a game log (the user's latest.log or a test's run-*.log).

    python tools/live_report.py [log] [--since HH:MM]

Prints: placed per minute, stall episodes (how many, how long, what it was doing), confused bursts, skips by reason,
landings by cause, shut-in / built-in, far flights, menu use. Default log: the user's .minecraft/logs/latest.log.
"""
import collections
import os
import re
import sys


def main():
    argv = sys.argv[1:]
    since = None
    if '--since' in argv:
        i = argv.index('--since')
        since = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    args = [a for a in argv if not a.startswith('--')]
    path = args[0] if args else os.path.expanduser(r'~\AppData\Roaming\.minecraft\logs\latest.log')
    lines = open(path, encoding='utf-8', errors='replace').read().splitlines()
    if since:
        lines = [l for l in lines if re.match(r'^\[(\d\d:\d\d)', l) and l[1:6] >= since]
    t = lambda l: l[1:9]
    status = [l for l in lines if '[status] schematic' in l]
    placed = [int(m.group(1)) for m in (re.search(r'\| \+(\d+) placed', l) for l in status) if m]
    print(f'{path}: {len(lines)} lines, {t(lines[0]) if lines else "-"} .. {t(lines[-1]) if lines else "-"}')
    if placed:
        print(f'  placed: {sum(placed)} in {len(placed) / 2:.0f} min ({2 * sum(placed) / len(placed):.0f}/min)')
    builds = collections.OrderedDict()
    for l in status:
        m = re.search(r'\[status\] schematic (.+?) \| (\d+)/(\d+)', l)
        if m:
            builds.setdefault(m.group(1), [m.group(2), m.group(3)])[0:1] = builds.get(m.group(1), [m.group(2)])[0:1]
            builds[m.group(1)].append(m.group(2))
    for name, v in builds.items():
        print(f'  build {name}: {v[0]} -> {v[-1]} of {v[1]}')
    # stall episodes: consecutive "STALL Ns" lines with a rising count
    eps, cur = [], None
    for l in lines:
        m = re.search(r'\[box\] STALL (\d+)s', l)
        if not m:
            continue
        n = int(m.group(1))
        if cur and n > cur[0]:
            cur[0], cur[2] = n, l
        else:
            if cur:
                eps.append(cur)
            cur = [n, l, l]
    if cur:
        eps.append(cur)
    doing = collections.Counter()
    for n, first, last in eps:
        m = re.search(r'\| ([a-z][^|]*?) \| last steer', last)
        doing[re.sub(r' \(.*', '', m.group(1)) if m else '?'] += n
    print(f'  stall episodes: {len(eps)} ({sum(e[0] for e in eps)} s), >=15 s: {sum(1 for e in eps if e[0] >= 15)}, '
          f'>=30 s: {sum(1 for e in eps if e[0] >= 30)}, >=60 s: {sum(1 for e in eps if e[0] >= 60)}')
    print(f'    seconds by what it was doing: {dict(doing.most_common(5))}')
    for n, first, last in sorted(eps, key=lambda e: -e[0])[:3]:
        m = re.search(r'STALL (\d+)s: at (\S+ \S+ \S+).*?steering to ([^|]*?), low.*?\| ([a-z][^|]*?) \| last steer', last)
        if m:
            print(f'    {t(last)} {m.group(1)} s at {m.group(2)} -> {m.group(3)[:40]} | {m.group(4)}')
    conf = [l for l in lines if '[confused]' in l]
    cd = collections.Counter(re.sub(r'.*\(([^)]*)\): .*', r'\1', l)[:40] for l in conf)
    print(f'  confused bursts: {len(conf)} {dict(cd.most_common(3))}')
    sk = collections.Counter(re.sub(r'.*for now: ', '', l)[:40] for l in lines if '[box] skipping' in l)
    print(f'  skips: {dict(sk.most_common(5))}')
    land = collections.Counter()
    for l in lines:
        if '[box] stopped flying' in l:
            m = re.search(r'last down: ([a-z -]+?)(?: \(|, \d+ ticks)', l)
            land[m.group(1) if m else '?'] += 1
    print(f'  landings: {sum(land.values())} {dict(land.most_common(5))}')
    print(f'  shut in: {sum(1 for l in lines if "[box] shut in at" in l)}, built in: {sum(1 for l in lines if "[box] built in at" in l)}, '
          f'moved out by hand: {sum(1 for l in lines if "out by hand" in l)}, /tp up: {sum(1 for l in lines if "/tp up" in l)}')
    got = [int(m.group(1)) for m in (re.search(r'\[schem\] got .* in (\d+) ticks', l) for l in lines) if m]
    print(f'  menu: {sum(1 for l in lines if "[schem] fetching " in l)} visits, {len(got)} blocks got ({sum(got) // 20} s), '
          f'{sum(1 for l in lines if "off the build" in l and "[schem] got" in l)} picked off the build')
    print(f'  couldn\'t fly there: {sum(1 for l in lines if "couldn" in l and "fly there" in l and "skipping" in l)}, '
          f'nowhere to place it from: {sum(1 for l in lines if "nowhere to place it from" in l and "skipping" in l)}')


if __name__ == '__main__':
    main()
