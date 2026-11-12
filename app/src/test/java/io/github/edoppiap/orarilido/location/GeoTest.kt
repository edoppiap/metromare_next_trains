package io.github.edoppiap.orarilido.location

import io.github.edoppiap.orarilido.schedule.Station
import org.junit.Assert.assertEquals
import org.junit.Test

/** Solo coordinate delle stazioni o punti fittizi (vedi CLAUDE.md, privacy). */
class GeoTest {

    private val psp = Station("porta_san_paolo", "Porta San Paolo", 41.875265, 12.481753)
    private val colombo = Station("cristoforo_colombo", "Cristoforo Colombo", 41.714540, 12.322072)
    private val acilia = Station("acilia", "Acilia", 41.783486, 12.360798)

    @Test
    fun `distanza tra i capolinea - circa 22 km in linea d'aria`() {
        val d = Geo.distanceMeters(GeoPoint(psp.lat, psp.lon), colombo)
        assertEquals(22_300.0, d, 500.0)
    }

    @Test
    fun `stesso punto - zero`() {
        assertEquals(0.0, Geo.distanceMeters(GeoPoint(acilia.lat, acilia.lon), acilia), 0.001)
    }

    @Test
    fun `ordinamento per distanza`() {
        // Punto fittizio poco a nord di Acilia.
        val here = GeoPoint(41.79, 12.36)
        assertEquals(listOf(acilia, colombo, psp), Geo.sortByDistance(listOf(psp, colombo, acilia), here))
    }

    @Test
    fun `formato della distanza`() {
        assertEquals("80 m", Geo.formatDistance(83.0))
        assertEquals("350 m", Geo.formatDistance(347.0))
        assertEquals("1,2 km", Geo.formatDistance(1_234.0))
        assertEquals("15 km", Geo.formatDistance(15_400.0))
    }
}
