package dev.locus.core.agent

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.SkillImport
import dev.locus.core.agent.home.SkillImportException
import dev.locus.core.agent.home.SkillManifest
import dev.locus.core.agent.home.SkillManifestDiff
import dev.locus.core.agent.home.SkillManifestInference
import dev.locus.core.agent.home.SkillReview
import dev.locus.core.agent.home.SkillSource
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Skill security manifests (docs/design/skill-manifests.md): parsing, import review, pins and update diffs. */
class SkillManifestTest {
    private val dir: File = Files.createTempDirectory("home").toFile()
    private val home = AgentHome(dir, clock = { 1_000L })

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun md(front: String, name: String = "pdf-tools") =
        "---\nname: $name\ndescription: Fill and merge PDFs.\n${front.trimEnd()}\n---\n\n# PDF\n\nRun scripts/fill.py.\n"

    private fun parse(front: String) = SkillManifest.parse(md(front))

    private fun fails(front: String): String = assertFailsWith<SkillImportException> { parse(front) }.message.orEmpty()

    // ---- Parser ---------------------------------------------------------------------------------

    @Test
    fun `a skill without manifest fields is undeclared`() {
        val m = SkillManifest.parse(md("license: MIT"))
        assertFalse(m.declared)
        assertTrue(m.capabilities.isEmpty())
        // The existing frontmatter check still passes, unchanged.
        assertEquals("pdf-tools", dev.locus.core.agent.home.SkillFrontmatter.validate(md("license: MIT"))["name"])
    }

    @Test
    fun `flow and block lists, nested dependencies and the pins are read`() {
        val m = parse(
            """
            version: 1.2.0
            capabilities: [files.read, files.write, linux, "mcp:github"]
            network:
              - api.example.com
              - "*.githubusercontent.com"   # raw files
            dependencies:
              apk: [poppler-utils, jq=1.7.1-r0]
              pip:
                - pypdf==4.2.0
              npm: ["@acme/pdf@2.0.1"]
              skills: [pdf-basics]
            source: https://github.com/acme/skills/tree/v1.2.0/pdf
            hash: sha256:${"a".repeat(64)}
            allowed-tools: Bash(git:*) Read
            """.trimIndent(),
        )
        assertTrue(m.declared)
        assertEquals(listOf("files.read", "files.write", "linux", "mcp:github"), m.capabilities)
        assertEquals(listOf("api.example.com", "*.githubusercontent.com"), m.network)
        assertEquals(listOf("poppler-utils", "jq=1.7.1-r0"), m.dependencies.apk)
        assertEquals(listOf("pypdf==4.2.0"), m.dependencies.pip)
        assertEquals(listOf("@acme/pdf@2.0.1"), m.dependencies.npm)
        assertEquals(listOf("pdf-basics"), m.dependencies.skills)
        assertEquals("1.2.0", m.version)
        assertEquals("https://github.com/acme/skills/tree/v1.2.0/pdf", m.source)
        assertEquals("Bash(git:*) Read", m.allowedTools)
        assertEquals(listOf("apk:poppler-utils"), m.dependencies.unpinned())
    }

    @Test
    fun `an empty capabilities list still declares`() {
        val m = parse("capabilities: []")
        assertTrue(m.declared)
        assertTrue(m.capabilities.isEmpty())
    }

    @Test
    fun `tool names may be declared one at a time`() {
        assertEquals(listOf("read_file", "web_search"), parse("capabilities: [read_file, web_search]").capabilities)
    }

    @Test
    fun `malformed manifests fail with clear messages`() {
        assertTrue("Unknown capability “root”" in fails("capabilities: [root]"))
        assertTrue("must be a list" in fails("capabilities: linux web"))
        assertTrue("isn't valid" in fails("version: latest"))
        assertTrue("hosts, not links" in fails("network: [https://api.example.com/v1]"))
        assertTrue("has a port" in fails("network: [api.example.com:8443]"))
        assertTrue("isn't a host" in fails("network: [not_a host]"))
        assertTrue("Unknown dependency kind “cargo”" in fails("dependencies:\n  cargo: [ripgrep]"))
        assertTrue("dependencies is a map" in fails("dependencies: [jq]"))
        assertTrue("pip dependency" in fails("dependencies:\n  pip: [\"pypdf; rm -rf /\"]"))
        assertTrue("https://" in fails("source: http://example.com/skill"))
        assertTrue("64 hex" in fails("hash: md5:abc"))
        assertTrue("invalid mcp id" in fails("capabilities: [\"mcp:../x\"]"))
        assertTrue("Only mcp:<id>" in fails("capabilities: [\"web:google\"]"))
        assertTrue("the limit is 64" in fails("capabilities: [${(1..65).joinToString { "files.read" }}]"))
    }

    @Test
    fun `a broken manifest on disk declares nothing, failing closed`() {
        val (m, problem) = SkillManifest.read(md("capabilities: [root]"))
        assertTrue(m.declared)
        assertTrue(m.capabilities.isEmpty())
        assertNotNull(problem)
    }

    // ---- Import: checks, pins and the review sheet ----------------------------------------------

    private fun bundle(front: String, files: Map<String, ByteArray> = mapOf("scripts/fill.py" to "print(1)".toByteArray()), url: String = "pdf.zip") =
        SkillImport.bundle(files + ("SKILL.md" to md(front).toByteArray()), SkillSource(kind = "zip", url = url))

    @Test
    fun `an import is pinned with its version and content hash`() {
        val b = bundle("version: 1.0.0\ncapabilities: [files.read]")
        assertEquals("1.0.0", b.source.version)
        assertEquals(SkillManifest.contentHash(b.skillMd, b.files), b.source.contentHash)
        home.draftImportedSkill(b)
        val draft = home.skillDrafts().single()
        assertEquals(b.source.contentHash, draft.source?.contentHash)
        assertEquals(listOf("files.read"), draft.manifest.capabilities)
        assertEquals(b.source.contentHash, home.skillContentHash("pdf-tools", draft = true))
    }

    @Test
    fun `a declared hash must match the files`() {
        val files = mapOf("scripts/fill.py" to "print(1)".toByteArray())
        val good = SkillManifest.filesHash(files)
        assertEquals(good, bundle("hash: $good", files).manifest.hash)
        val e = assertFailsWith<SkillImportException> { bundle("hash: $good", mapOf("scripts/fill.py" to "print(2)".toByteArray())) }
        assertTrue("don't match the hash" in e.message.orEmpty())
    }

    @Test
    fun `an import can't plant Locus's own marker files`() {
        val b = bundle("capabilities: []", mapOf(".locus-inferred" to "x".toByteArray(), "a.md" to "a".toByteArray()))
        assertEquals(setOf("a.md"), b.files.keys)
    }

    @Test
    fun `the review lists the manifest in plain words`() {
        val r = SkillReview.of(
            bundle(
                "version: 2.0.0\ncapabilities: [linux, files.write]\nnetwork: [api.example.com, \"*.cdn.example\"]\n" +
                    "dependencies:\n  apk: [poppler-utils]\n  pip: [pypdf==4.2.0]\nallowed-tools: Bash Read",
            ),
        )
        assertTrue(r.declared)
        assertTrue(r.capabilities.first().startsWith("Can run Linux commands"))
        assertEquals(listOf("Can contact api.example.com", "Can contact cdn.example and its subdomains"), r.network)
        assertEquals(listOf("Alpine package poppler-utils (not pinned)", "Python package pypdf==4.2.0"), r.dependencies)
        assertEquals("2.0.0", r.version)
        assertTrue(r.hash.startsWith("sha256:"))
        assertEquals("the file pdf.zip", r.source)
        assertTrue(r.notes.any { "aren't limited to the hosts" in it })
        assertTrue(r.notes.any { "allowed-tools" in it })
        assertNull(r.diff)
    }

    @Test
    fun `an undeclared skill says it gets normal permissions`() {
        val r = SkillReview.of(bundle("license: MIT"))
        assertFalse(r.declared)
        assertEquals(SkillReview.UNDECLARED, r.notes.first())
        assertEquals("This skill doesn't declare what it needs; it gets your normal permissions.", SkillReview.UNDECLARED)
    }

    // ---- Updates: the diff ----------------------------------------------------------------------

    private fun installed(front: String, files: Map<String, ByteArray> = mapOf("scripts/fill.py" to "print(1)".toByteArray())): AgentHome.Skill {
        home.draftImportedSkill(bundle(front, files))
        home.approveSkill("pdf-tools")
        return home.skill("pdf-tools")!!
    }

    private fun update(old: String, new: String, newFiles: Map<String, ByteArray> = mapOf("scripts/fill.py" to "print(1)".toByteArray())): SkillManifestDiff {
        val active = installed(old)
        return SkillReview.of(bundle(new, newFiles), active, home.skillContentHash("pdf-tools", draft = false)).diff!!
    }

    @Test
    fun `grown capabilities, hosts and dependencies need confirming`() {
        val d = update(
            "version: 1.0.0\ncapabilities: [files.read]\nnetwork: [api.example.com]",
            "version: 1.1.0\ncapabilities: [files.read, linux]\nnetwork: [api.example.com, evil.example]\ndependencies:\n  npm: [left-pad@1.3.0]",
        )
        assertTrue(d.grown)
        assertEquals(listOf("linux"), d.addedCapabilities)
        assertEquals(listOf("evil.example"), d.addedHosts)
        assertEquals(listOf("npm:left-pad@1.3.0"), d.addedDependencies)
        assertTrue(d.lines().first().startsWith("New: can run Linux commands"))
        assertTrue("New: can contact evil.example" in d.lines())
        assertFalse(d.changedWithoutVersionBump)
    }

    @Test
    fun `shrunk permissions don't count as growth`() {
        val d = update("version: 1.0.0\ncapabilities: [files.read, linux]", "version: 1.1.0\ncapabilities: [files.read]")
        assertFalse(d.grown)
        assertTrue(d.shrunk)
        assertEquals(listOf("linux"), d.removedCapabilities)
        assertTrue(d.lines().single().startsWith("Removed: can run Linux commands"))
    }

    @Test
    fun `the same skill again is the same`() {
        val d = update("version: 1.0.0\ncapabilities: [files.read]", "version: 1.0.0\ncapabilities: [files.read]")
        assertTrue(d.same)
        assertFalse(d.grown)
        assertFalse(d.contentChanged)
        assertTrue(d.lines().isEmpty())
    }

    @Test
    fun `changed files without a version bump are flagged`() {
        val d = update("version: 1.0.0\ncapabilities: [files.read]", "version: 1.0.0\ncapabilities: [files.read]", mapOf("scripts/fill.py" to "print(2)".toByteArray()))
        assertTrue(d.contentChanged)
        assertTrue(d.changedWithoutVersionBump)
        assertFalse(d.grown)
        assertFalse(d.same)
    }

    @Test
    fun `dropping the manifest is growth, adding one is not`() {
        val dropped = update("capabilities: [files.read]", "license: MIT")
        assertTrue(dropped.becameUndeclared)
        assertTrue(dropped.grown)
        assertTrue(dropped.lines().first().contains("normal permissions"))
        home.removeSkill("pdf-tools")
        val added = update("license: MIT", "capabilities: [files.read]")
        assertTrue(added.becameDeclared)
        assertFalse(added.grown)
    }

    @Test
    fun `a widened host pattern is growth`() {
        val d = update("network: [api.example.com]", "network: [\"*.example.com\"]")
        assertEquals(listOf("*.example.com"), d.addedHosts)
        assertTrue(d.grown)
    }

    @Test
    fun `a different source is noted`() {
        val active = installed("capabilities: [files.read]")
        val other = SkillImport.bundle(mapOf("SKILL.md" to md("capabilities: [files.read]").toByteArray()), SkillSource(kind = "zip", url = "other.zip"))
        assertTrue(SkillReview.of(other, active).diff!!.sourceChanged)
    }

    // ---- skill_create: inferred manifests -------------------------------------------------------

    @Test
    fun `an agent draft gets a manifest inferred from what it uses`() {
        home.draftSkill(
            "weekly-report", "Build the weekly report.",
            "1. Use read_file on notes/*.md.\n2. Run `apk add poppler-utils` then `pip install pypdf==4.2.0` with run_command.\n3. fetch_url https://api.example.com/v1/stats\n",
            mapOf("scripts/build.py" to "print('hi')"),
        )
        val draft = home.skillDrafts().single()
        assertTrue(draft.manifestInferred)
        assertNull(draft.manifestProblem)
        val m = draft.manifest
        assertTrue(m.declared)
        assertTrue("files.read" in m.capabilities)
        assertTrue("linux" in m.capabilities)
        assertFalse("web" in m.capabilities)
        assertEquals(listOf("api.example.com"), m.network)
        assertEquals(listOf("poppler-utils"), m.dependencies.apk)
        assertEquals(listOf("pypdf==4.2.0"), m.dependencies.pip)
        assertFalse(".locus-inferred" in draft.files)
        assertTrue(draft.raw.contains("# Inferred by Locus"))
    }

    @Test
    fun `instructions that use nothing stay undeclared`() {
        assertFalse(SkillManifestInference.infer("Answer in a friendly tone and keep it short.", emptyMap()).declared)
    }

    @Test
    fun `every tool belongs to a group or is always allowed`() {
        val loose = SkillScope.knownTools().filter { SkillScope.groupOf(it) == null && !SkillScope.isBaseline(it) }
        assertTrue(loose.isEmpty(), "Tools without a skill capability group: $loose")
    }

    @Test
    fun `the manifest doesn't change the skill catalog in the prompt`() {
        installed("capabilities: [files.read]\nnetwork: [api.example.com]")
        assertTrue("- pdf-tools: Fill and merge PDFs." in home.snapshot())
    }
}
