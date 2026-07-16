package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserAppInfoTest {
    @Test
    fun sourceCodeUsesCanonicalCrossPlatformRepository() {
        assertEquals(
            "https://github.com/Denuo-Web/hns-dane-browser",
            BrowserAppInfo.SOURCE_CODE_URL,
        )
    }
}
