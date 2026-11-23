package pl.ballistics.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindForecastTest {
    @Test
    fun readsTheReasonOpenMeteoGives() {
        assertEquals("Daily API request limit exceeded", WindForecast.reason("""{"error":true,"reason":"Daily API request limit exceeded"}"""))
    }

    @Test
    fun noReasonInAnHtmlPage() {
        assertNull(WindForecast.reason("<html><body>403 Forbidden</body></html>"))
        assertNull(WindForecast.reason("""{"error":true,"reason":""}"""))
    }
}
