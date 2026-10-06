package com.pombo.android.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vendor bundle is the web's vendor build (scripts/update-vendor-bundle.sh),
 * which carries the web's patch to the Streamr SDK. A bundle refreshed from a
 * web checkout without the patch would read the storage node registry on every
 * resend again.
 */
class VendorBundleTest {

    private val bundle = File("src/main/assets/pombo-vendor.bundle.js").readText()

    @Test
    fun `the SDK caches storage node metadata and drops an entry when the node stops answering`() {
        assertTrue(bundle.contains("getStorageNodeMetadata_nonCached"))
        assertTrue(bundle.contains("nodeMetadataCache"))
        assertTrue(bundle.contains("invalidateStorageNodeMetadata"))
    }
}
