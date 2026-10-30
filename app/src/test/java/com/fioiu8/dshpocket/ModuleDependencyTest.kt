package com.fioiu8.dshpocket

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keep the package boundaries from silently growing UI dependencies again. */
class ModuleDependencyTest {
    private val sourceRoot: File
        get() = listOf(File("src/main/java/com/fioiu8/dshpocket"), File("app/src/main/java/com/fioiu8/dshpocket"))
            .firstOrNull(File::isDirectory) ?: error("Cannot locate production Kotlin sources")

    @Test
    fun `engine shared data and background services do not import the application UI`() {
        val root = sourceRoot
        val files = listOf("core", "data", "engine", "notify", "overlay").flatMap { sources(File(root, it)) } +
            sources(File(root, "screen")).filter { it.name.endsWith("Service.kt") }

        assertNoImports(files, Regex("^import\\s+com\\.fioiu8\\.dshpocket\\.ui(?:\\.|$)"))
    }

    @Test
    fun `shared values and protocol data are independent of Compose`() {
        val root = sourceRoot
        val files = listOf("core", "data").flatMap { sources(File(root, it)) }

        assertNoImports(files, Regex("^import\\s+androidx\\.compose\\."))
    }

    private fun sources(directory: File): List<File> {
        check(directory.isDirectory) { "Missing source directory: $directory" }
        return directory.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun assertNoImports(files: List<File>, forbidden: Regex) {
        assertTrue("No sources were checked", files.isNotEmpty())
        val violations = files.flatMap { file ->
            file.readLines().filter { forbidden.containsMatchIn(it) }.map { "${file.name}: $it" }
        }
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }
}
