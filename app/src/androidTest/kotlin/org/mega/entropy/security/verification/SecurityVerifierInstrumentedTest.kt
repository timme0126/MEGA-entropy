package org.mega.entropy.security.verification

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityVerifierInstrumentedTest {
    @Test fun installedApplicationDoesNotRequestInternet() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
    }
}
