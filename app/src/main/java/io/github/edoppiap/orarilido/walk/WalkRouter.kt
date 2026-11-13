package io.github.edoppiap.orarilido.walk

import io.github.edoppiap.orarilido.data.TravelMode
import io.github.edoppiap.orarilido.location.Geo
import io.github.edoppiap.orarilido.location.GeoPoint
import io.github.edoppiap.orarilido.walk.WalkGraph.Companion.BIKE
import io.github.edoppiap.orarilido.walk.WalkGraph.Companion.BIKE_ONLY_BACKWARD
import io.github.edoppiap.orarilido.walk.WalkGraph.Companion.BIKE_ONLY_FORWARD
import io.github.edoppiap.orarilido.walk.WalkGraph.Companion.BIKE_PUSH
import io.github.edoppiap.orarilido.walk.WalkGraph.Companion.WALK
import io.github.edoppiap.orarilido.walk.WalkGraph.Companion.distanceM

/** Un percorso trovato: i punti da disegnare (da te alla stazione), minuti e metri. */
data class WalkRoute(val points: List<GeoPoint>, val minutes: Double, val meters: Double)

/**
 * Cerca il percorso più veloce da un punto a una stazione, a piedi o in bici,
 * TUTTO sul telefono (la posizione non esce mai: vedi Privacy in CLAUDE.md).
 *
 * Algoritmo A* ("A-star"): come Dijkstra (espande prima i nodi raggiungibili
 * in meno tempo), ma dà la precedenza a quelli che in linea d'aria sono più
 * vicini alla stazione, così esplora molta meno rete.
 */
object WalkRouter {

    /** Più lontano di così dalla strada più vicina, niente percorso (sei fuori dalla rete). */
    private const val MAX_SNAP_M = 300.0

    /**
     * Percorso da [from] alla stazione [stationId]; null se sei fuori dalle zone
     * coperte o se ci vorrebbero più di [maxMinutes] minuti.
     */
    fun route(
        g: WalkGraph,
        from: GeoPoint,
        stationId: String,
        mode: TravelMode,
        maxMinutes: Double,
    ): WalkRoute? {
        val arrivals = g.arrivals.filter { it.stationId == stationId }
        if (arrivals.isEmpty()) return null
        val bike = mode == TravelMode.BIKE
        val usable: (Int) -> Boolean = { e -> g.edgeFlags[e] and (if (bike) BIKE else WALK) != 0 }
        val snap = g.snap(from, MAX_SNAP_M, usable) ?: return null

        // Velocità sull'arco (metri al minuto): in bici a mano si va come a piedi.
        fun speed(e: Int): Double =
            if (bike && g.edgeFlags[e] and BIKE_PUSH == 0) Geo.BIKE_METERS_PER_MINUTE else Geo.WALK_METERS_PER_MINUTE
        // Si può percorrere l'arco in quel verso? (a piedi sempre; in bici occhio ai sensi unici)
        fun allowed(e: Int, backward: Boolean): Boolean {
            val f = g.edgeFlags[e]
            if (!usable(e)) return false
            if (!bike) return true
            return if (backward) f and BIKE_ONLY_FORWARD == 0 else f and BIKE_ONLY_BACKWARD == 0
        }

        val n = g.nodeCount
        val sink = n  // nodo "finto": la stazione (ci si arriva da uno qualsiasi degli arrivi)
        val cost = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
        /** Da dove si è arrivati: arco*2+verso; −1 = primo nodo (dal punto agganciato); −2 = nessuno. */
        val via = IntArray(n + 1) { -2 }
        val arrivalOf = HashMap<Int, WalkGraph.Arrival>()
        for (a in arrivals) {
            val prev = arrivalOf[a.node]
            if (prev == null || distanceM(g.node(a.node), a.point) < distanceM(g.node(prev.node), prev.point)) arrivalOf[a.node] = a
        }
        val maxSpeed = if (bike) Geo.BIKE_METERS_PER_MINUTE else Geo.WALK_METERS_PER_MINUTE
        // Stima "ottimista" dei minuti mancanti: linea d'aria alla velocità massima.
        fun h(node: Int): Double {
            if (node == sink) return 0.0
            val p = g.node(node)
            return arrivals.minOf { distanceM(p, it.point) } / maxSpeed
        }
        val heap = MinHeap()
        fun push(node: Int, c: Double, from: Int) {
            if (c < cost[node]) {
                cost[node] = c
                via[node] = from
                heap.add(c + h(node), node)
            }
        }

        // Partenza: da te si cammina fino alla strada, poi lungo l'arco verso uno dei due capi.
        val e0 = snap.edge
        val approach = snap.distance / Geo.WALK_METERS_PER_MINUTE
        if (allowed(e0, backward = true)) push(g.edgeA[e0], approach + snap.along / speed(e0), -1)
        if (allowed(e0, backward = false)) push(g.edgeB[e0], approach + (g.edgeLength[e0] - snap.along) / speed(e0), -1)
        val startSideA = cost[g.edgeA[e0]]  // per ricostruire: da che parte siamo partiti
        val startSideB = cost[g.edgeB[e0]]

        while (heap.isNotEmpty()) {
            val node = heap.popNode()
            val c = cost[node]
            if (heap.lastKey > c + h(node) + 1e-9) continue  // voce vecchia: c'era già un costo migliore
            // La chiave è una stima per difetto del totale: se già supera il limite,
            // anche tutto ciò che resta in coda lo supera.
            if (heap.lastKey > maxMinutes) return null
            if (node == sink) break
            // Nodo di arrivo: da qui alla stazione/ingresso si cammina dritti.
            arrivalOf[node]?.let { a -> push(sink, c + distanceM(g.node(node), a.point) / Geo.WALK_METERS_PER_MINUTE, node * 2) }
            for (i in g.exitsFrom(node)) {
                val code = g.exitCode(i)
                val e = code shr 1
                val backward = (code and 1) == 1
                if (!allowed(e, backward)) continue
                val next = if (backward) g.edgeA[e] else g.edgeB[e]
                push(next, c + g.edgeLength[e] / speed(e), code)
            }
        }
        if (cost[sink].isInfinite() || cost[sink] > maxMinutes) return null

        // ---- Ricostruzione: dalla stazione all'indietro fino a te ----
        val lastNode = via[sink] / 2
        val arrival = arrivalOf.getValue(lastNode)
        val reversed = ArrayList<GeoPoint>()
        reversed.add(arrival.point)
        var node = lastNode
        while (via[node] >= 0) {
            val code = via[node]
            val e = code shr 1
            val pts = g.edgePoints(e)
            // L'arco è stato percorso a->b (code pari) o b->a (dispari): lo aggiungiamo al contrario.
            val forwardPts = if (code and 1 == 0) pts else pts.asReversed()
            for (k in forwardPts.size - 1 downTo 1) reversed.add(forwardPts[k])
            node = if (code and 1 == 0) g.edgeA[e] else g.edgeB[e]
        }
        reversed.add(g.node(node))
        // Primo pezzo: dal punto agganciato al capo dell'arco da cui si è partiti.
        val pts0 = g.edgePoints(e0)
        val startedFromA = node == g.edgeA[e0] && (node != g.edgeB[e0] || startSideA <= startSideB)
        if (startedFromA) {
            for (k in 1..snap.segment) reversed.add(pts0[k])  // da a (già messo) verso il punto: al contrario
        } else {
            for (k in pts0.size - 2 downTo snap.segment + 1) reversed.add(pts0[k])
        }
        reversed.add(snap.point)
        reversed.add(from)
        val points = reversed.asReversed().toList().let { dedup(it) }
        var meters = 0.0
        for (i in 0 until points.size - 1) meters += distanceM(points[i], points[i + 1])
        return WalkRoute(points, cost[sink], meters)
    }

    /** Toglie i punti uguali consecutivi (es. te stesso già sulla strada). */
    private fun dedup(pts: List<GeoPoint>): List<GeoPoint> =
        pts.filterIndexed { i, p -> i == 0 || distanceM(p, pts[i - 1]) > 0.3 }

    /** Coda con priorità minima (heap binario) di coppie (chiave, nodo). */
    private class MinHeap {
        private var keys = DoubleArray(1024)
        private var nodes = IntArray(1024)
        private var size = 0
        /** Chiave dell'ultimo elemento tolto. */
        var lastKey = 0.0
            private set

        fun isNotEmpty() = size > 0

        fun add(key: Double, node: Int) {
            if (size == keys.size) { keys = keys.copyOf(size * 2); nodes = nodes.copyOf(size * 2) }
            var i = size++
            while (i > 0) {
                val parent = (i - 1) / 2
                if (keys[parent] <= key) break
                keys[i] = keys[parent]; nodes[i] = nodes[parent]
                i = parent
            }
            keys[i] = key; nodes[i] = node
        }

        fun popNode(): Int {
            val top = nodes[0]
            lastKey = keys[0]
            size--
            if (size > 0) {
                val key = keys[size]; val node = nodes[size]
                var i = 0
                while (true) {
                    var child = 2 * i + 1
                    if (child >= size) break
                    if (child + 1 < size && keys[child + 1] < keys[child]) child++
                    if (keys[child] >= key) break
                    keys[i] = keys[child]; nodes[i] = nodes[child]
                    i = child
                }
                keys[i] = key; nodes[i] = node
            }
            return top
        }
    }
}
