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
            "androidx.compose.material3:material3:1.5.0-alpha29",
            "androidx.camera:camera-core:1.6.2",
            "org.jetbrains.kotlin:kotlin-stdlib:2.4.20",
            "pulldown-cmark 0.13.4",
            "qrcode 0.14.1",
            "quircs 0.10.3",
            "rxing 0.9.3",
            "2010-2012 Daniel Beer",
            "unicode-ident 1.0.26",
            "skrifa 0.47.0",
            "read-fonts 0.44.0",
            "font-types 0.12.5",
            "ratex-parser 0.1.14",
            "ratex-katex-fonts 0.1.14",
            "Copyright (c) erweixin",
            "Copyright (c) 2013-2020 Khan Academy and other contributors",
            "SIL OPEN FONT LICENSE",
            "Reserved Font Name KaTeX_Main",
            "merman 0.8.0-alpha.6",
            "usvg 0.48.1",
            "harfrust 0.12.0",
            "html5ever 0.40.1",
            "Jorge Aparicio",
            "https://crates.io/api/v1/crates/cssparser/0.36.0/download",
            "https://crates.io/api/v1/crates/selectors/0.37.0/download"
        ).forEach { dependency ->
            assertTrue("missing $dependency", dependency in notices)
        }
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
