package com.avscode.workspace

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class WorkspaceArchiveManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var projectsDir: File
    private lateinit var archiveManager: WorkspaceArchiveManager

    @Before
    fun setup() {
        projectsDir = tempFolder.newFolder("projects")
        archiveManager = WorkspaceArchiveManager(projectsDir)
    }

    @Test
    fun testListProjects() {
        assertTrue(archiveManager.listProjects().isEmpty())

        val p1 = File(projectsDir, "my-app").apply { mkdirs() }
        val p2 = File(projectsDir, "backend").apply { mkdirs() }
        File(projectsDir, "regular_file.txt").writeText("hello")

        val projects = archiveManager.listProjects()
        assertEquals(2, projects.size)
        assertEquals("backend", projects[0].name)
        assertEquals("my-app", projects[1].name)
    }

    @Test
    fun testExportAndImportZip() {
        val project = File(projectsDir, "test-project").apply { mkdirs() }
        File(project, "index.js").writeText("console.log('hello world');")
        val subDir = File(project, "src").apply { mkdirs() }
        File(subDir, "util.js").writeText("export const x = 42;")

        val out = ByteArrayOutputStream()
        archiveManager.exportProject(project, out, ArchiveFormat.ZIP)
        val zipBytes = out.toByteArray()
        assertTrue(zipBytes.isNotEmpty())

        val targetDir = tempFolder.newFolder("imported-zip")
        val count = archiveManager.importArchive(ByteArrayInputStream(zipBytes), targetDir, ArchiveFormat.ZIP)

        assertEquals(2, count)
        val importedIndex = File(targetDir, "index.js")
        val importedUtil = File(targetDir, "src/util.js")
        assertTrue(importedIndex.exists())
        assertTrue(importedUtil.exists())
        assertEquals("console.log('hello world');", importedIndex.readText())
        assertEquals("export const x = 42;", importedUtil.readText())
    }

    @Test
    fun testExportAndImportTarGz() {
        val project = File(projectsDir, "tar-project").apply { mkdirs() }
        File(project, "README.md").writeText("# Tar Gz Test")

        val out = ByteArrayOutputStream()
        archiveManager.exportProject(project, out, ArchiveFormat.TAR_GZ)
        val tarBytes = out.toByteArray()
        assertTrue(tarBytes.isNotEmpty())

        val targetDir = tempFolder.newFolder("imported-tar")
        val count = archiveManager.importArchive(ByteArrayInputStream(tarBytes), targetDir, ArchiveFormat.TAR_GZ)

        assertEquals(1, count)
        val importedReadme = File(targetDir, "README.md")
        assertTrue(importedReadme.exists())
        assertEquals("# Tar Gz Test", importedReadme.readText())
    }

    @Test
    fun testExportAndImportTarXz() {
        val project = File(projectsDir, "xz-project").apply { mkdirs() }
        File(project, "config.json").writeText("{\"active\": true}")

        val out = ByteArrayOutputStream()
        archiveManager.exportProject(project, out, ArchiveFormat.TAR_XZ)
        val xzBytes = out.toByteArray()
        assertTrue(xzBytes.isNotEmpty())

        val targetDir = tempFolder.newFolder("imported-xz")
        val count = archiveManager.importArchive(ByteArrayInputStream(xzBytes), targetDir, ArchiveFormat.TAR_XZ)

        assertEquals(1, count)
        val importedConfig = File(targetDir, "config.json")
        assertTrue(importedConfig.exists())
        assertEquals("{\"active\": true}", importedConfig.readText())
    }
}

