"""Watches the user's game log while the autopilot builds and prints one line per problem (stall, confused, flying
around, shut in, no progress), each with the build's percent and the bot's own reasons from the log. Lines also go to
live-report.txt in the project folder. Meant to run under Claude's Monitor (each printed line is an event).

python tools/live_watch.py [log] [--report file]
"""
import os, re, sys, time, collections

LOG = os.path.expandvars(r'%APPDATA%\.minecraft\logs\latest.log')
REPORT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'live-report.txt')
args = sys.argv[1:]
if args and not args[0].startswith('--'): LOG = args.pop(0)
if '--report' in args: REPORT = args[args.index('--report') + 1]

STATUS = re.compile(r'\[status\] schematic (.+?) \| (\d+)/(\d+) \| \+(\d+) placed .*?(\d+) stalls.*?flew (\d+) blocks.*?\| at ([-\d. ]+) \|')
DOING = re.compile(r'doing: (.*)$')
STALL = re.compile(r'\[box\] STALL (\d+)s: at ([-\d. ]+) .*?\| target ([^|]*?) stand')
WHY_LINES = re.compile(r'\[schem\] (off to|building the|the wall round|leaving|not flying over|far look|nothing to place|what\'s left|repairing|winding|before calling|on to |hanging ones|carrying on)|\[stuck\]|\[box\] (can\'t see|shut in|built in|someone)|couldn\'t fly|nowhere to place|\[plan\]')
TIME = re.compile(r'^\[(\d\d:\d\d:\d\d)\]')

state = {'name': None, 'done': 0, 'total': 0, 'pct': 0.0, 'doing': '', 'same': 0, 'stall_said': 0, 'paused': False}
why = collections.deque(maxlen=6)
seen_open = {}  # (each opened spot said once in 2 minutes: the mod logs it every 5 s)


def say(t, text):
    line = '%s %s | %s %.1f%% | %s' % (t, text, state['name'] or '?', state['pct'], state['doing'][:80])
    print(line, flush=True)
    try:
        with open(REPORT, 'a', encoding='utf-8') as f:
            f.write(time.strftime('%Y-%m-%d ') + line + '\n')
            for w in list(why)[-3:]: f.write('        because: ' + w + '\n')
    except OSError:
        pass


def reasons():
    return ' / '.join(w[:110] for w in list(why)[-2:])


def handle(line):
    m = TIME.match(line)
    t = m.group(1) if m else '--:--:--'
    if 'paused (' in line and 'Autopilot' in line: state['paused'] = True
    if 'Building ' in line and 'Autopilot' in line and 'paused' not in line: state['paused'] = False
    msg = line.split(': ', 1)[-1].strip()
    if WHY_LINES.search(line) and 'STALL' not in line: why.append(t + ' ' + msg)
    m = STATUS.search(line)
    if m:
        name, done, total, placed, stalls, flew, at = m.groups()
        done, total, placed, flew = int(done), int(total), int(placed), int(flew)
        if name != state['name']: state['same'] = 0
        state['same'] = state['same'] + 1 if done == state['done'] and placed == 0 else 0
        state.update(name=name, done=done, total=total, pct=100.0 * done / max(1, total))
        d = DOING.search(line)
        state['doing'] = d.group(1) if d else ''
        if flew >= 150 and placed < 10: say(t, 'FLYING AROUND: flew %d blocks for %d placed at %s; %s' % (flew, placed, at.strip(), reasons()))
        if state['same'] in (5, 10, 20) and not state['paused']: say(t, 'NO PROGRESS for %d s at %s; %s' % (state['same'] * 30, at.strip(), reasons()))
        return
    m = STALL.search(line)
    if m:
        secs = int(m.group(1))
        if secs < state['stall_said']: state['stall_said'] = 0
        for mark in (15, 30, 60, 120, 240):
            if secs >= mark > state['stall_said']:
                state['stall_said'] = mark
                steer = re.search(r'steering to ([^|]*)', line)
                say(t, 'STALL %ds at %s, target %s, %s; %s' % (secs, m.group(2).strip(), m.group(3).strip(),
                    steer.group(1).strip()[:70] if steer else '', reasons()))
        return
    if '[undo] taking' in line: say(t, 'TAKES OWN BLOCK OUT: ' + msg[7:200])
    elif '[undo] put' in line: say(t, 'PUTS IT BACK: ' + msg[7:200])
    elif '[undo]' in line and 'is back at' in line: say(t, 'BACK (put in by someone else): ' + msg[7:180])
    elif '[undo]' in line and 'opened up but not to do yet' in line:
        spot = msg.split(' is opened')[0]
        if seen_open.get(spot, -1e9) < time.time() - 120: say(t, 'OPENED, WAITING: ' + msg[7:200])
        seen_open[spot] = time.time()
    elif '[undo]' in line: say(t, 'NEVER PUT BACK: ' + msg[7:160])
    elif '[confused]' in line: say(t, 'CONFUSED: ' + msg[:120] + '; ' + reasons())
    elif '[box] shut in at' in line or '[box] built in' in line: say(t, 'SHUT IN: ' + msg[:140])
    elif 'Done building' in line: say(t, 'DONE: ' + re.sub(r'§.', '', msg)[:140])
    elif 'the server puts in the rest' in line: say(t, 'HANDED OFF: ' + msg[:140])
    elif 'Exception' in line and ('humanautopilot' in line or 'BoxFill' in line or 'Schematic' in line): say(t, 'ERROR: ' + msg[:160])
    elif '/tp @s' in line or 'up over the build' in line: say(t, 'TELEPORT/CLIMB OUT: ' + msg[:140])


def follow():
    f, pos, ino, first = None, 0, None, True
    while True:
        try:
            st = os.stat(LOG)
        except OSError:
            time.sleep(2)
            continue
        if f is None or st.st_size < pos or st.st_ino != ino:  # (a new game: a new latest.log)
            if f: f.close()
            f = open(LOG, 'r', encoding='utf-8', errors='replace')
            if first and not os.environ.get('FROM_START'): f.seek(0, 2)
            first, ino, pos = False, st.st_ino, f.tell()
        line = f.readline()
        if not line:
            if os.environ.get('FROM_START') == 'once': return
            time.sleep(0.5)
            continue
        pos = f.tell()
        handle(line.rstrip(chr(10)))


if __name__ == '__main__':
    follow()
