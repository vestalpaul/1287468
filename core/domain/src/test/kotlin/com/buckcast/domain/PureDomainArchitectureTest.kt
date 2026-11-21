package com.buckcast.domain

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PureDomainArchitectureTest {

    @Test
    fun `core domain production sources contain zero android or androidx imports and no metric unit leaks`() {
        val mainSourceRoot = File("src/main/kotlin")
        assertTrue(mainSourceRoot.exists() && mainSourceRoot.isDirectory, "Expected src/main/kotlin under :core:domain")

        val ktFiles = mainSourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(ktFiles.isNotEmpty(), "Expected Kotlin files in :core:domain")

        val forbiddenImports = listOf(
            "import android.",
            "import androidx.",
            "import dagger.",
            "import org.maplibre.",
            "import retrofit2.",
        )

        val forbiddenMetricTokens = listOf("SpeedKph", "GustKph", "PressureHpa", "TempC:")

        for (file in ktFiles) {
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                for (prefix in forbiddenImports) {
                    assertTrue(
                        !trimmed.startsWith(prefix),
                        "Forbidden import '$trimmed' in ${file.path}:${index + 1}",
                    )
                }
                if (trimmed.startsWith("val ") || trimmed.startsWith("var ")) {
                    for (token in forbiddenMetricTokens) {
                        assertTrue(
                            !trimmed.contains(token),
                            "Metric unit leak '$token' in ${file.path}:${index + 1}",
                        )
                    }
                }
            }
        }
    }
}
