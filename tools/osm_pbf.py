"""
Legge un estratto OpenStreetMap in formato .osm.pbf (es. lazio-latest.osm.pbf
da download.openstreetmap.fr) e tiene solo quello che serve attorno alle
stazioni. Scrive gli stessi file di osm_fetch.py (che usa Overpass), così
osm_graph.py funziona con entrambi:
  roads_pbf.json  strade (way con highway) + i loro punti
  pois.json       fontanelle, bar, ciclofficine, farmacie, supermercati,
                  bagni, ingressi delle stazioni

Python puro, nessuna libreria: il formato PBF è "protobuf" (numeri codificati
a lunghezza variabile) dentro blocchi compressi con zlib.

Uso:  py -I tools/osm_pbf.py <file.osm.pbf> <cartella_di_uscita> [raggio_m]
"""
import json
import math
import struct
import sys
import time
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCHEDULE = ROOT / "app" / "src" / "main" / "assets" / "schedule.json"

EXCLUDED_HW = {"motorway", "motorway_link", "construction", "proposed", "raceway",
               "bus_guideway", "platform", "corridor", "elevator"}
POI_AMENITY = {"drinking_water", "bar", "cafe", "bicycle_repair_station", "pharmacy", "toilets"}
POI_SHOP = {"bicycle", "supermarket", "convenience"}
ENTRANCE_M = 300   # ingressi stazione: entro 300 m dal punto della stazione


# ------------------------------------------------------------ protobuf minimo
def read_varint(b, i):
    """Legge un numero a lunghezza variabile da b[i:]: ritorna (valore, nuova posizione)."""
    v = shift = 0
    while True:
        x = b[i]
        i += 1
        v |= (x & 0x7F) << shift
        if x < 0x80:
            return v, i
        shift += 7


def fields(b):
    """Scorre i campi di un messaggio: (numero_campo, valore). I campi 'lunghi'
    (tipo 2: testi, sotto-messaggi, liste compatte) tornano come bytes."""
    i, n = 0, len(b)
    while i < n:
        key, i = read_varint(b, i)
        wt = key & 7
        if wt == 0:
            v, i = read_varint(b, i)
        elif wt == 2:
            ln, i = read_varint(b, i)
            v = b[i:i + ln]
            i += ln
        elif wt == 1:
            v = b[i:i + 8]; i += 8
        elif wt == 5:
            v = b[i:i + 4]; i += 4
        else:
            raise ValueError(f"tipo di campo {wt} non gestito")
        yield key >> 3, v


def packed(b):
    """Lista compatta di varint -> lista di interi."""
    out = []
    i, n = 0, len(b)
    append = out.append
    while i < n:
        v = shift = 0
        while True:
            x = b[i]
            i += 1
            v |= (x & 0x7F) << shift
            if x < 0x80:
                break
            shift += 7
        append(v)
    return out


def zigzag(v):
    return (v >> 1) ^ -(v & 1)


def delta_zigzag(b):
    """Lista compatta di sint64 codificati come differenze -> valori assoluti."""
    out, acc = [], 0
    for v in packed(b):
        acc += (v >> 1) ^ -(v & 1)
        out.append(acc)
    return out


# ------------------------------------------------------------ blocchi del file
def blobs(path):
    """Scorre i blocchi del file: (tipo, dati_decompressi)."""
    with open(path, "rb") as f:
        while True:
            head = f.read(4)
            if not head:
                return
            (hlen,) = struct.unpack(">I", head)
            btype, dsize = None, 0
            for k, v in fields(f.read(hlen)):
                if k == 1:
                    btype = v.decode()
                elif k == 3:
                    dsize = v
            raw = f.read(dsize)
            data = None
            for k, v in fields(raw):
                if k == 1:
                    data = v
                elif k == 3:
                    data = zlib.decompress(v)
                elif k == 4:
                    import lzma
                    data = lzma.decompress(v)
                elif k == 7:
                    from compression import zstd   # Python 3.14
                    data = zstd.decompress(v)
            yield btype, data


def primitive_blocks(path, want_nodes, want_ways):
    """Per ogni blocco dati: (tabella_stringhe, granularità, off_lat, off_lon, gruppi_scelti)."""
    for btype, data in blobs(path):
        if btype != "OSMData":
            continue
        strings, groups = [], []
        gran, lat_off, lon_off = 100, 0, 0
        for k, v in fields(data):
            if k == 1:
                strings = [s for kk, s in fields(v) if kk == 1]
            elif k == 2:
                groups.append(v)
            elif k == 17:
                gran = v
            elif k == 19:
                lat_off = v
            elif k == 20:
                lon_off = v
        chosen = []
        for g in groups:
            for k, v in fields(g):
                if (k in (1, 2) and want_nodes) or (k == 3 and want_ways):
                    chosen.append((k, v))
        if chosen:
            yield strings, gran, lat_off, lon_off, chosen


def iter_nodes(path):
    """Tutti i nodi: (id, lat, lon, tag_o_None)."""
    for strings, gran, lat_off, lon_off, items in primitive_blocks(path, True, False):
        for kind, msg in items:
            if kind == 2:   # DenseNodes: tutto in liste parallele, a differenze
                ids = lats = lons = kv = None
                for k, v in fields(msg):
                    if k == 1:
                        ids = delta_zigzag(v)
                    elif k == 8:
                        lats = delta_zigzag(v)
                    elif k == 9:
                        lons = delta_zigzag(v)
                    elif k == 10:
                        kv = packed(v)
                j = 0
                for n in range(len(ids)):
                    tags = None
                    if kv is not None:
                        # chiave, valore, chiave, valore, ..., 0 = fine del nodo
                        while kv[j] != 0:
                            if tags is None:
                                tags = {}
                            tags[strings[kv[j]].decode("utf-8", "replace")] = \
                                strings[kv[j + 1]].decode("utf-8", "replace")
                            j += 2
                        j += 1
                    yield (ids[n], 1e-9 * (lat_off + gran * lats[n]),
                           1e-9 * (lon_off + gran * lons[n]), tags)
            else:           # Node singolo (raro)
                nid = lat = lon = 0
                keys = vals = []
                for k, v in fields(msg):
                    if k == 1:
                        nid = zigzag(v)
                    elif k == 2:
                        keys = packed(v)
                    elif k == 3:
                        vals = packed(v)
                    elif k == 8:
                        lat = zigzag(v)
                    elif k == 9:
                        lon = zigzag(v)
                tags = {strings[a].decode(): strings[b].decode() for a, b in zip(keys, vals)} or None
                yield nid, 1e-9 * (lat_off + gran * lat), 1e-9 * (lon_off + gran * lon), tags


def iter_ways(path):
    """Tutte le way: (id, [id_nodi], tag)."""
    for strings, _, _, _, items in primitive_blocks(path, False, True):
        for _, msg in items:
            wid, keys, vals, refs = 0, [], [], []
            for k, v in fields(msg):
                if k == 1:
                    wid = v
                elif k == 2:
                    keys = packed(v)
                elif k == 3:
                    vals = packed(v)
                elif k == 8:
                    refs = delta_zigzag(v)
            tags = {strings[a].decode("utf-8", "replace"): strings[b].decode("utf-8", "replace")
                    for a, b in zip(keys, vals)}
            yield wid, refs, tags


# ------------------------------------------------------------ filtro sulle stazioni
def is_poi(t):
    return t.get("amenity") in POI_AMENITY or t.get("shop") in POI_SHOP


def is_entrance(t):
    return "railway" in t and ("entrance" in t.get("railway", "") or "entrance" in t)


def main():
    pbf, out = Path(sys.argv[1]), Path(sys.argv[2])
    radius = int(sys.argv[3]) if len(sys.argv) > 3 else 6300
    out.mkdir(parents=True, exist_ok=True)
    sts = [(s["lat"], s["lon"]) for s in json.loads(SCHEDULE.read_text(encoding="utf-8"))["stations"]]
    k = math.cos(math.radians(41.8))
    dlat, dlon = radius / 111000, radius / (111000 * k)
    s0, n0 = min(p[0] for p in sts) - dlat, max(p[0] for p in sts) + dlat
    w0, e0 = min(p[1] for p in sts) - dlon, max(p[1] for p in sts) + dlon

    def nearest_m(lat, lon):
        return min(math.hypot((lat - a) * 111000, (lon - b) * 111000 * k) for a, b in sts)

    t0 = time.time()
    # Passo 1: nodi dentro i cerchi (coordinate) + POI/ingressi che sono nodi
    inside, pois = {}, []
    for nid, lat, lon, tags in iter_nodes(pbf):
        if s0 <= lat <= n0 and w0 <= lon <= e0:
            d = nearest_m(lat, lon)
            if d <= radius:
                inside[nid] = (lat, lon)
                if tags and (is_poi(tags) or (is_entrance(tags) and d <= ENTRANCE_M)):
                    pois.append({"type": "node", "id": nid, "lat": lat, "lon": lon, "tags": tags})
    print(f"Passo 1: {len(inside):,} punti nei cerchi, {len(pois)} POI-nodo  ({time.time()-t0:.0f} s)", flush=True)

    # Passo 2: strade con almeno un punto nei cerchi; POI disegnati come aree
    roads, poi_ways, missing = [], [], set()
    for wid, refs, tags in iter_ways(pbf):
        hw = tags.get("highway")
        road = hw is not None and hw not in EXCLUDED_HW
        poi = is_poi(tags)
        if (road or poi) and any(r in inside for r in refs):
            if road:
                roads.append({"type": "way", "id": wid, "nodes": refs, "tags": tags})
            if poi:
                poi_ways.append((wid, refs, tags))
            missing.update(r for r in refs if r not in inside)
    print(f"Passo 2: {len(roads):,} strade, {len(poi_ways)} POI-area, "
          f"{len(missing):,} punti fuori dai cerchi da recuperare  ({time.time()-t0:.0f} s)", flush=True)

    # Passo 3: coordinate dei punti delle strade che escono dai cerchi
    if missing:
        for nid, lat, lon, _ in iter_nodes(pbf):
            if nid in missing:
                inside[nid] = (lat, lon)
    print(f"Passo 3 fatto  ({time.time()-t0:.0f} s)", flush=True)

    for wid, refs, tags in poi_ways:
        pts = [inside[r] for r in refs if r in inside]
        if pts:
            pois.append({"type": "way", "id": wid, "tags": tags,
                         "center": {"lat": sum(p[0] for p in pts) / len(pts),
                                    "lon": sum(p[1] for p in pts) / len(pts)}})

    used = {r for w in roads for r in w["nodes"]}
    elements = [{"type": "node", "id": n, "lat": round(inside[n][0], 7), "lon": round(inside[n][1], 7)}
                for n in used if n in inside] + roads
    (out / "roads_pbf.json").write_text(json.dumps({"elements": elements}, separators=(",", ":")), encoding="utf-8")
    (out / "pois.json").write_text(json.dumps({"elements": pois}, separators=(",", ":"), ensure_ascii=False),
                                   encoding="utf-8")
    print(f"Scritti {len(used):,} punti strada, {len(roads):,} strade, {len(pois):,} POI  ({time.time()-t0:.0f} s)")


if __name__ == "__main__":
    main()
