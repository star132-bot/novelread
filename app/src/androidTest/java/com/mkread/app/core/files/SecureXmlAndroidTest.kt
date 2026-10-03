package com.mkread.app.core.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureXmlAndroidTest {
    @Test
    fun safeDocument_parsesOnAndroidXmlImplementation() {
        val bytes = "<root><value>ok</value></root>".toByteArray(Charsets.UTF_8)

        val document = SecureXml.parse(bytes)

        assertEquals("ok", document.getElementsByTagName("value").item(0).textContent)
    }

    @Test
    fun doctype_isRejectedOnAndroidXmlImplementation() {
        val xml = (
            "<!DOCTYPE root [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" +
                "<root>&xxe;</root>"
            )
        val encodings = listOf(
            xml.toByteArray(Charsets.UTF_8),
            byteArrayOf(0xff.toByte(), 0xfe.toByte()) + xml.toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0xfe.toByte(), 0xff.toByte()) + xml.toByteArray(Charsets.UTF_16BE),
        )

        encodings.forEach { bytes ->
            try {
                SecureXml.parse(bytes)
                fail("Expected DOCTYPE to be rejected")
            } catch (_: SecureXmlException) {
                // Expected fail-closed behavior.
            }
        }
    }
}
