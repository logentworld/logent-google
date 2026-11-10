package com.sleepysoong.logent.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAppsTargetTest {
    @Test
    fun targetsUseHttpsAndPackages() {
        GoogleAppsTarget.entries.forEach { target ->
            assertTrue(target.packageName.contains('.'))
            assertTrue(target.webUrl.startsWith("https://"))
            assertTrue(target.displayName.isNotBlank())
        }
    }

    @Test
    fun fromIsCaseInsensitiveAndRejectsUnknown() {
        assertEquals(GoogleAppsTarget.GMAIL, GoogleAppsTarget.from("gmail"))
        assertEquals(GoogleAppsTarget.YOUTUBE, GoogleAppsTarget.from(" YouTube "))
        assertNull(GoogleAppsTarget.from("unknown"))
        assertNull(GoogleAppsTarget.from(null))
    }

    @Test
    fun mapsAndPhotosHaveExpectedWebFallbacks() {
        assertEquals("https://maps.google.com/", GoogleAppsTarget.MAPS.webUrl)
        assertEquals("https://photos.google.com/", GoogleAppsTarget.PHOTOS.webUrl)
    }
}
