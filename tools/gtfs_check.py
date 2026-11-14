"""Confronta schedule.json con il GTFS ufficiale dei treni Cotral.

Cotral pubblica gli orari dei treni (Metromare, Roma-Viterbo, Roma-Flaminio)
in formato GTFS a questo indirizzo (trovato tramite Transitland):
    http://travel.mob.cotralspa.it:7777/GTFS/GTFS_FERRO.zip

Il GTFS NON entra nell'app (licenza non dichiarata, indirizzo che può
cambiare): serve solo per controllare a mano il nostro orario. Lo script:
  1. scarica lo zip (o usa quello indicato con --zip);
  2. per ogni giorno coperto dal GTFS guarda quali corse Metromare ci sono;
  3. le confronta con quello che mostrerebbe l'app (partenze dai capolinea,
     minuti per stazione, tipo di giorno feriale/sabato/festivo);
  4. stampa SOLO le differenze, più il periodo di validità del GTFS.

Uso (dalla cartella del progetto):
    py -I tools/gtfs_check.py
    py -I tools/gtfs_check.py --zip percorso/GTFS_FERRO.zip

Solo libreria standard di Python, niente da installare.
"""

import argparse
import collections
import csv
import datetime as dt
import io
import json
import sys
import urllib.request
import zipfile

GTFS_URL = "http://travel.mob.cotralspa.it:7777/GTFS/GTFS_FERRO.zip"
SCHEDULE = "app/src/main/assets/schedule.json"

# Codici delle fermate nel GTFS Cotral -> id delle stazioni in schedule.json.
GTFS_TO_OURS = {
    "RL_PORTASPAOLO": "porta_san_paolo",
    "RL_SANPAOLO": "basilica_san_paolo",
    "RL_EURMAGLIANA": "eur_magliana",
    "RL_TORDIVALLE": "tor_di_valle",
    "RL_VITINIA": "vitinia",
    "RL_CASALBERNO": "casal_bernocchi",
    "RL_ACILIA": "acilia",
    "RL_ACILIASUD_DRAGONA": "acilia_sud",
    "RL_OSTIAANT": "ostia_antica",
    "RL_LIDONORD": "lido_nord",
    "RL_LIDOCENTRO": "lido_centro",
    "RL_STELLAPOL": "stella_polare",
    "RL_CASFUS": "castel_fusano",
    "RL_COLOMBO": "cristoforo_colombo",
}
WEEKDAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]


def read_zip(path):
    """Restituisce lo zip del GTFS: da file se indicato, altrimenti scaricato."""
    if path:
        return zipfile.ZipFile(path)
    print(f"Scarico {GTFS_URL} ...")
    with urllib.request.urlopen(GTFS_URL, timeout=60) as r:
        return zipfile.ZipFile(io.BytesIO(r.read()))


def table(z, name):
    """Legge un file .txt del GTFS come lista di dizionari."""
    with z.open(name) as f:
        return list(csv.DictReader(io.TextIOWrapper(f, encoding="utf-8-sig")))


def minutes(hhmmss):
    """'05:57:30' -> 357.5 (le ore possono superare 24 dopo mezzanotte)."""
    h, m, s = (int(x) for x in hhmmss.split(":"))
    return h * 60 + m + s / 60


def hhmm(mins):
    mins = int(mins)
    return f"{mins // 60 % 24:02d}:{mins % 60:02d}"


def day_type(day, holidays):
    """Il tipo di giorno come lo calcola l'app (domenica = festivo)."""
    if day.isoformat() in holidays or day.weekday() == 6:
        return "holiday"
    return "saturday" if day.weekday() == 5 else "weekday"


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--zip", help="GTFS_FERRO.zip già scaricato")
    ap.add_argument("--schedule", default=SCHEDULE)
    args = ap.parse_args()

    z = read_zip(args.zip)
    ours = json.load(open(args.schedule, encoding="utf-8"))
    holidays = set(ours["holidays"])

    # --- Corse Metromare del GTFS, con le fermate in ordine ---------------
    trips = {t["trip_id"]: t for t in table(z, "trips.txt") if t["route_id"] == "ROMALIDO"}
    stops_of = collections.defaultdict(list)
    for x in table(z, "stop_times.txt"):
        if x["trip_id"] in trips:
            stops_of[x["trip_id"]].append(x)
    for s in stops_of.values():
        s.sort(key=lambda x: int(x["stop_sequence"]))

    # --- Calendario: in quali giorni vale ogni service_id ------------------
    calendar = {c["service_id"]: c for c in table(z, "calendar.txt")}
    exceptions = collections.defaultdict(dict)  # service_id -> {data: "1"/"2"}
    for c in table(z, "calendar_dates.txt"):
        exceptions[c["service_id"]][c["date"]] = c["exception_type"]

    def runs_on(service_id, day):
        ds = day.strftime("%Y%m%d")
        exc = exceptions[service_id].get(ds)
        if exc:
            return exc == "1"  # 1 = aggiunto quel giorno, 2 = tolto
        c = calendar.get(service_id)
        return bool(c) and c["start_date"] <= ds <= c["end_date"] and c[WEEKDAYS[day.weekday()]] == "1"

    all_dates = [c["start_date"] for c in calendar.values()] + [c["end_date"] for c in calendar.values()]
    all_dates += [d for e in exceptions.values() for d in e]
    first = dt.datetime.strptime(min(all_dates), "%Y%m%d").date()
    last = dt.datetime.strptime(max(all_dates), "%Y%m%d").date()
    print(f"GTFS valido dal {first} al {last}")
    if last - dt.date.today() < dt.timedelta(days=14):
        print("  ATTENZIONE: scade fra meno di due settimane, a breve potrebbe arrivare un orario nuovo.")

    problems = 0
    checked_types = set()
    reported = set()
    day = first
    while day <= last:
        kind = day_type(day, holidays)
        today_trips = [stops_of[t] for t in trips if runs_on(trips[t]["service_id"], day) and stops_of[t]]

        for direction in ("toOstia", "toRoma"):
            terminus = "RL_PORTASPAOLO" if direction == "toOstia" else "RL_COLOMBO"
            runs = [s for s in today_trips if s[0]["stop_id"] == terminus]
            # Corse che non partono dal capolinea (es. da Acilia): da guardare a parte.
            for s in today_trips:
                if s[0]["stop_id"] not in ("RL_PORTASPAOLO", "RL_COLOMBO"):
                    print(f"{day} corsa che parte da {s[0]['stop_id']} alle {s[0]['departure_time']}"
                          f" verso {s[-1]['stop_id']} (le nostre 'extraTrips' vanno controllate)")

            # 1) Partenze dai capolinea: stesse corse?
            theirs = sorted(hhmm(minutes(s[0]["departure_time"])) for s in runs)
            mine = ours["departures"][kind][direction]
            if theirs != sorted(mine):
                problems += 1
                print(f"{day} ({kind}) {direction}: partenze diverse")
                print(f"    solo GTFS: {sorted(set(theirs) - set(mine))}")
                print(f"    solo noi:  {sorted(set(mine) - set(theirs))}")

            # 2) Minuti per stazione (li controlliamo una volta per tipo di giorno).
            if (kind, direction) in checked_types:
                continue
            checked_types.add((kind, direction))
            for s in runs:
                t0 = minutes(s[0]["departure_time"])
                for x in s:
                    station = GTFS_TO_OURS.get(x["stop_id"])
                    if station is None:
                        problems += 1
                        print(f"Fermata sconosciuta nel GTFS: {x['stop_id']} (aggiungerla a GTFS_TO_OURS)")
                        continue
                    # L'app arrotonda per difetto, come l'app Cotral (13,5 -> 13).
                    gtfs_min = int(minutes(x["departure_time"]) - t0)
                    our_min = ours["minutesFromTerminus"][direction].get(station)
                    if gtfs_min != our_min and (direction, station, gtfs_min) not in reported:
                        reported.add((direction, station, gtfs_min))  # una riga sola, non una per corsa
                        problems += 1
                        print(f"{day} {direction} {station}: GTFS {gtfs_min} min, noi {our_min}"
                              f" (corsa delle {s[0]['departure_time'][:5]})")
        day += dt.timedelta(days=1)

    # Le corse 'unconfirmed' non sono nel GTFS (al 14/11/2026): ricordarlo.
    unconfirmed = [e for e in ours.get("extraTrips", []) if e.get("unconfirmed")]
    if unconfirmed:
        print(f"Nota: {len(unconfirmed)} corse col '?' in schedule.json (non nel GTFS, lasciate apposta).")

    print("Nessuna differenza." if problems == 0 else f"{problems} differenze.")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
