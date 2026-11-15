"""Legge gli avvisi pubblici di Cotral e decide quali mostrare nell'app.

Uso:  py -I tools/cotral_alerts.py            (stampa cosa mostrerebbe l'app)
      py -I tools/cotral_alerts.py --json F   (scrive anche il file avvisi.json)
      --debug                                 (mostra anche gli scartati)

Su GitHub gira da solo ogni 30 minuti (.github/workflows/avvisi.yml) e salva
avvisi.json nel ramo "dati". Le date di Cotral sono in ora di Roma: il
workflow imposta TZ=Europe/Rome, perche' i server di GitHub sono in UTC.

Fonte: https://cotralspa.it/wp-json/wp/v2/alerts (elenco: titolo, date, link;
il testo NON c'e', quindi per gli avvisi che passano il filtro si apre la
pagina dell'avviso per leggere le date di validita' e le fasce di sciopero).

Due livelli:
  "urgente" -> in cima al menu + pallino sull'icona: sciopero non ancora
               finito che riguarda i treni, oppure guasto/sospensione
               sulla Metromare ancora valido.
  "notizia" -> nella lista "Notizie": il resto degli avvisi rilevanti.
Nessuna approvazione a mano: il testo mostrato e' sempre il titolo di Cotral.
Solo libreria standard di Python.
"""
import html
import json
import re
import sys
import urllib.request
from datetime import datetime, timedelta

API = "https://cotralspa.it/wp-json/wp/v2/alerts?per_page=50&_fields=id,date,modified,title,link"
USER_AGENT = "OrariMetromare-avvisi/0.1"

# Un avviso e' rilevante se il titolo contiene una di queste parole...
STATIONS = [
    "porta san paolo", "basilica san paolo", "eur magliana", "tor di valle",
    "vitinia", "casal bernocchi", "acilia", "ostia", "lido nord", "lido centro",
    "stella polare", "castel fusano", "castelfusano", "cristoforo colombo",
]
LINE_WORDS = ["metromare", "roma-lido", "roma lido"] + STATIONS
GENERIC_WORDS = ["sciopero", "treni"]          # riguardano tutta Cotral
# ...ma non se parla solo di un'altra ferrovia (e non della nostra).
OTHER_LINES = ["viterbo"]
# Parole che, insieme alla Metromare, rendono l'avviso urgente.
FAULT_WORDS = ["sospes", "interrott", "guasto", "navette", "soppress", "chius"]

NEWS_DAYS = 30   # avvisi senza data di fine: si mostrano per 30 giorni
# Guasti. Cotral li chiude in modi diversi (visti a set./ott. 2026):
#  - mette una "Fine validita'" (ramo sui binari dell'8/10)  -> urgente fino a li';
#  - cambia titolo/testo in "Riattivata", "ripristinata"   -> risolto;
#  - pubblica un avviso piu' recente sulla Metromare ("aggiornamento",
#    "ripristinato"...)                                     -> chiude il vecchio;
#  - a volte NON li chiude mai (Metromare sospesa del 9/9: "Da definire",
#    ancora online due mesi dopo). "Da definire" = non sanno quanto dura,
#    quindi resta urgente finche' Cotral lo aggiorna; rete di sicurezza:
#    dopo 7 giorni SENZA modifiche scende tra le notizie (non sparisce
#    subito, non diciamo che e' risolto). Nell'app si mostra sempre la data
#    dell'ultimo aggiornamento di Cotral.
FAULT_URGENT_DAYS = 7
RESOLVED_WORDS = ["riattivat", "ripristinat", "regolare", "riprende", "ripreso"]
UPDATE_WORDS = RESOLVED_WORDS + ["aggiornament"]
RESOLVED_DAYS = 1   # "Riattivata..." resta come notizia per un giorno


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.read().decode("utf-8", errors="replace")


def page_text(page_html):
    """Testo semplice della pagina, senza script, stili, menu."""
    t = re.sub(r"(?s)<(script|style|header|footer|nav)[^>]*>.*?</\1>", " ", page_html)
    t = html.unescape(re.sub(r"<[^>]+>", " ", t))
    return re.sub(r"\s+", " ", t)


def parse_date(s):
    """'20-11-2026 23:59' -> datetime; 'Da definire' o altro -> None."""
    try:
        return datetime.strptime(s, "%d-%m-%Y %H:%M")
    except ValueError:
        return None


def read_details(text):
    """Dalla pagina dell'avviso: inizio, fine e (se sciopero) fasce dei treni."""
    d = {}
    m = re.search(r"Inizio (?:sciopero|validit\S*) (\d\d-\d\d-\d{4} \d\d:\d\d)", text)
    if m:
        d["start"] = parse_date(m.group(1))
    m = re.search(r"Fine (?:sciopero|validit\S*) (\d\d-\d\d-\d{4} \d\d:\d\d)", text)
    if m:
        d["end"] = parse_date(m.group(1))
    # Fasce: "08:30 - 16:59 Bus in sciopero Treni in sciopero"
    bands = re.findall(
        r"(\d\d:\d\d) - (\d\d:\d\d) Bus (regolari|in sciopero) Treni (regolari|in sciopero)",
        text)
    if bands:
        d["train_strike_bands"] = [f"{a}-{b}" for a, b, _bus, tr in bands if tr == "in sciopero"]
    # Testo dell'avviso (tra "Torna a tutti gli avvisi" e "Condividi"):
    # serve solo a capire se un guasto e' stato risolto, NON si mostra.
    m = re.search(r"Torna a tutti gli avvisi(.*?)(?:Condividi|$)", text)
    if m:
        d["body"] = m.group(1)
    return d


def classify(title, details, now, modified=None):
    """Restituisce 'urgente', 'notizia' o None (= non mostrare).
    modified = ultima modifica dell'avviso (serve per i guasti senza fine)."""
    t = title.lower()
    on_line = any(w in t for w in LINE_WORDS)
    generic = any(w in t for w in GENERIC_WORDS)
    if not (on_line or generic):
        return None
    if any(w in t for w in OTHER_LINES) and not on_line:
        return None

    end = details.get("end")
    if end and end < now:
        return None                                    # scaduto
    is_strike = "sciopero" in t
    if is_strike and ("rinviat" in t or "differit" in t or "revocat" in t):
        return "notizia"                               # sciopero annullato
    if is_strike:
        # Urgente da quando e' pubblicato fino alla fine, se i treni sono
        # coinvolti (scelta dell'utente: uno sciopero merita il menu anche
        # giorni prima). Se non riusciamo a leggere le fasce, meglio urgente.
        bands = details.get("train_strike_bands")
        if bands == []:
            return "notizia"                           # solo bus in sciopero
        return "urgente"
    age = now - modified if modified else timedelta(0)
    # Guasto risolto: il titolo o il testo dicono "riattivata", "ripristinata"...
    # (nel primo passaggio, solo sul titolo, il testo non c'e' ancora)
    if on_line and "sciopero" not in t:
        title_and_body = t + " " + details.get("body", "").lower()
        if any(w in title_and_body for w in RESOLVED_WORDS) and \
                any(w in title_and_body for w in FAULT_WORDS + ["riattivat"]):
            return "notizia" if age <= timedelta(days=RESOLVED_DAYS) else None
    if on_line and any(w in t for w in FAULT_WORDS):
        if end:
            return "urgente"                           # c'e' una fine: vale fino a lei
        if age <= timedelta(days=FAULT_URGENT_DAYS):
            return "urgente"                           # "Da definire"
        return "notizia"                               # 7 giorni senza notizie
    return "notizia"


def is_fault(title):
    t = title.lower()
    return any(w in t for w in LINE_WORDS) and "sciopero" not in t and \
        any(w in t for w in FAULT_WORDS)


def is_line_update(title):
    """Avviso sulla Metromare che aggiorna/chiude un guasto precedente."""
    t = title.lower()
    return any(w in t for w in LINE_WORDS) and any(w in t for w in UPDATE_WORDS)


def drop_superseded(items, everything):
    """Un guasto e' superato se dopo e' uscito un altro avviso sulla
    Metromare che e' un guasto (lo sostituisce) o un aggiornamento/ripristino
    (lo chiude). Resta solo il piu' recente. "everything" = TUTTI gli avvisi
    Cotral (anche quelli non mostrati: un "ripristinato" di 3 giorni fa non
    si vede piu', ma chiude comunque il guasto)."""
    keep = []
    for x in items:
        newer = [y for y in everything if y["id"] != x["id"] and y["modified"] > x["modified"]
                 and (is_fault(y["title"]) or is_line_update(y["title"]))]
        if is_fault(x["title"]) and newer:
            continue
        keep.append(x)
    return keep


def main():
    sys.stdout.reconfigure(encoding="utf-8")   # accenti giusti nella console
    debug = "--debug" in sys.argv               # mostra anche gli scartati
    now = datetime.now()
    alerts = json.loads(fetch(API))
    out = []
    for a in alerts:
        title = html.unescape(a["title"]["rendered"]).strip()
        modified = datetime.fromisoformat(a["modified"])
        # Primo passaggio solo sul titolo, per non aprire pagine inutili.
        if classify(title, {}, now) is None:
            if debug:
                print(f"  (non pertinente) {title}")
            continue
        details = read_details(page_text(fetch(a["link"])))
        level = classify(title, details, now, modified)
        if level is None:
            if debug:
                print(f"  (scaduto {details.get('end')}) {title}")
            continue
        # Senza data di fine: si mostra per NEWS_DAYS dall'ultima modifica.
        if not details.get("end") and now - modified > timedelta(days=NEWS_DAYS):
            if debug:
                print(f"  (vecchio, modificato {a['modified'][:10]}) {title}")
            continue
        item = {
            "id": a["id"],
            # Se Cotral modifica l'avviso cambia "modified": l'app riaccende
            # il pallino perche' (id, modified) non e' tra quelli gia' visti.
            "modified": a["modified"],
            "level": level,
            "title": title,
            "link": a["link"],
            "end": details["end"].isoformat() if details.get("end") else None,
        }
        if details.get("train_strike_bands"):
            item["trainStrikeBands"] = details["train_strike_bands"]
        out.append(item)

    everything = [{"id": a["id"], "modified": a["modified"],
                   "title": html.unescape(a["title"]["rendered"]).strip()} for a in alerts]
    out = drop_superseded(out, everything)
    # Prima gli urgenti, poi i piu' recenti.
    out.sort(key=lambda x: x["modified"], reverse=True)
    out.sort(key=lambda x: x["level"] != "urgente")
    for x in out:
        extra = f"  treni in sciopero {', '.join(x['trainStrikeBands'])}" if x.get("trainStrikeBands") else ""
        print(f"[{x['level']:7}] {x['modified'][:10]}  {x['title']}{extra}")
        print(f"           fine: {x['end'] or 'non indicata'}  {x['link']}")
    if not out:
        print("Nessun avviso da mostrare.")

    if "--json" in sys.argv:
        path = sys.argv[sys.argv.index("--json") + 1]
        # Si riscrive il file SOLO se gli avvisi sono cambiati: cosi' su
        # GitHub c'e' un commit solo quando c'e' davvero una novita'.
        # "updated" = quando sono cambiati l'ultima volta (ora di Roma).
        try:
            with open(path, encoding="utf-8") as f:
                old = json.load(f).get("alerts")
        except (OSError, ValueError):
            old = None
        if old == out:
            print(f"{path}: nessun cambiamento")
        else:
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                json.dump({"updated": now.isoformat(timespec="minutes"), "alerts": out},
                          f, ensure_ascii=False, indent=2)
                f.write("\n")
            print(f"Scritto {path}")


if __name__ == "__main__":
    main()
