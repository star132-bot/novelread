package com.mkread.app

import android.Manifest
import android.os.Build
import androidx.test.rule.GrantPermissionRule
import org.junit.rules.TestRule

/**
 * Starting narration asks for the notification permission on Android 13+. The system dialog
 * would cover the app under test, so journeys that read aloud grant it up front.
 */
fun grantNotificationPermission(): TestRule =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        TestRule { base, _ -> base }
    }
