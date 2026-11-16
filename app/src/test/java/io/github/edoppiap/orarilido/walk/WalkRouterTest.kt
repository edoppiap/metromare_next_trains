package io.github.edoppiap.orarilido.walk

import io.github.edoppiap.orarilido.data.TravelMode
import io.github.edoppiap.orarilido.location.Geo
import io.github.edoppiap.orarilido.location.GeoPoint
import io.github.edoppiap.orarilido.schedule.ScheduleParser
import io.github.edoppiap.orarilido.walk.WalkGraph.Companion.BIKE
import io.github.edoppiap.orarilido.walk.WalkGraph.Companion.BIKE_ONLY_BACKWARD
import io.github.edoppiap.orarilido.walk.WalkGraph.Companion.BIKE_PUSH
import io.github.edoppiap.orarilido.walk.WalkGraph.Companion.WALK
import io.github.edoppiap.orarilido.walk.WalkGraph.EdgeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WalkRouterTest {

    // ---- Grafo finto (punti fittizi) ----
    // Partenza P a ovest, nodo T a est (~340 m) con la "stazione" 20 m più a nord.
    // Strada diretta P–T, oppure giro più lungo passando per N a nord (~580 m).
    private val p = GeoPoint(41.800, 12.400)
    private val t = GeoPoint(41.800, 12.404)
    private val nNorth = GeoPoint(41.802, 12.402)
    private val station = GeoPoint(41.8002, 12.404)
    private val arrivals = listOf(WalkGraph.Arrival("fittizia", 1, station))

    private fun graph(directFlags: Int) = WalkGraph.of(
        nodes = listOf(p, t, nNorth),
        edges = listOf(
            EdgeSpec(0, 1, directFlags),
            EdgeSpec(0, 2, WALK or BIKE),
            EdgeSpec(2, 1, WALK or BIKE),
        ),
        arrivals = arrivals,
    )

    private fun route(g: WalkGraph, mode: TravelMode, from: GeoPoint = p, max: Double = 60.0) =
        WalkRouter.route(g, from, "fittizia", mode, max)

    private fun usesNorth(r: WalkRoute) = r.points.any { Geo.distanceMeters(it, nNorth) < 1 }

    @Test fun `prende la strada piu breve e finisce sulla stazione`() {
        val r = route(graph(WALK or BIKE), TravelMode.WALK)!!
        assertTrue(!usesNorth(r))
        assertEquals(station, r.points.last())
        assertEquals(p, r.points.first())
        // 340 m + 22 m fino alla stazione, a 80 m/min
        assertEquals(362.0, r.meters, 10.0)
        assertEquals(r.meters / 80, r.minutes, 0.2)
    }

    @Test fun `in bici rispetta il senso unico, a piedi no`() {
        val g = graph(WALK or BIKE or BIKE_ONLY_BACKWARD)  // in bici solo da T verso P
        assertTrue(usesNorth(route(g, TravelMode.BIKE)!!))
        assertTrue(!usesNorth(route(g, TravelMode.WALK)!!))
    }

    @Test fun `in bici non usa le strade solo pedonali`() {
        val g = graph(WALK)
        assertTrue(usesNorth(route(g, TravelMode.BIKE)!!))
    }

    @Test fun `bici a mano conta come a piedi, quindi conviene il giro`() {
        val g = graph(WALK or BIKE or BIKE_PUSH)
        val bike = route(g, TravelMode.BIKE)!!
        assertTrue(usesNorth(bike))
        // ~580 m in bici (250 m/min) + 22 m a piedi fino alla stazione
        assertTrue(bike.minutes < 340.0 / 80)
    }

    @Test fun `ti aggancia a meta strada e parte da te`() {
        val user = GeoPoint(41.8003, 12.402)   // ~33 m a nord del centro della strada diretta
        val r = route(graph(WALK or BIKE), TravelMode.WALK, from = user)!!
        assertEquals(user, r.points[0])
        assertEquals(41.800, r.points[1].lat, 1e-6)  // il punto sulla strada
        assertEquals(12.402, r.points[1].lon, 1e-6)
        assertEquals(station, r.points.last())
    }

    @Test fun `una stradina staccata piu vicina non blocca il percorso, e il primo tratto e un salto`() {
        // Vialetto isolato (nodi 3–4) a ~10 m da te; la rete vera è a ~110 m più a sud.
        val user = GeoPoint(41.801, 12.402)
        val g = WalkGraph.of(
            nodes = listOf(p, t, nNorth, GeoPoint(41.8011, 12.4015), GeoPoint(41.8011, 12.4025)),
            edges = listOf(
                EdgeSpec(0, 1, WALK or BIKE),
                EdgeSpec(0, 2, WALK or BIKE),
                EdgeSpec(2, 1, WALK or BIKE),
                EdgeSpec(3, 4, WALK or BIKE),   // l'isola
            ),
            arrivals = arrivals,
        )
        val r = route(g, TravelMode.WALK, from = user)!!
        assertEquals(user, r.points[0])
        assertTrue(r.gap)
        assertEquals(station, r.points.last())
        // A ridosso della strada niente salto
        assertTrue(!route(g, TravelMode.WALK, from = GeoPoint(41.8001, 12.402))!!.gap)
    }

    @Test fun `fuori dalla rete o oltre il limite di tempo niente percorso`() {
        val g = graph(WALK or BIKE)
        assertNull(route(g, TravelMode.WALK, from = GeoPoint(41.83, 12.40)))  // ~3 km dalla strada
        assertNull(route(g, TravelMode.WALK, max = 2.0))                     // servono ~4,5 min
        assertNull(WalkRouter.route(g, p, "inesistente", TravelMode.WALK, 60.0))
    }

    @Test fun `il punto di partenza su un arco con forma segue la forma`() {
        // Arco a "L": P -> (41.802, 12.400) -> T2 (41.802, 12.403)
        val corner = GeoPoint(41.802, 12.400)
        val t2 = GeoPoint(41.802, 12.403)
        val g = WalkGraph.of(
            listOf(p, t2),
            listOf(EdgeSpec(0, 1, WALK, mid = listOf(corner))),
            listOf(WalkGraph.Arrival("fittizia", 1, t2)),
        )
        val user = GeoPoint(41.801, 12.4001)   // sul primo tratto (verticale)
        val r = WalkRouter.route(g, user, "fittizia", TravelMode.WALK, 60.0)!!
        assertTrue(r.points.any { Geo.distanceMeters(it, corner) < 1 })
        assertEquals(t2, r.points.last())
    }

    // ---- File vero (assets/walk.bin) con le coordinate delle stazioni ----

    private val stations = ScheduleParser.parse(File("src/main/assets/schedule.json").readText()).stations
    private val real: WalkGraph by lazy {
        val t0 = System.nanoTime()
        WalkGraph.parse(File("src/main/assets/walk.bin").readBytes()).also {
            println("walk.bin: ${it.nodeCount} nodi, ${it.edgeCount} archi, letto in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }

    private fun at(id: String) = stations.first { it.id == id }.let { GeoPoint(it.lat, it.lon) }

    @Test fun `walk_bin ha un arrivo per ogni stazione`() {
        val withArrival = real.arrivals.map { it.stationId }.toSet()
        stations.forEach { assertTrue("manca ${it.id}", it.id in withArrival) }
    }

    @Test fun `a piedi da Lido Nord a Lido Centro`() {
        val from = at("lido_nord")
        val air = Geo.distanceMeters(from, at("lido_centro"))
        val t0 = System.nanoTime()
        val r = WalkRouter.route(real, from, "lido_centro", TravelMode.WALK, 60.0)
        println("Lido Nord -> Lido Centro a piedi: ${r?.minutes} min, ${r?.meters} m, calcolo ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertNotNull(r)
        assertTrue(r!!.minutes >= air / 80 - 0.5 && r.minutes <= 3 * air / 80)
    }

    @Test fun `in bici da Castel Fusano a Stella Polare e da Acilia a Casal Bernocchi`() {
        for ((a, b) in listOf("castel_fusano" to "stella_polare", "acilia" to "casal_bernocchi")) {
            val from = at(a)
            val air = Geo.distanceMeters(from, at(b))
            val t0 = System.nanoTime()
            val r = WalkRouter.route(real, from, b, TravelMode.BIKE, 25.0)
            println("$a -> $b in bici: ${r?.minutes} min, ${r?.meters} m, calcolo ${(System.nanoTime() - t0) / 1_000_000} ms")
            assertNotNull(r)
            assertTrue(r!!.meters >= air - 50 && r.meters <= 3 * air)
        }
    }
}
