# Orari Metromare

App Android **non ufficiale** che, appena aperta, mostra tra quanto passano i
prossimi treni della Metromare (ferrovia Roma–Lido) dalla stazione più
vicina, in entrambe le direzioni.

> Non è affiliata a Cotral né ad Astral. Gli orari sono quelli
> **programmati**, non in tempo reale: in caso di ritardi, scioperi o guasti
> fanno fede gli avvisi ufficiali di Cotral.

## Cosa fa

- Prossimi treni dalla stazione più vicina, con il conto alla rovescia.
- Orari di tutta la giornata per stazione e direzione.
- Mappa della linea, percorso a piedi o in bici fino alla stazione, punti
  utili vicino alle stazioni (fontanelle, bar, farmacie…).
- Bus sostitutivi serali nei giorni feriali.
- Modalità "Vai": in treno mostra tra quanto arrivi alle fermate successive.

Niente pubblicità, account, analytics. La posizione non lascia mai il
telefono: il percorso si calcola sul dispositivo.

## Dati e fonti

| Dato | Fonte | Licenza |
|---|---|---|
| Orario dei treni (`schedule.json`) | Orario Astral/Cotral in vigore dal 9/11/2026 | dati di orario pubblici |
| Fermate dei bus sostitutivi | GTFS di Roma Servizi per la Mobilità | CC BY 4.0 |
| Linea, strade, punti di interesse (`line.json`, `walk.bin`, `pois.bin`) | © OpenStreetMap contributors | ODbL 1.0 |

Il codice è sotto licenza MIT (vedi [LICENSE](LICENSE)); i file di dati
mantengono le loro licenze.

Gli script in `tools/` rigenerano i dati: `osm_pbf.py` e `osm_graph.py` da
un estratto OpenStreetMap del Lazio, `gtfs_check.py` confronta l'orario con
il GTFS ufficiale Cotral.

## Compilare

Serve Android Studio (JDK incluso). La mappa usa Google Maps: per vederla
serve una propria chiave "Maps SDK for Android" in `local.properties`:

```
MAPS_API_KEY=la_tua_chiave
```

Senza chiave gli orari funzionano, ma la mappa non si carica.

```
./gradlew testDebugUnitTest assembleDebug
```

## Segnalazioni

Orari sbagliati o stazioni mancanti: apri una issue su GitHub.

App sviluppata con l'aiuto di Claude (Anthropic).
