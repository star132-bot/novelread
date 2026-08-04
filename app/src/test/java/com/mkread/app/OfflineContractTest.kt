package com.mkread.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineContractTest {
    @Test
    fun sourceManifest_hasNoActiveNetworkPermissions() {
        val manifest = File("src/main/AndroidManifest.xml")

        assertOfflineManifest(manifest)
    }

    private fun assertOfflineManifest(manifest: File) {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest)
        val activeForbiddenPermissions = buildList {
            val permissions = document.getElementsByTagName("uses-permission")
            repeat(permissions.length) { index ->
                val element = permissions.item(index)
                val permission = element.attributes
                    .getNamedItemNS(ANDROID_NAMESPACE, "name")
                    ?.nodeValue
                val mergeAction = element.attributes
                    .getNamedItemNS(TOOLS_NAMESPACE, "node")
                    ?.nodeValue
                if (permission in FORBIDDEN_PERMISSIONS && mergeAction != "remove") {
                    add(permission)
                }
            }
        }
        assertTrue(
            "Active network permissions are forbidden: $activeForbiddenPermissions",
            activeForbiddenPermissions.isEmpty(),
        )
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val TOOLS_NAMESPACE = "http://schemas.android.com/tools"
        val FORBIDDEN_PERMISSIONS = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
        )
    }
}
