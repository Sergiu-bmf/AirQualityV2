package com.example.android_app

import com.example.android_app.data.NotificationPrefs
import com.example.android_app.ui.components.SensorConversions
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorConversionsTest {

    // ---------- Light ----------

    @Test
    fun `ldr resistance follows the documented divider`() {
        // 5V -> LDR -> A1 -> 10k -> GND. Half scale means the two legs are equal.
        assertEquals(10_000f, SensorConversions.ldrResistanceOhms(511.5f)!!, 50f)
        // Brighter light pulls the tap up, so the LDR's own resistance falls.
        assertTrue(SensorConversions.ldrResistanceOhms(900f)!! < SensorConversions.ldrResistanceOhms(300f)!!)
    }

    @Test
    fun `a pinned divider has no defined resistance`() {
        assertNull(SensorConversions.ldrResistanceOhms(0f))
        assertNull(SensorConversions.ldrResistanceOhms(1023f))
        assertNull(SensorConversions.lux(0f))
    }

    @Test
    fun `lux rises with the raw reading`() {
        val dim = SensorConversions.lux(300f)!!
        val bright = SensorConversions.lux(800f)!!
        assertTrue("lux must increase with light, got $dim then $bright", bright > dim)
    }

    @Test
    fun `saturation is capped rather than exploding the chart scale`() {
        // One ADC step below the rail implies a near-zero resistance, which the exponent
        // would otherwise turn into millions of lux and flatten every real reading.
        val nearRail = SensorConversions.lux(1022f)!!
        assertTrue("expected a cap, got $nearRail", nearRail <= 100_000f)
    }

    // ---------- Gas ----------

    @Test
    fun `gas ratio is unavailable until a clean-air baseline is measured`() {
        // Guards the shipped state: no baseline set, so no ratio is invented.
        assertNull(SensorConversions.GAS_CLEAN_AIR_RAW)
        assertNull(SensorConversions.gasRatio(462f))
    }

    // ---------- Flame ----------

    @Test
    fun `flame percent is measured against the alarm threshold`() {
        // The sketch's own calibration: ~3 raw at rest, ~700 with a flame held close.
        assertEquals(100f, SensorConversions.flamePercent(150f), 0.01f)
        assertEquals(2f, SensorConversions.flamePercent(3f), 0.1f)
        assertTrue(SensorConversions.flamePercent(700f) > 100f)
    }

    @Test
    fun `flame percent stays honest at zero`() {
        assertEquals(0f, SensorConversions.flamePercent(0f), 0.001f)
    }
}

class NotificationPrefsTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun prefs(body: String) = json.decodeFromString<NotificationPrefs>(body)

    // The launch prompt asks whenever no address is set , not "if never asked". These pin
    // that rule, because it is the difference between a fire alarm that keeps reminding
    // you it isn't wired up and one that goes quiet after a single stray tap.

    @Test
    fun `no address set means the launch prompt is shown`() {
        val p = prefs("""{"device_id":"arduino-01","configured":false,"channels":[],"email":null,"email_status":"none"}""")
        assertEquals(false, p.emailEnabled)
        assertTrue(p.email.isNullOrBlank())
    }

    @Test
    fun `declining is recorded server-side but still leaves no address, so it asks again`() {
        // configured=true tells the *pipeline* the silence is deliberate; it deliberately
        // does not stop the app asking, because no address was ever provided.
        val p = prefs("""{"device_id":"arduino-01","configured":true,"channels":[],"email":null,"email_status":"none"}""")
        assertEquals(true, p.configured)
        assertEquals(false, p.emailEnabled)
        assertTrue(p.email.isNullOrBlank())
    }

    @Test
    fun `an address awaiting confirmation still counts as set and stops the prompt`() {
        // Pending is explained in the Alerts sheet; re-asking for an address already given
        // would be noise.
        val p = prefs("""{"device_id":"arduino-01","configured":true,"channels":["email"],"email":"a@b.co","email_status":"pending"}""")
        assertEquals(true, p.emailEnabled)
        assertEquals("a@b.co", p.email)
    }

    @Test
    fun `a response without the configured field is not treated as unconfigured`() {
        // An older Lambda omits it. The app no longer reads it, but the pipeline does, and
        // the default must not turn a working setup into a silent one.
        val p = prefs("""{"device_id":"arduino-01","channels":["email"],"email":"a@b.co","email_status":"confirmed"}""")
        assertEquals(true, p.configured)
        assertEquals(true, p.emailEnabled)
    }
}
