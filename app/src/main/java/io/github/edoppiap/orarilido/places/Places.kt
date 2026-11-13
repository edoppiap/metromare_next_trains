package io.github.edoppiap.orarilido.places

import io.github.edoppiap.orarilido.location.GeoPoint

/**
 * Tipi di punti di interesse (da OpenStreetMap), nello STESSO ordine di CATS
 * in tools/osm_graph.py: il file salva solo l'indice.
 */
enum class PlaceKind(val label: String) {
    WATER("Fontanella"),
    BAR("Bar"),
    BIKE_SHOP("Ciclofficina"),
    PHARMACY("Farmacia"),
    SUPERMARKET("Supermercato"),
    TOILET("Bagni"),
}

/** Un punto di interesse: tipo, posizione e nome (vuoto se OSM non lo ha). */
data class Place(val kind: PlaceKind, val point: GeoPoint, val name: String)

/** Lettore di assets/pois.bin (scritto da tools/osm_graph.py, funzione write_pois). Kotlin puro. */
object Places {

    fun parse(bytes: ByteArray): List<Place> {
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "POI1") { "pois.bin: formato sconosciuto" }
        var pos = 4
        fun varint(): Int {
            var v = 0; var shift = 0
            while (true) {
                val x = bytes[pos++].toInt() and 0xFF
                v = v or ((x and 0x7F) shl shift)
                if (x < 0x80) return v
                shift += 7
            }
        }
        fun zigzag(): Int { val v = varint(); return (v ushr 1) xor -(v and 1) }

        val kinds = PlaceKind.entries
        var lat = 0; var lon = 0
        return List(varint()) {
            val kind = kinds[bytes[pos++].toInt()]
            lat += zigzag(); lon += zigzag()
            val len = varint()
            val name = String(bytes, pos, len, Charsets.UTF_8)
            pos += len
            Place(kind, GeoPoint(lat / 1e5, lon / 1e5), name)
        }
    }
}
