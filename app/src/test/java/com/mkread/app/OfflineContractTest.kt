package com.mkread.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Reading and narration stay fully offline; the network is used only for the cloud library
 * (catalog sync and MKauth sign-in). These checks keep that surface from growing silently.
 */
class OfflineContractTest {
    @Test
    fun sourceManifest_requestsOnlyCloudSyncNetworkPermissions() {
        val document = parse(File("src/main/AndroidManifest.xml"))
        val permissions = document.getElementsByTagName("uses-permission")
        val active = buildSet {
            repeat(permissions.length) { index ->
                val element = permissions.item(index) as Element
                if (element.getAttributeNS(TOOLS_NAMESPACE, "node") != "remove") {
                    add(element.getAttributeNS(ANDROID_NAMESPACE, "name"))
                }
            }
        }
        val network = active.filter { it in NETWORK_PERMISSIONS || "NETWORK" in it || "WIFI" in it }.toSet()

        assertEquals(ALLOWED_NETWORK_PERMISSIONS, network)
    }

    @Test
    fun releaseNetworkConfig_forbidsCleartextExceptLoopbackSignIn() {
        val document = parse(File("src/main/res/xml/network_security_config.xml"))
        val base = document.getElementsByTagName("base-config").item(0) as Element
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        val domains = document.getElementsByTagName("domain")
        val cleartextDomains = buildSet {
            repeat(domains.length) { index -> add(domains.item(index).textContent.trim()) }
        }
        assertEquals(setOf("127.0.0.1"), cleartextDomains)
        assertTrue(
            "The release manifest must use the network security config",
            File("src/main/AndroidManifest.xml").readText().contains("@xml/network_security_config"),
        )
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(file)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val TOOLS_NAMESPACE = "http://schemas.android.com/tools"
        val NETWORK_PERMISSIONS = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
        )
        val ALLOWED_NETWORK_PERMISSIONS = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
        )
    }
}
