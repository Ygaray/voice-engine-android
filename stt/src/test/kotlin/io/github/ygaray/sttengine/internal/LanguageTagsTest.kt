package io.github.ygaray.sttengine.internal

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit coverage for [LanguageTags.regionQualified] — the region-qualification that keeps a bare
 * language tag (the documented `SttConfig.language` default `"en"`) from reaching Google's SODA
 * on-device recognizer, which rejects a region-less locale with `ERROR_LANGUAGE_NOT_SUPPORTED`
 * (code 12). Pure JVM: no Robolectric, locale injected for determinism.
 */
class LanguageTagsTest {

    private val enUS = Locale.US            // en-US
    private val enGB = Locale.UK            // en-GB
    private val deDE = Locale.GERMANY       // de-DE
    private val enNoRegion = Locale.forLanguageTag("en")   // en, no country

    @Test
    fun `a bare tag matching the device language borrows the device's full tag`() {
        assertEquals("en-US", LanguageTags.regionQualified("en", enUS))
        assertEquals("en-GB", LanguageTags.regionQualified("en", enGB))
    }

    @Test
    fun `a bare tag for a different language borrows only the device region`() {
        // es on an en-US device -> es-US, which SODA supports; strictly better than bare "es".
        assertEquals("es-US", LanguageTags.regionQualified("es", enUS))
        assertEquals("fr-DE", LanguageTags.regionQualified("fr", deDE))
    }

    @Test
    fun `an already region-qualified tag is returned unchanged`() {
        assertEquals("en-US", LanguageTags.regionQualified("en-US", enGB))
        assertEquals("de-DE", LanguageTags.regionQualified("de-DE", enUS))
        assertEquals("xx-XX", LanguageTags.regionQualified("xx-XX", enUS))
    }

    @Test
    fun `a device locale with no region to lend leaves a bare tag untouched`() {
        assertEquals("en", LanguageTags.regionQualified("en", enNoRegion))
        assertEquals("es", LanguageTags.regionQualified("es", enNoRegion))
    }

    @Test
    fun `an empty or unparseable tag is returned unchanged`() {
        assertEquals("", LanguageTags.regionQualified("", enUS))
    }
}
