"""
Costruisce il grafo pedonale/ciclabile delle "zone coperte" dalle stazioni
e ne MISURA il peso (per ora non scrive nessun asset dell'app).

Passi:
 1. unisce i roads_*.json scaricati da osm_fetch.py (togliendo i doppioni)
 2. decide per ogni strada se ci si va a piedi, in bici, in che senso
 3. spezza le strade agli incroci -> archi con la loro forma
 4. Dijkstra "al contrario" partendo da TUTTE le stazioni insieme:
    per ogni incrocio, minuti per arrivare alla stazione più comoda
    (= zone coperte, tipo Voronoi ma sulle strade)
 5. per vari limiti (es. piedi 40 min, bici 25 min) tiene solo le strade
    dentro le zone, sfoltisce la forma (Douglas-Peucker), unisce gli archi
    rimasti "in fila" e codifica in binario compatto -> misura byte e zip.

Uso:  py -I tools/osm_graph.py <cartella_dati_osm>
"""
import gzip
import heapq
import json
import math
import struct
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCHEDULE = ROOT / "app" / "src" / "main" / "assets" / "schedule.json"

WALK_M_PER_MIN = 80.0   # come i cerchi nell'app
BIKE_M_PER_MIN = 250.0
SIMPLIFY_M = 5.0        # tolleranza Douglas-Peucker
SNAP_MAX_M = 150.0      # stazione/ingresso -> incrocio più vicino

# Limiti da provare: (minuti a piedi, minuti in bici)
CAPS = [(30, 15), (40, 20), (40, 25), (50, 25), (60, 30)]


# ---------------------------------------------------------------- geometria
def dist_m(a, b):
    """Distanza approssimata (equirettangolare) in metri: va bene sotto i 10 km."""
    lat = math.radians((a[0] + b[0]) / 2)
    dx = math.radians(b[1] - a[1]) * math.cos(lat)
    dy = math.radians(b[0] - a[0])
    return 6371000.0 * math.hypot(dx, dy)


def seg_dist_m(p, a, b):
    """Distanza del punto p dal segmento a-b (metri, piano locale)."""
    k = math.cos(math.radians(p[0])) * 111320.0
    ax, ay = (a[1] - p[1]) * k, (a[0] - p[0]) * 110540.0
    bx, by = (b[1] - p[1]) * k, (b[0] - p[0]) * 110540.0
    dx, dy = bx - ax, by - ay
    l2 = dx * dx + dy * dy
    t = 0.0 if l2 == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / l2))
    return math.hypot(ax + t * dx, ay + t * dy)


def simplify(pts, tol):
    """Douglas-Peucker: tiene solo i punti che servono a disegnare la curva."""
    if len(pts) <= 2:
        return pts
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        i, j = stack.pop()
        best, idx = 0.0, -1
        for k in range(i + 1, j):
            d = seg_dist_m(pts[k], pts[i], pts[j])
            if d > best:
                best, idx = d, k
        if best > tol:
            keep[idx] = True
            stack += [(i, idx), (idx, j)]
    return [p for p, k in zip(pts, keep) if k]


# ---------------------------------------------------------------- regole OSM
NO_WALK = {"trunk", "trunk_link", "bus_stop", "rest_area", "services", "escape", "busway"}
NO_BIKE = {"trunk", "trunk_link", "steps", "bus_stop", "rest_area", "services", "escape", "busway"}
PUSH_BIKE = {"footway", "pedestrian", "corridor"}   # bici a mano (velocità a piedi)
NO_ACCESS = {"no", "private"}


def access(tags):
    """-> (piedi, bici, bici_senso: 0 entrambi / 1 solo avanti / -1 solo indietro, bici_a_mano)"""
    hw = tags.get("highway", "")
    acc = tags.get("access", "")
    foot = tags.get("foot", "")
    bic = tags.get("bicycle", "")
    if tags.get("area") == "yes" and hw != "pedestrian":
        return False, False, 0, False

    walk = hw not in NO_WALK or foot in ("yes", "designated", "permissive") \
        or tags.get("sidewalk", "no") not in ("no", "none")
    if foot in NO_ACCESS or (acc in NO_ACCESS and foot not in ("yes", "designated", "permissive")):
        walk = False

    bike = hw not in NO_BIKE or bic in ("yes", "designated", "permissive")
    if bic in NO_ACCESS or (acc in NO_ACCESS and bic not in ("yes", "designated", "permissive")):
        bike = False
    push = bike and (bic == "dismount" or (hw in PUSH_BIKE and bic not in ("yes", "designated", "permissive")))

    ow = tags.get("oneway", "")
    oneway = 1 if ow in ("yes", "1", "true") else -1 if ow == "-1" else 0
    if oneway == 0 and tags.get("junction") in ("roundabout", "circular"):
        oneway = 1
    if tags.get("oneway:bicycle") == "no" or "opposite" in tags.get("cycleway", "") \
            or any("opposite" in tags.get(k, "") for k in ("cycleway:left", "cycleway:right", "cycleway:both")):
        oneway = 0
    if push:
        oneway = 0   # a mano si cammina, il senso unico non conta
    return walk, bike, oneway, push


# ---------------------------------------------------------------- caricamento
def load(folder):
    nodes, ways = {}, {}
    for f in sorted(folder.glob("roads_*.json")):
        for el in json.loads(f.read_text(encoding="utf-8"))["elements"]:
            if el["type"] == "node":
                nodes[el["id"]] = (el["lat"], el["lon"])
            elif el["type"] == "way":
                ways[el["id"]] = (el["nodes"], el.get("tags", {}))
    pois = json.loads((folder / "pois.json").read_text(encoding="utf-8"))["elements"]
    return nodes, ways, pois


def build_edges(nodes, ways):
    """Spezza le strade agli incroci. Ritorna lista di archi:
    (nodo_a, nodo_b, [punti], walk, bike, oneway, push)"""
    ways_ok = {}
    for wid, (nds, tags) in ways.items():
        w, b, ow, push = access(tags)
        if (w or b) and len(nds) >= 2 and all(n in nodes for n in nds):
            ways_ok[wid] = (nds, w, b, ow, push)
    use = defaultdict(int)
    for nds, *_ in ways_ok.values():
        for i, n in enumerate(nds):
            use[n] += 2 if i in (0, len(nds) - 1) else 1
    edges = []
    for nds, w, b, ow, push in ways_ok.values():
        start = 0
        for i in range(1, len(nds)):
            if use[nds[i]] >= 2 or i == len(nds) - 1:
                pts = [nodes[n] for n in nds[start:i + 1]]
                if nds[start] != nds[i] or len(pts) > 2:
                    edges.append((nds[start], nds[i], pts, w, b, ow, push))
                start = i
    return edges


# ---------------------------------------------------------------- zone coperte
def length(pts):
    return sum(dist_m(pts[i], pts[i + 1]) for i in range(len(pts) - 1))


def coverage(edges, sources, mode):
    """Dijkstra multi-sorgente dalle stazioni: minuti dal nodo alla stazione più comoda.
    In bici si percorrono gli archi AL CONTRARIO (si calcola nodo -> stazione)."""
    adj = defaultdict(list)
    for a, b, pts, w, bk, ow, push in edges:
        L = length(pts)
        if mode == "walk":
            if not w:
                continue
            t = L / WALK_M_PER_MIN
            adj[a].append((b, t))
            adj[b].append((a, t))
        else:
            if not bk:
                continue
            t = L / (WALK_M_PER_MIN if push else BIKE_M_PER_MIN)
            # arco a->b percorribile: dal punto di vista "stazione -> nodo" si va da b ad a
            if ow >= 0:
                adj[b].append((a, t))
            if ow <= 0:
                adj[a].append((b, t))
    best = {}
    owner = {}
    heap = [(t0, n, st) for n, (t0, st, _) in sources.items()]
    heapq.heapify(heap)
    while heap:
        t, n, st = heapq.heappop(heap)
        if n in best:
            continue
        best[n], owner[n] = t, st
        for m, dt in adj[n]:
            if m not in best:
                heapq.heappush(heap, (t + dt, m, st))
    return best, owner


def snap_sources(edges, nodes, stations, pois):
    """Ogni stazione (e i suoi ingressi OSM) -> incroci vicini, con i minuti per arrivarci in linea retta."""
    graph_nodes = {e[0] for e in edges} | {e[1] for e in edges}
    cell = defaultdict(list)
    for n in graph_nodes:
        p = nodes[n]
        cell[(int(p[0] * 500), int(p[1] * 500))].append(n)   # celle ~200 m

    def near(p):
        cx, cy = int(p[0] * 500), int(p[1] * 500)
        best = (None, 1e18)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for n in cell[(cx + dx, cy + dy)]:
                    d = dist_m(p, nodes[n])
                    if d < best[1]:
                        best = (n, d)
        return best

    targets = [(sid, (lat, lon)) for sid, lat, lon in stations]
    entrances = 0
    for el in pois:
        t = el.get("tags", {})
        if el["type"] == "node" and ("railway" in t and ("entrance" in t.get("railway", "") or "entrance" in t)):
            p = (el["lat"], el["lon"])
            sid, _, _ = min(stations, key=lambda s: dist_m(p, (s[1], s[2])))
            targets.append((sid, p))
            entrances += 1
    sources = {}
    for sid, p in targets:
        n, d = near(p)
        if n is not None and d <= SNAP_MAX_M:
            t = d / WALK_M_PER_MIN
            if n not in sources or sources[n][0] > t:
                sources[n] = (t, sid, p)
    return sources, entrances


# ---------------------------------------------------------------- sfoltire e codificare
def prune(edges, tw, tb, cap_w, cap_b):
    kept = []
    for e in edges:
        a, b, pts, w, bk, ow, push = e
        kw = w and min(tw.get(a, 1e9), tw.get(b, 1e9)) <= cap_w
        kb = bk and min(tb.get(a, 1e9), tb.get(b, 1e9)) <= cap_b
        if kw or kb:
            kept.append((a, b, pts, kw, kb, ow if kb else 0, push and kb))
    return kept


def merge_chains(edges, protected=frozenset()):
    """Dopo lo sfoltimento molti incroci restano con 2 soli archi uguali: li uniamo.
    I nodi [protected] (arrivi alle stazioni) restano dove sono."""
    edges = [list(e) for e in edges]
    by_node = defaultdict(list)
    for i, e in enumerate(edges):
        by_node[e[0]].append(i)
        by_node[e[1]].append(i)
    alive = [True] * len(edges)
    changed = True
    while changed:
        changed = False
        for n, lst in list(by_node.items()):
            lst = [i for i in lst if alive[i]]
            by_node[n] = lst
            if len(lst) != 2 or lst[0] == lst[1] or n in protected:
                continue
            i, j = lst
            ei, ej = edges[i], edges[j]
            if ei[3:5] != ej[3:5] or ei[6] != ej[6]:
                continue
            # orienta: ei finisce in n, ej parte da n
            def flip(e):
                return [e[1], e[0], e[2][::-1], e[3], e[4], -e[5], e[6]]
            if ei[1] != n:
                ei = flip(ei)
            if ej[0] != n:
                ej = flip(ej)
            if ei[5] != ej[5] or ei[0] == ej[1]:
                continue
            new = [ei[0], ej[1], ei[2] + ej[2][1:], ei[3], ei[4], ei[5], ei[6]]
            edges[i] = new
            alive[j] = False
            by_node[n] = []
            by_node[ej[1]] = [i if k == j else k for k in by_node[ej[1]]]
            changed = True
    return [tuple(e) for e, a in zip(edges, alive) if a]


def varint(out, v):
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return


def zz(v):
    return (v << 1) ^ (v >> 63)


def encode(edges, nodes):
    """Binario compatto: coordinate in 1e-5 gradi (~1 m) come differenze, varint.
    Struttura (per la stima; il formato vero si deciderà con il lettore Kotlin):
      nodi:   n, poi (dlat, dlon) zigzag-varint in ordine spaziale
      archi:  m, poi da(delta), a, flag, nPunti, punti intermedi come differenze"""
    ids = sorted({e[0] for e in edges} | {e[1] for e in edges},
                 key=lambda n: (round(nodes[n][0], 3), nodes[n][1]))
    index = {n: i for i, n in enumerate(ids)}
    out = bytearray()
    varint(out, len(ids))
    plat = plon = 0
    for n in ids:
        la, lo = round(nodes[n][0] * 1e5), round(nodes[n][1] * 1e5)
        varint(out, zz(la - plat)); varint(out, zz(lo - plon))
        plat, plon = la, lo
    es = sorted(edges, key=lambda e: (index[e[0]], index[e[1]]))
    varint(out, len(es))
    pa = 0
    for a, b, pts, w, bk, ow, push in es:
        ia, ib = index[a], index[b]
        varint(out, ia - pa); pa = ia
        varint(out, zz(ib - ia))
        out.append(w | bk << 1 | (ow & 3) << 2 | push << 4)
        mid = simplify(pts, SIMPLIFY_M)[1:-1]
        varint(out, len(mid))
        la0, lo0 = round(pts[0][0] * 1e5), round(pts[0][1] * 1e5)
        for p in mid:
            la, lo = round(p[0] * 1e5), round(p[1] * 1e5)
            varint(out, zz(la - la0)); varint(out, zz(lo - lo0))
            la0, lo0 = la, lo
    return bytes(out), len(ids)


CATS = ["fontanelle", "bar", "ciclofficine", "farmacie", "supermercati", "bagni"]


def write_asset(edges, nodes, sources, path):
    """Scrive il grafo per l'app (assets/walk.bin). Formato v1, tutti i numeri
    sono varint; "zz" = zigzag (numeri con segno); coordinate in 1e-5 gradi (~1 m).
      "WLK1"
      nNodi;    per nodo:    zz(dlat) zz(dlon)        differenza dal nodo precedente
      nArrivi;  per arrivo:  len + id stazione (utf-8), nodo, zz(lat-latNodo), zz(lon-lonNodo)
                             (il punto vero: la stazione o un suo ingresso)
      nArchi;   per arco:    (a - aPrecedente), zz(b - a), flag, nPunti,
                             punti intermedi come zz(dlat) zz(dlon) partendo dal nodo a
      flag: bit0 a piedi, bit1 in bici, bit2 bici solo a->b, bit3 bici solo b->a,
            bit4 bici a mano (velocità a piedi)
    Ritorna il numero di byte."""
    ids = sorted({e[0] for e in edges} | {e[1] for e in edges},
                 key=lambda n: (round(nodes[n][0], 3), nodes[n][1]))
    index = {n: i for i, n in enumerate(ids)}
    e5 = {n: (round(nodes[n][0] * 1e5), round(nodes[n][1] * 1e5)) for n in ids}
    out = bytearray(b"WLK1")
    varint(out, len(ids))
    plat = plon = 0
    for n in ids:
        la, lo = e5[n]
        varint(out, zz(la - plat)); varint(out, zz(lo - plon))
        plat, plon = la, lo
    arr = [(sid, n, p) for n, (sid, p) in sources.items() if n in index]
    varint(out, len(arr))
    for sid, n, p in sorted(arr):
        b = sid.encode("utf-8")
        varint(out, len(b)); out += b
        varint(out, index[n])
        varint(out, zz(round(p[0] * 1e5) - e5[n][0])); varint(out, zz(round(p[1] * 1e5) - e5[n][1]))
    es = sorted(edges, key=lambda e: (index[e[0]], index[e[1]]))
    varint(out, len(es))
    pa = 0
    for a, b, pts, w, bk, ow, push in es:
        ia, ib = index[a], index[b]
        varint(out, ia - pa); pa = ia
        varint(out, zz(ib - ia))
        out.append(int(w) | int(bk) << 1 | (ow == 1) << 2 | (ow == -1) << 3 | int(push) << 4)
        mid = simplify(pts, SIMPLIFY_M)[1:-1]
        varint(out, len(mid))
        la0, lo0 = e5[a]
        for q in mid:
            la, lo = round(q[0] * 1e5), round(q[1] * 1e5)
            varint(out, zz(la - la0)); varint(out, zz(lo - lo0))
            la0, lo0 = la, lo
    Path(path).write_bytes(bytes(out))
    return len(out)


def poi_category(t):
    if t.get("amenity") == "drinking_water":
        return "fontanelle"
    if t.get("amenity") in ("bar", "cafe"):
        return "bar"
    if t.get("shop") == "bicycle" or t.get("amenity") == "bicycle_repair_station":
        return "ciclofficine"
    if t.get("amenity") == "pharmacy":
        return "farmacie"
    if t.get("shop") in ("supermarket", "convenience"):
        return "supermercati"
    if t.get("amenity") == "toilets":
        return "bagni"
    return None


def write_pois(pois, path):
    """Scrive i punti di interesse per l'app (assets/pois.bin). Formato v1:
      "POI1", n; per punto (in ordine di latitudine):
        categoria (1 byte, indice in CATS), zz(dlat) zz(dlon) in 1e-5 gradi
        dal punto precedente, lunghezza nome + nome utf-8 (0 = senza nome)
    Ritorna il numero di byte."""
    out = bytearray(b"POI1")
    varint(out, len(pois))
    plat = plon = 0
    for cat, p, name, _ in sorted(pois, key=lambda q: (q[1][0], q[1][1])):
        out.append(CATS.index(cat))
        la, lo = round(p[0] * 1e5), round(p[1] * 1e5)
        varint(out, zz(la - plat)); varint(out, zz(lo - plon))
        plat, plon = la, lo
        b = name.encode("utf-8")[:80]
        varint(out, len(b)); out += b
    Path(path).write_bytes(bytes(out))
    return len(out)


def main():
    folder = Path(sys.argv[1])
    write_to = None
    if "--write" in sys.argv:   # --write <file> <min_piedi> <min_bici>
        i = sys.argv.index("--write")
        write_to, cap_w, cap_b = sys.argv[i + 1], int(sys.argv[i + 2]), int(sys.argv[i + 3])
    stations = [(s["id"], s["lat"], s["lon"]) for s in
                json.loads(SCHEDULE.read_text(encoding="utf-8"))["stations"]]
    nodes, ways, pois = load(folder)
    print(f"OSM grezzo: {len(nodes):,} punti, {len(ways):,} strade")
    edges = build_edges(nodes, ways)
    print(f"Archi tra incroci: {len(edges):,}")
    sources, n_entr = snap_sources(edges, nodes, stations, pois)
    print(f"Ingressi stazione trovati in OSM: {n_entr}; punti di partenza: {len(sources)}")
    tw, ow_w = coverage(edges, sources, "walk")
    tb, ow_b = coverage(edges, sources, "bike")
    # nodo del grafo più vicino a ogni POI (per sapere se è in zona)
    gn = list({e[0] for e in edges} | {e[1] for e in edges})
    cell = defaultdict(list)
    for n in gn:
        cell[(int(nodes[n][0] * 500), int(nodes[n][1] * 500))].append(n)

    def nearest_node(p):
        cx, cy = int(p[0] * 500), int(p[1] * 500)
        c = [n for dx in (-1, 0, 1) for dy in (-1, 0, 1) for n in cell[(cx + dx, cy + dy)]]
        return min(c, key=lambda n: dist_m(p, nodes[n]), default=None)

    poi_list = []
    for el in pois:
        cat = poi_category(el.get("tags", {}))
        if not cat:
            continue
        p = (el["lat"], el["lon"]) if "lat" in el else (el["center"]["lat"], el["center"]["lon"])
        poi_list.append((cat, p, el["tags"].get("name", ""), nearest_node(p)))

    if write_to:
        kept = merge_chains(prune(edges, tw, tb, cap_w, cap_b), protected=frozenset(sources))
        size = write_asset(kept, nodes, {n: (sid, p) for n, (_, sid, p) in sources.items()}, write_to)
        print(f"Scritto {write_to}: {size / 1e6:.2f} MB (piedi {cap_w} min, bici {cap_b} min)")
        inz = [q for q in poi_list if q[3] is not None and
               (tw.get(q[3], 1e9) <= cap_w or tb.get(q[3], 1e9) <= cap_b)]
        pois_to = str(Path(write_to).with_name("pois.bin"))
        size = write_pois(inz, pois_to)
        print(f"Scritto {pois_to}: {size / 1e3:.0f} KB, {len(inz)} punti di interesse")
        return

    print()
    print("limite piedi/bici | incroci |  archi  | binario |  zippato | POI zip | " + " ".join(CATS))
    for cap_w, cap_b in CAPS:
        kept = merge_chains(prune(edges, tw, tb, cap_w, cap_b))
        data, nn = encode(kept, nodes)
        z = len(gzip.compress(data, 9))
        # POI in zona: incrocio vicino dentro il limite a piedi o in bici
        inz = [p for p in poi_list if p[3] is not None and
               (tw.get(p[3], 1e9) <= cap_w or tb.get(p[3], 1e9) <= cap_b)]
        cnt = defaultdict(int)
        pbuf = bytearray()
        for cat, p, name, _ in sorted(inz, key=lambda x: (x[0], x[1])):
            cnt[cat] += 1
            pbuf += struct.pack("<Bii", CATS.index(cat),
                                round(p[0] * 1e5), round(p[1] * 1e5))
            pbuf += name.encode("utf-8")[:60] + b"\0"
        pz = len(gzip.compress(bytes(pbuf), 9))
        print(f"   {cap_w:3d} / {cap_b:2d} min   | {nn:7,} | {len(kept):7,} | {len(data)/1e6:5.2f} MB "
              f"| {z/1e6:5.2f} MB | {pz/1e3:4.0f} KB | " + " ".join(f"{cnt[c]:,}" for c in CATS))


if __name__ == "__main__":
    main()
