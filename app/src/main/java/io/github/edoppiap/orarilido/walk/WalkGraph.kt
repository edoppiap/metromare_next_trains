package io.github.edoppiap.orarilido.walk

import io.github.edoppiap.orarilido.location.GeoPoint
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * La rete di strade attorno alle stazioni (da OpenStreetMap, vedi
 * tools/osm_graph.py che scrive assets/walk.bin), pronta per cercare percorsi.
 *
 * - NODI: gli incroci (e i punti dove si arriva alle stazioni).
 * - ARCHI: i pezzi di strada tra due nodi, con la loro forma (punti intermedi)
 *   e cosa si può fare lì: a piedi, in bici, senso unico per le bici, bici a mano.
 * - ARRIVI: per ogni stazione, i nodi da cui si entra (la stazione stessa o i
 *   suoi ingressi mappati in OSM) e il punto vero dove finisce la linea.
 *
 * Tutto in array semplici: con ~76 mila nodi e ~105 mila archi occupa pochi MB
 * e la ricerca (vedi [WalkRouter]) resta sotto le decine di millisecondi.
 * Kotlin puro: niente Android, si prova con JUnit.
 */
class WalkGraph private constructor(
    val nodeLat: DoubleArray,
    val nodeLon: DoubleArray,
    val edgeA: IntArray,
    val edgeB: IntArray,
    val edgeFlags: IntArray,
    /** Punti intermedi dell'arco e: da shapeStart[e] (incluso) a shapeStart[e+1] (escluso). */
    private val shapeStart: IntArray,
    private val shapeLat: DoubleArray,
    private val shapeLon: DoubleArray,
    val arrivals: List<Arrival>,
) {
    /** Un modo di arrivare a una stazione: dal nodo [node] si cammina dritti fino a [point]. */
    data class Arrival(val stationId: String, val node: Int, val point: GeoPoint)

    /** Un arco da costruire (usato dal lettore e dai test). [mid] = punti intermedi. */
    data class EdgeSpec(val a: Int, val b: Int, val flags: Int, val mid: List<GeoPoint> = emptyList())

    val nodeCount get() = nodeLat.size
    val edgeCount get() = edgeA.size

    /** Lunghezza di ogni arco in metri, calcolata dalla sua forma. */
    val edgeLength = DoubleArray(edgeCount) { e ->
        val pts = edgePoints(e)
        var sum = 0.0
        for (i in 0 until pts.size - 1) sum += distanceM(pts[i], pts[i + 1])
        sum
    }

    // ---- Chi è collegato a chi: per ogni nodo, gli archi che lo toccano ----
    // Formato "compresso": gli archi del nodo n stanno in adj[adjStart[n] until adjStart[n+1]],
    // codificati come arco*2 + 0 (si percorre a->b) oppure arco*2 + 1 (b->a).
    private val adjStart = IntArray(nodeCount + 1)
    private val adj = IntArray(edgeCount * 2)

    init {
        for (e in 0 until edgeCount) { adjStart[edgeA[e] + 1]++; adjStart[edgeB[e] + 1]++ }
        for (n in 0 until nodeCount) adjStart[n + 1] += adjStart[n]
        val fill = adjStart.copyOf()
        for (e in 0 until edgeCount) {
            adj[fill[edgeA[e]]++] = e * 2       // da a verso b
            adj[fill[edgeB[e]]++] = e * 2 + 1   // da b verso a
        }
    }

    /** Posizioni in [exitCode] degli archi che partono dal nodo [n]. */
    fun exitsFrom(n: Int): IntRange = adjStart[n] until adjStart[n + 1]
    /** Arco*2 + verso: pari = si percorre a->b, dispari = b->a. */
    fun exitCode(i: Int): Int = adj[i]

    /** Tutti i punti dell'arco, da a fino a b. */
    fun edgePoints(e: Int): List<GeoPoint> = buildList {
        add(GeoPoint(nodeLat[edgeA[e]], nodeLon[edgeA[e]]))
        for (i in shapeStart[e] until shapeStart[e + 1]) add(GeoPoint(shapeLat[i], shapeLon[i]))
        add(GeoPoint(nodeLat[edgeB[e]], nodeLon[edgeB[e]]))
    }

    fun node(n: Int) = GeoPoint(nodeLat[n], nodeLon[n])

    // ---- Ricerca della strada più vicina a un punto (griglia di celle) ----
    // Ogni cella (~280 × 250 m) sa quali archi la attraversano: per trovare la
    // strada più vicina a te si guardano solo le celle attorno, non 100 mila archi.
    private val cells: Map<Long, IntArray> = run {
        val tmp = HashMap<Long, MutableList<Int>>()
        for (e in 0 until edgeCount) {
            val pts = edgePoints(e)
            var minR = Int.MAX_VALUE; var maxR = Int.MIN_VALUE
            var minC = Int.MAX_VALUE; var maxC = Int.MIN_VALUE
            for (p in pts) {
                val r = row(p.lat); val c = col(p.lon)
                if (r < minR) minR = r; if (r > maxR) maxR = r
                if (c < minC) minC = c; if (c > maxC) maxC = c
            }
            for (r in minR..maxR) for (c in minC..maxC) tmp.getOrPut(key(r, c)) { ArrayList(4) }.add(e)
        }
        tmp.mapValues { it.value.toIntArray() }
    }

    /** Dove ti "aggancia" alla rete: arco, tratto dell'arco, punto più vicino e distanze. */
    data class Snap(
        val edge: Int,
        /** Il punto sta sul tratto tra il punto [segment] e il [segment]+1 dell'arco. */
        val segment: Int,
        val point: GeoPoint,
        /** Distanza da te alla strada, in metri. */
        val distance: Double,
        /** Metri dall'inizio dell'arco (nodo a) fino al punto agganciato. */
        val along: Double,
    )

    /**
     * La strada più vicina a [p] entro [maxMeters], tra gli archi per cui
     * [usable] dice sì (es. solo quelli percorribili in bici). null = nessuna.
     */
    fun snap(p: GeoPoint, maxMeters: Double, usable: (Int) -> Boolean): Snap? {
        val r0 = row(p.lat); val c0 = col(p.lon)
        val reach = 1 + (maxMeters / CELL_M).toInt()
        var best: Snap? = null
        val seen = HashSet<Int>()
        for (r in r0 - reach..r0 + reach) for (c in c0 - reach..c0 + reach) {
            val list = cells[key(r, c)] ?: continue
            for (e in list) {
                if (!seen.add(e) || !usable(e)) continue
                val pts = edgePoints(e)
                var along = 0.0
                for (i in 0 until pts.size - 1) {
                    val (q, t) = project(p, pts[i], pts[i + 1])
                    val d = distanceM(p, q)
                    val segLen = distanceM(pts[i], pts[i + 1])
                    if (d <= maxMeters && (best == null || d < best.distance)) {
                        best = Snap(e, i, q, d, along + segLen * t)
                    }
                    along += segLen
                }
            }
        }
        return best
    }

    private fun row(lat: Double) = floor(lat / CELL_LAT).toInt()
    private fun col(lon: Double) = floor(lon / CELL_LON).toInt()
    private fun key(r: Int, c: Int) = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)

    companion object {
        // Bit di edgeFlags (stessi di tools/osm_graph.py)
        const val WALK = 1
        const val BIKE = 2
        /** In bici solo da a verso b (senso unico). */
        const val BIKE_ONLY_FORWARD = 4
        /** In bici solo da b verso a. */
        const val BIKE_ONLY_BACKWARD = 8
        /** Bici a mano (es. marciapiede, area pedonale): si va alla velocità a piedi. */
        const val BIKE_PUSH = 16

        private const val CELL_LAT = 0.0025   // ~280 m
        private const val CELL_LON = 0.003    // ~250 m a Roma
        private const val CELL_M = 250.0

        /** Costruisce il grafo da liste (lo usano il lettore di walk.bin e i test). */
        fun of(nodes: List<GeoPoint>, edges: List<EdgeSpec>, arrivals: List<Arrival>): WalkGraph {
            val start = IntArray(edges.size + 1)
            for (i in edges.indices) start[i + 1] = start[i] + edges[i].mid.size
            val mids = edges.flatMap { it.mid }
            return WalkGraph(
                nodeLat = DoubleArray(nodes.size) { nodes[it].lat },
                nodeLon = DoubleArray(nodes.size) { nodes[it].lon },
                edgeA = IntArray(edges.size) { edges[it].a },
                edgeB = IntArray(edges.size) { edges[it].b },
                edgeFlags = IntArray(edges.size) { edges[it].flags },
                shapeStart = start,
                shapeLat = DoubleArray(mids.size) { mids[it].lat },
                shapeLon = DoubleArray(mids.size) { mids[it].lon },
                arrivals = arrivals,
            )
        }

        /**
         * Legge assets/walk.bin (formato v1, descritto in tools/osm_graph.py,
         * funzione write_asset): numeri "varint" e coordinate in 1e-5 gradi
         * scritte come differenze dal punto precedente, per occupare poco.
         */
        fun parse(bytes: ByteArray): WalkGraph {
            val r = Reader(bytes)
            require(String(bytes, 0, 4, Charsets.US_ASCII) == "WLK1") { "walk.bin: formato sconosciuto" }
            r.pos = 4
            val n = r.varint()
            val lat = IntArray(n); val lon = IntArray(n)
            var pLat = 0; var pLon = 0
            for (i in 0 until n) {
                pLat += r.zigzag(); pLon += r.zigzag()
                lat[i] = pLat; lon[i] = pLon
            }
            val arrivals = List(r.varint()) {
                val len = r.varint()
                val id = String(bytes, r.pos, len, Charsets.UTF_8)
                r.pos += len
                val node = r.varint()
                val aLat = lat[node] + r.zigzag(); val aLon = lon[node] + r.zigzag()
                Arrival(id, node, GeoPoint(aLat / 1e5, aLon / 1e5))
            }
            val m = r.varint()
            val edges = ArrayList<EdgeSpec>(m)
            var pa = 0
            repeat(m) {
                val a = pa + r.varint(); pa = a
                val b = a + r.zigzag()
                val flags = r.byte()
                val k = r.varint()
                var la = lat[a]; var lo = lon[a]
                val mid = List(k) {
                    la += r.zigzag(); lo += r.zigzag()
                    GeoPoint(la / 1e5, lo / 1e5)
                }
                edges.add(EdgeSpec(a, b, flags, mid))
            }
            return of(List(n) { GeoPoint(lat[it] / 1e5, lon[it] / 1e5) }, edges, arrivals)
        }

        /** Distanza in metri "su un foglio piano": ottima sotto i 10 km. */
        fun distanceM(a: GeoPoint, b: GeoPoint): Double {
            val k = cos(Math.toRadians((a.lat + b.lat) / 2))
            return hypot((b.lat - a.lat) * M_PER_DEG, (b.lon - a.lon) * M_PER_DEG * k)
        }

        /** Punto del segmento a-b più vicino a p, e a che frazione t (0..1) del segmento sta. */
        fun project(p: GeoPoint, a: GeoPoint, b: GeoPoint): Pair<GeoPoint, Double> {
            val k = cos(Math.toRadians(p.lat))
            val ax = (a.lon - p.lon) * k; val ay = a.lat - p.lat
            val dx = (b.lon - a.lon) * k; val dy = b.lat - a.lat
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
            return GeoPoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon)) to t
        }

        private const val M_PER_DEG = 111_195.0
    }

    /** Lettore di numeri varint da un array di byte. */
    private class Reader(val b: ByteArray) {
        var pos = 0
        fun byte(): Int = b[pos++].toInt() and 0xFF
        fun varint(): Int {
            var v = 0; var shift = 0
            while (true) {
                val x = byte()
                v = v or ((x and 0x7F) shl shift)
                if (x < 0x80) return v
                shift += 7
            }
        }
        /** Numero con segno ("zigzag": 0,-1,1,-2,2... diventano 0,1,2,3,4...). */
        fun zigzag(): Int { val v = varint(); return (v ushr 1) xor -(v and 1) }
    }
}
