package dev.soupslurpr.beautyxt

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the generated notices distributed in every application variant. */
class ThirdPartyNoticesTest {
    private val noticesFile = File("src/main/res/raw/third_party_notices.txt")

    @Test
    fun noticesCoverShippedLicenseFamilies() {
        val notices = noticesFile.readText()

        listOf(
            "Apache License 2.0",
            "MIT License",
            "ISC License",
            "Unicode License v3",
            "Mozilla Public License 2.0",
            "libyuv",
            "Eclipse Jakarta Dependency Injection notice"
        ).forEach { requiredText ->
            assertTrue("missing $requiredText", requiredText in notices)
        }
    }

    @Test
    fun noticesCoverRepresentativeDependencyGraphs() {
        val notices = noticesFile.readText()

        listOf(
            "androidx.compose.material3:material3:1.6.0-alpha01",
            "androidx.camera:camera-core:1.7.0",
            "org.jetbrains.kotlin:kotlin-stdlib:2.4.21",
            "pulldown-cmark 0.13.6",
            "qrcode 0.14.1",
            "quircs 0.10.3",
            "rxing 0.9.4",
            "2010-2012 Daniel Beer",
            "unicode-ident 1.0.27",
            "skrifa 0.49.0",
            "skrifa 0.50.0",
            "read-fonts 0.47.0",
            "font-types 0.12.7",
            "ratex-parser 0.1.16",
            "ratex-katex-fonts 0.1.16",
            "Copyright (c) erweixin",
            "Copyright (c) 2013-2020 Khan Academy and other contributors",
            "SIL OPEN FONT LICENSE",
            "Reserved Font Name KaTeX_Main",
            "merman 0.11.0",
            "usvg 0.50.0",
            "fontdb inlined ttf-parser subset",
            "harfrust 0.15.0",
            "html5ever 0.40.1",
            "Jorge Aparicio",
            "https://crates.io/api/v1/crates/cssparser/0.38.0/download",
            "https://crates.io/api/v1/crates/cssparser-macros/0.7.1/download",
            "https://crates.io/api/v1/crates/dtoa-short/0.3.5/download"
        ).forEach { dependency ->
            assertTrue("missing $dependency", dependency in notices)
        }
    }

    @Test
    fun materialSymbolsIncludeAppearanceControls() {
        val section = noticesFile.readText().substringAfter("Google Material Symbols\n", "").substringBefore("\n\n\n")
        val icons = section.substringAfter("Icons: ", "").substringBefore('.')
            .split(',').map(String::trim).toSet()
        listOf("add", "error", "remove").forEach { assertTrue("missing Material Symbol $it", it in icons) }
    }

    @Test
    fun noticesContainNoGenerationArtifacts() {
        val notices = noticesFile.readText()

        listOf(
            "{{",
            "&quot;",
            "MISSING_",
            "<year>",
            "<copyright holders>",
            "/home/",
            "C:\\"
        ).forEach { artifact -> assertFalse("contains $artifact", artifact in notices) }
    }
}
