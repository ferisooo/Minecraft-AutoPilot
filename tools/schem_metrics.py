"""Compare schematic test runs: python tools/schem_metrics.py run-a.log [run-b.log ...]"""
import re, sys, collections

def far_outcomes(lines):
    pending = {}
    out = collections.Counter()
    for l in lines:
        m = re.search(r'\[box\] STALL (\d+)s: .*\| target (-?\d+, -?\d+, -?\d+) .*\| flying over to the next block', l)
        if m:
            pending.setdefault(m.group(2), True)
            continue
        if not pending: continue
        for t in list(pending):
            if t not in l: continue
            r = None
            if 'skipping ' + t in l: r = 'skip: ' + l.split('for now: ')[-1][:30]
            elif 'leaving ' + t in l: r = 'left'
            elif 'slow block ' + t in l: r = 'PLACED'
            elif "isn't to do any more" in l: r = 'gone'
            elif 'not flying over to ' + t in l: r = 'not flown (seen from afar)'
            if r:
                out[r] += 1
                del pending[t]
    return out

def metrics(path):
    lines = open(path, encoding='utf-8', errors='replace').read().splitlines()
    m = collections.OrderedDict()
    res = [l for l in lines if 'REAL RESULT' in l]
    if res:
        r = res[-1]
        mm = re.search(r'REAL RESULT: (\d+)/(\d+) in (\d+) s \((\d+) blocks/min\), placed (\d+) skipped (\d+) stalls (\d+), worst tick (\d+) ms, slow ticks (\d+)', r)
        if mm:
            m['done'] = mm.group(1); m['secs'] = mm.group(3); m['placed'] = mm.group(5); m['per min'] = mm.group(4)
            m['skipped'] = mm.group(6); m['worst tick ms'] = mm.group(8); m['slow ticks'] = mm.group(9)
    land = [l for l in lines if 'REAL land' in l]
    if land: m['land'] = re.sub(r'.*REAL land: ', '', land[0])[:80]
    m['STALL-s'] = sum(1 for l in lines if '[box] STALL' in l)
    m['confused'] = sum(1 for l in lines if '[confused]' in l)
    m['no spot'] = sum(1 for l in lines if '[box] no spot for' in l)
    m['long ticks'] = sum(1 for l in lines if '[box] long tick' in l)
    # the four the user named: stalls (above), back and forth, caved in, 1x1 holes / wedging
    m['jumps >12 leaving work'] = sum(1 for l in lines if '[schem] off to ' in l)
    m['shut in / built in'] = sum(1 for l in lines if '[box] shut in at' in l or '[box] built in at' in l)
    m['wedged / cant get into'] = sum(1 for l in lines if 'wedged' in l and '[box]' in l and 'STALL' not in l) + sum(1 for l in lines if "can't get into" in l)
    m['landings'] = sum(1 for l in lines if '[box] stopped flying' in l)
    m['left as is'] = sum(1 for l in lines if '[schem] leaving ' in l)
    pl = [l for l in lines if '[plan] ' in l and ('blocks in' in l or 'not planned' in l)]
    if pl: m['plan'] = re.sub(r'.*\[plan\] ', '', pl[-1])[:140]
    sk = collections.Counter(re.sub(r'.*for now: ', '', l)[:40] for l in lines if '[box] skipping' in l)
    m['skips'] = dict(sk.most_common(5))
    m['not flown (afar)'] = sum(1 for l in lines if 'not flying over to' in l)
    m['blind visits'] = sum(1 for l in lines if 'nothing far off looks workable' in l)
    look = [l for l in lines if 'looking before flying' in l]
    tot = [0, 0, 0, 0]
    for l in look:
        mm = re.search(r'(\d+) far spots looked over \((\d+) workable from the sky, (\d+) not\), (\d+) passed over', l)
        if mm:
            for i in range(4): tot[i] += int(mm.group(i + 1))
    if look: m['far looks/yes/no, buried checks'] = tot
    m['far flights (3s+)'] = dict(far_outcomes(lines))
    # the creative menu (not in the status lines' "doing" times): visits, blocks got, seconds spent getting them
    got = [int(mm.group(1)) for mm in (re.search(r'\[schem\] got .* in (\d+) ticks', l) for l in lines) if mm]
    kinds = collections.Counter(re.sub(r".*\[schem\] (while the menu's open: )?fetching (.*) into hotbar.*", r'\2', l)
                                for l in lines if 'into hotbar slot' in l)
    m['menu visits/got/extras/secs'] = [sum(1 for l in lines if '[schem] fetching ' in l), len(got),
                                        sum(1 for l in lines if "while the menu's open: fetching" in l), sum(got) // 20]
    m['menu most fetched'] = dict(kinds.most_common(5))
    m['picked off the build'] = sum(1 for l in lines if '[schem] got ' in l and 'off the build' in l)
    tk = collections.Counter()
    for l in lines:
        mm = re.search(r'time in the last 30 s: menu (\d+)s, flying (\d+)s, placing (\d+)s, looking (\d+)s, other (\d+)s, nothing said (\d+)s', l)
        if mm:
            for name, v in zip(('menu', 'flying', 'placing', 'looking', 'other', 'nothing said'), mm.groups()): tk[name] += int(v)
    if tk: m['seconds by kind'] = dict(tk)
    flew = [float(x) for x in re.findall(r'flew (\d+) blocks', ' '.join(l for l in lines if '[status] schematic' in l))]
    if flew and m.get('placed'): m['blocks flown per block placed'] = round(sum(flew) / max(1, int(m['placed'])), 2)
    stall_doing = collections.Counter()
    for l in lines:
        if '[box] STALL' in l:
            mm = re.search(r'\| ([^|]+) \| last steer', l)
            if mm: stall_doing[re.sub(r' \(.*', '', mm.group(1)).strip()] += 1
    m['stall doing'] = dict(stall_doing.most_common(4))
    # time per activity from the status lines
    act = collections.Counter()
    for l in lines:
        if '[status] schematic' in l and 'doing:' in l:
            for part in l.split('doing: ')[1].split(', '):
                mm = re.match(r'(.*) (\d+)s$', part.strip())
                if mm:
                    k = re.sub(r' \(.*', '', mm.group(1))
                    k = re.sub(r'^(placing|knocking out|taking out) .*', r'\1 X', k)
                    act[k] += int(mm.group(2))
    t = sum(act.values()) or 1
    m['time'] = ', '.join('%s %d%%' % (k, 100 * v // t) for k, v in act.most_common(5))
    return m

if __name__ == '__main__':
    for p in sys.argv[1:]:
        print('==', p)
        for k, v in metrics(p).items():
            print('  %-32s %s' % (k, v))
