"""Minimal litematic reader: python tools/litematic.py <file.litematic> [query]
query: 'find <id>'          -> positions (schematic coords) of a block id, with its neighbours
       'at x y z'           -> the block there and round it
"""
import gzip, struct, sys, math, collections

def rd(f, n):
    b = f.read(n)
    if len(b) < n: raise EOFError
    return b

def tag(f, t):
    if t == 1: return struct.unpack('>b', rd(f, 1))[0]
    if t == 2: return struct.unpack('>h', rd(f, 2))[0]
    if t == 3: return struct.unpack('>i', rd(f, 4))[0]
    if t == 4: return struct.unpack('>q', rd(f, 8))[0]
    if t == 5: return struct.unpack('>f', rd(f, 4))[0]
    if t == 6: return struct.unpack('>d', rd(f, 8))[0]
    if t == 7:
        n = struct.unpack('>i', rd(f, 4))[0]; return rd(f, n)
    if t == 8:
        n = struct.unpack('>H', rd(f, 2))[0]; return rd(f, n).decode('utf-8', 'replace')
    if t == 9:
        et = rd(f, 1)[0]; n = struct.unpack('>i', rd(f, 4))[0]
        return [tag(f, et) for _ in range(n)]
    if t == 10:
        d = {}
        while True:
            et = rd(f, 1)[0]
            if et == 0: return d
            nl = struct.unpack('>H', rd(f, 2))[0]; name = rd(f, nl).decode('utf-8', 'replace')
            d[name] = tag(f, et)
    if t == 11:
        n = struct.unpack('>i', rd(f, 4))[0]; return list(struct.unpack('>%di' % n, rd(f, 4 * n)))
    if t == 12:
        n = struct.unpack('>i', rd(f, 4))[0]; return list(struct.unpack('>%dq' % n, rd(f, 8 * n)))
    raise ValueError(t)

def load(path):
    with gzip.open(path, 'rb') as f:
        t = rd(f, 1)[0]; nl = struct.unpack('>H', rd(f, 2))[0]; rd(f, nl)
        return tag(f, t)

def name(st):
    n = st['Name'].replace('minecraft:', '')
    if 'Properties' in st:
        n += '[' + ','.join('%s=%s' % kv for kv in sorted(st['Properties'].items())) + ']'
    return n

def regions(root):
    out = []
    for rname, r in root['Regions'].items():
        pos = r['Position']; size = r['Size']
        sx, sy, sz = size['x'], size['y'], size['z']
        ox = pos['x'] + (sx + 1 if sx < 0 else 0); oy = pos['y'] + (sy + 1 if sy < 0 else 0); oz = pos['z'] + (sz + 1 if sz < 0 else 0)
        sx, sy, sz = abs(sx), abs(sy), abs(sz)
        pal = [name(p) for p in r['BlockStatePalette']]
        bits = max(2, math.ceil(math.log2(len(pal))))
        longs = [v & 0xFFFFFFFFFFFFFFFF for v in r['BlockStates']]
        vol = sx * sy * sz; mask = (1 << bits) - 1
        arr = [0] * vol
        for i in range(vol):
            bit = i * bits; li = bit >> 6; off = bit & 63
            v = longs[li] >> off
            if off + bits > 64: v |= longs[li + 1] << (64 - off)
            arr[i] = v & mask
        out.append((rname, (ox, oy, oz), (sx, sy, sz), pal, arr))
    return out

def grid(path):
    root = load(path)
    g = {}
    mins = [10**9] * 3
    for rname, o, s, pal, arr in regions(root):
        sx, sy, sz = s
        for y in range(sy):
            for z in range(sz):
                for x in range(sx):
                    b = pal[arr[(y * sz + z) * sx + x]]
                    if b == 'air': continue
                    g[(o[0] + x, o[1] + y, o[2] + z)] = b
        for k in range(3): mins[k] = min(mins[k], o[k])
    # shift to 0-based
    return {(x - mins[0], y - mins[1], z - mins[2]): b for (x, y, z), b in g.items()}

if __name__ == '__main__':
    g = grid(sys.argv[1])
    q = sys.argv[2:]
    if not q:
        c = collections.Counter(b.split('[')[0] for b in g.values())
        print(len(g), 'blocks'); print(c.most_common(40))
    elif q[0] == 'find':
        hits = [p for p, b in g.items() if b.split('[')[0] == q[1]]
        print(len(hits), 'of', q[1])
        for p in sorted(hits)[:int(q[2]) if len(q) > 2 else 8]:
            x, y, z = p
            print(p, g[p])
            for d, (dx, dy, dz) in {'down': (0, -1, 0), 'up': (0, 1, 0), 'n': (0, 0, -1), 's': (0, 0, 1), 'w': (-1, 0, 0), 'e': (1, 0, 0)}.items():
                print('   ', d, g.get((x + dx, y + dy, z + dz), 'air'))
    elif q[0] == 'at':
        x, y, z = map(int, q[1:4])
        for dy in (1, 0, -1):
            print('y', y + dy)
            for dz in (-1, 0, 1):
                print('   ', ' | '.join(g.get((x + dx, y + dy, z + dz), 'air')[:28].ljust(28) for dx in (-1, 0, 1)))
