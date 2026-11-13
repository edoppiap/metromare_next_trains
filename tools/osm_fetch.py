"""
Scarica da OpenStreetMap (Overpass API) i dati grezzi attorno alle stazioni:
  - strade/sentieri percorribili a piedi o in bici  -> roads.json
  - punti di interesse (fontanelle, bar, ciclofficine, farmacie,
    supermercati, bagni) e ingressi stazione -> pois.json

Le richieste contengono SOLO le coordinate delle 14 stazioni (da schedule.json)
e un raggio: mai la posizione di nessuno.

Uso:  py -I tools/osm_fetch.py <cartella_di_uscita> [raggio_m]
"""
import json
import math
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCHEDULE = ROOT / "app" / "src" / "main" / "assets" / "schedule.json"
# Server Overpass pubblici: se uno è carico (504/429) si prova il successivo.
OVERPASS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
]
# Tutte le highway tranne autostrade e strade in costruzione/progetto.
ROAD_SEL = 'way["highway"]["highway"!~"^(motorway|motorway_link|construction|proposed|raceway|bus_guideway|platform|corridor|elevator)$"]'


def stations():
    data = json.loads(SCHEDULE.read_text(encoding="utf-8"))
    return [(s["id"], s["lat"], s["lon"]) for s in data["stations"]]


def around(radius, sts, selector):
    """Una riga Overpass per ogni stazione: selector(around:r,lat,lon);"""
    return "".join(f"{selector}(around:{radius},{lat},{lon});" for _, lat, lon in sts)


TILE_LAT, TILE_LON = 0.02, 0.025   # ~2,2 x 2,1 km a Roma


def tiles(radius, sts):
    """Riquadri (sud, ovest, nord, est) che toccano almeno un cerchio."""
    dlat = radius / 111000
    dlon = radius / (111000 * math.cos(math.radians(41.8)))
    s0 = math.floor((min(s[1] for s in sts) - dlat) / TILE_LAT) * TILE_LAT
    w0 = math.floor((min(s[2] for s in sts) - dlon) / TILE_LON) * TILE_LON
    n1 = max(s[1] for s in sts) + dlat
    e1 = max(s[2] for s in sts) + dlon
    out = []
    lat = s0
    while lat < n1:
        lon = w0
        while lon < e1:
            def touches(st):   # punto del riquadro più vicino alla stazione
                cy = min(max(st[1], lat), lat + TILE_LAT)
                cx = min(max(st[2], lon), lon + TILE_LON)
                dy = (cy - st[1]) * 111000
                dx = (cx - st[2]) * 111000 * math.cos(math.radians(st[1]))
                return math.hypot(dx, dy) <= radius
            if any(touches(st) for st in sts):
                out.append((round(lat, 4), round(lon, 4), round(lat + TILE_LAT, 4), round(lon + TILE_LON, 4)))
            lon += TILE_LON
        lat += TILE_LAT
    return out


def run(query, out_file):
    print(f"Richiesta -> {out_file.name} ...", flush=True)
    body = urllib.parse.urlencode({"data": query}).encode()
    for attempt in range(6):
        for url in OVERPASS:
            req = urllib.request.Request(url, data=body,
                                         headers={"User-Agent": "orari-graph-extract/0.1"})
            try:
                with urllib.request.urlopen(req, timeout=900) as r:
                    data = r.read()
            except Exception as e:  # server carico: si passa al prossimo
                print(f"  {url.split('/')[2]}: {e}", flush=True)
                time.sleep(10 + 20 * attempt)   # attese crescenti
                continue
            out_file.write_bytes(data)
            print(f"  {len(data) / 1e6:.1f} MB da {url.split('/')[2]}", flush=True)
            return
    raise SystemExit(f"Nessun server ha risposto per {out_file.name}")


def main():
    out = Path(sys.argv[1])
    radius = int(sys.argv[2]) if len(sys.argv) > 2 else 6300
    out.mkdir(parents=True, exist_ok=True)
    sts = stations()

    # Riquadri di ~2 km: una richiesta enorme va in timeout sui server pubblici.
    # Si tengono solo i riquadri che toccano almeno un cerchio di una stazione;
    # i doppioni ai bordi si tolgono in osm_graph.py.
    all_tiles = tiles(radius, sts)
    for i, (s_, w_, n_, e_) in enumerate(all_tiles, 1):
        f = out / f"roads_{s_:.3f}_{w_:.3f}.json"
        if not f.exists():
            print(f"[{i}/{len(all_tiles)}]", end=" ")
            q = ("[out:json][timeout:180];("
                 + f'{ROAD_SEL}({s_},{w_},{n_},{e_});'
                 + ");out body qt;>;out skel qt;")
            run(q, f)
            time.sleep(1)   # gentili coi server gratuiti

    pois = (
        "[out:json][timeout:300];("
        + around(radius, sts, 'nwr["amenity"="drinking_water"]')
        + around(radius, sts, 'nwr["amenity"~"^(bar|cafe)$"]')
        + around(radius, sts, 'nwr["shop"="bicycle"]')
        + around(radius, sts, 'nwr["amenity"="bicycle_repair_station"]')
        + around(radius, sts, 'nwr["amenity"="pharmacy"]')
        + around(radius, sts, 'nwr["shop"~"^(supermarket|convenience)$"]')
        + around(radius, sts, 'nwr["amenity"="toilets"]')
        + around(300, sts, 'node["railway"~"^(train_station_entrance|subway_entrance)$"]')
        + around(300, sts, 'node["entrance"]["railway"]')
        + ");out center tags qt;"
    )
    if not (out / "pois.json").exists():
        run(pois, out / "pois.json")


if __name__ == "__main__":
    main()
