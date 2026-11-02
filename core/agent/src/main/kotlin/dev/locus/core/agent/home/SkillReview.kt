package dev.locus.core.agent.home

import dev.locus.core.agent.SkillScope

/**
 * How a skill's manifest changed between the version the user has and the one being imported or
 * approved, like Chrome's permission diff on an extension update. [grown] means it may now do more:
 * the user must confirm before it replaces the old one.
 */
data class SkillManifestDiff(
    val addedCapabilities: List<String> = emptyList(),
    val removedCapabilities: List<String> = emptyList(),
    val addedHosts: List<String> = emptyList(),
    val removedHosts: List<String> = emptyList(),
    val addedDependencies: List<String> = emptyList(),
    val removedDependencies: List<String> = emptyList(),
    /** The old one declared a manifest and the new one doesn't: it would get the user's normal permissions. */
    val becameUndeclared: Boolean = false,
    /** The old one had no manifest and the new one has: it is now held to what it lists. */
    val becameDeclared: Boolean = false,
    val versionBefore: String? = null,
    val versionAfter: String? = null,
    /** Any file differs (by content hash). */
    val contentChanged: Boolean = false,
    /** It comes from somewhere else than the version the user has. */
    val sourceChanged: Boolean = false,
) {
    val grown: Boolean get() = becameUndeclared || addedCapabilities.isNotEmpty() || addedHosts.isNotEmpty() || addedDependencies.isNotEmpty()

    val shrunk: Boolean get() = becameDeclared || removedCapabilities.isNotEmpty() || removedHosts.isNotEmpty() || removedDependencies.isNotEmpty()

    /** The files changed but the version number didn't: either a silent update or a changed copy. */
    val changedWithoutVersionBump: Boolean get() = contentChanged && versionBefore == versionAfter

    /** Nothing at all changed. */
    val same: Boolean get() = !grown && !shrunk && !contentChanged && versionBefore == versionAfter

    /** The changes in plain words, new permissions first. */
    fun lines(): List<String> = buildList {
        if (becameUndeclared) add("New: it no longer declares what it needs, so it would get your normal permissions")
        addedCapabilities.forEach { add("New: ${lowerFirst(SkillScope.describe(it))}") }
        addedHosts.forEach { add("New: ${lowerFirst(SkillScope.describeHost(it))}") }
        addedDependencies.forEach { add("New dependency: ${SkillReview.describeDependency(it)}") }
        if (becameDeclared) add("Now declares what it needs, and is held to it")
        removedCapabilities.forEach { add("Removed: ${lowerFirst(SkillScope.describe(it))}") }
        removedHosts.forEach { add("Removed: ${lowerFirst(SkillScope.describeHost(it))}") }
        removedDependencies.forEach { add("Removed dependency: ${SkillReview.describeDependency(it)}") }
    }

    companion object {
        /** Compares [old] (what the user has, with its pin) with [new]. */
        fun between(
            old: SkillManifest,
            oldHash: String?,
            oldSource: SkillSource?,
            new: SkillManifest,
            newHash: String?,
            newSource: SkillSource?,
        ): SkillManifestDiff {
            val bothDeclared = old.declared && new.declared
            fun added(a: List<String>, b: List<String>) = if (bothDeclared) (b - a.toSet()) else emptyList()
            return SkillManifestDiff(
                addedCapabilities = added(old.capabilities, new.capabilities),
                removedCapabilities = added(new.capabilities, old.capabilities),
                addedHosts = added(old.network, new.network),
                removedHosts = added(new.network, old.network),
                addedDependencies = added(old.dependencies.all(), new.dependencies.all()),
                removedDependencies = added(new.dependencies.all(), old.dependencies.all()),
                becameUndeclared = old.declared && !new.declared,
                becameDeclared = !old.declared && new.declared,
                versionBefore = old.version,
                versionAfter = new.version,
                contentChanged = oldHash != null && newHash != null && oldHash != newHash,
                sourceChanged = oldSource != null && newSource != null && !oldSource.sameOrigin(newSource),
            )
        }

        private fun lowerFirst(s: String) = s.replaceFirstChar { it.lowercaseChar() }
    }
}

/**
 * What the import sheet shows for a skill: its manifest in plain words, where it came from, and, when
 * it would replace a skill of the same name, what changed.
 */
data class SkillReview(
    val name: String,
    val declared: Boolean,
    /** "Can run Linux commands. …", one per declared capability. */
    val capabilities: List<String>,
    /** "Can contact api.example.com", one per host. */
    val network: List<String>,
    /** "Alpine package poppler-utils", with "(not pinned)" where no exact version is given. */
    val dependencies: List<String>,
    /** Warnings worth reading before saving (unpinned packages, `allowed-tools` ignored, …). */
    val notes: List<String>,
    /** Where Locus got it ([SkillSource.describe]). */
    val source: String?,
    /** Where the author says it's published (the manifest's `source`). */
    val declaredSource: String?,
    val version: String?,
    /** The pin: [SkillManifest.contentHash] of what's being imported. */
    val hash: String,
    /** Whether the author pinned the files with `hash` (checked: an import that doesn't match never gets here). */
    val hashDeclared: Boolean,
    /** Set when it replaces a skill (active or draft) of the same name. */
    val diff: SkillManifestDiff?,
) {
    companion object {
        const val UNDECLARED = "This skill doesn't declare what it needs; it gets your normal permissions."

        /** The review of [bundle]; [previous] is the skill it would replace, with that one's current content hash. */
        fun of(bundle: SkillBundle, previous: AgentHome.Skill? = null, previousHash: String? = null): SkillReview {
            val m = bundle.manifest
            val notes = buildList {
                if (!m.declared) add(UNDECLARED)
                if ("linux" in m.capabilities && m.network.isNotEmpty()) add("Linux commands aren't limited to the hosts listed: only fetches and the browser are.")
                m.dependencies.unpinned().takeIf { it.isNotEmpty() }?.let { add("Not pinned to an exact version: ${it.joinToString { d -> d.substringAfter(':') }}. Each install may get a newer release.") }
                m.allowedTools?.let { add("It lists allowed-tools ($it). Locus doesn't pre-approve tools from a skill; your permissions still apply.") }
                if (m.version == null) add("It has no version number, so a changed copy can't be told apart from an update.")
            }
            val diff = previous?.let {
                // The pin recorded at import wins: a skill saved under a new name has a rewritten SKILL.md.
                SkillManifestDiff.between(it.manifest, it.source?.contentHash ?: previousHash, it.source, m, bundle.contentHash, bundle.source)
            }
            return SkillReview(
                name = bundle.name,
                declared = m.declared,
                capabilities = m.capabilities.map(SkillScope::describe),
                network = m.network.map(SkillScope::describeHost),
                dependencies = m.dependencies.all().map { d -> describeDependency(d) + if (d in m.dependencies.unpinned()) " (not pinned)" else "" },
                notes = notes,
                source = bundle.source.describe(),
                declaredSource = m.source,
                version = m.version,
                hash = bundle.contentHash,
                hashDeclared = m.hash != null,
                diff = diff,
            )
        }

        /** The same summary for a skill already saved (active or draft), for Library. [hash] is empty when it has no pin. */
        fun of(skill: AgentHome.Skill): SkillReview {
            val m = skill.manifest
            return SkillReview(
                name = skill.name,
                declared = m.declared,
                capabilities = m.capabilities.map(SkillScope::describe),
                network = m.network.map(SkillScope::describeHost),
                dependencies = m.dependencies.all().map { d -> describeDependency(d) + if (d in m.dependencies.unpinned()) " (not pinned)" else "" },
                notes = listOfNotNull(
                    UNDECLARED.takeIf { !m.declared },
                    "Linux commands aren't limited to the hosts listed: only fetches and the browser are.".takeIf { "linux" in m.capabilities && m.network.isNotEmpty() },
                ),
                source = skill.source?.describe(),
                declaredSource = m.source,
                version = m.version,
                hash = skill.source?.contentHash.orEmpty(),
                hashDeclared = m.hash != null,
                diff = null,
            )
        }

        /** "apk:jq" → "Alpine package jq", and so on. */
        fun describeDependency(d: String): String {
            val v = d.substringAfter(':')
            return when (d.substringBefore(':')) {
                "apk" -> "Alpine package $v"
                "npm" -> "npm package $v"
                "pip" -> "Python package $v"
                "skill" -> "the skill “$v”"
                else -> d
            }
        }
    }
}
