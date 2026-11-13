package io.github.edoppiap.orarilido.places

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlacesTest {

    private val places = Places.parse(File("src/main/assets/pois.bin").readBytes())

    @Test fun `pois_bin contiene tutti i tipi, attorno alla linea`() {
        assertTrue(places.size > 1000)
        PlaceKind.entries.forEach { k -> assertTrue("manca $k", places.any { it.kind == k }) }
        // Tutti tra Ostia e Roma sud (riquadro largo attorno alle stazioni)
        places.forEach {
            assertTrue(it.point.lat in 41.6..41.95 && it.point.lon in 12.2..12.6)
        }
    }

    @Test fun `i nomi sono letti come testo`() {
        assertTrue(places.any { it.kind == PlaceKind.PHARMACY && it.name.contains("Farmacia", ignoreCase = true) })
    }
}
