package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.core.AppLanguage
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun unknownStoredValueFallsBackToSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStoredValue("invalid"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStoredValue(null))
    }

    @Test
    fun systemLanguageUsesSimplifiedChineseOnlyForChina() {
        assertEquals(AppLanguage.ZH_CN, AppLanguage.SYSTEM.resolve(Locale.SIMPLIFIED_CHINESE))
        assertEquals(AppLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.US))
        assertEquals(AppLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.TRADITIONAL_CHINESE))
    }

    @Test
    fun explicitLanguageAlwaysWins() {
        assertEquals(AppLanguage.EN, AppLanguage.EN.resolve(Locale.SIMPLIFIED_CHINESE))
        assertEquals(AppLanguage.ZH_CN, AppLanguage.ZH_CN.resolve(Locale.US))
    }
}
