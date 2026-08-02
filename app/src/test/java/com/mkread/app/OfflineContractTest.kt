package com.mkread.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class OfflineContractTest {
    @Test
    fun sourceManifest_hasNoNetworkPermissions() {
        val manifest = File("src/main/AndroidManifest.xml").readText(Charsets.UTF_8)

        assertOfflineManifest(manifest)
    }

    private fun assertOfflineManifest(manifest: String) {
        assertFalse(
            "INTERNET permission is forbidden",
            manifest.contains("android.permission.INTERNET"),
        )
        assertFalse(
            "ACCESS_NETWORK_STATE permission is forbidden",
            manifest.contains("android.permission.ACCESS_NETWORK_STATE"),
        )
    }
}
