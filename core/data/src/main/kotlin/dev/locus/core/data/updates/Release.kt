package dev.locus.core.data.updates

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A published Locus release, as much of GitHub's "latest release" answer as the update check uses
 * (https://docs.github.com/en/rest/releases/releases#get-the-latest-release). Field names are GitHub's.
 */
@Serializable
data class Release(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    /** The release notes, GitHub-flavoured Markdown. */
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<ReleaseAsset> = emptyList(),
) {
    /** The version the tag names: "v0.1.5" is 0.1.5. */
    val version: String get() = tag.removePrefix("v").removePrefix("V")
}

@Serializable
data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
    /**
     * "sha256:<hex>", worked out by GitHub at upload (since June 2025,
     * https://github.blog/changelog/2025-06-03-releases-now-expose-digests-for-release-assets/). Null on
     * older assets, which weren't backfilled.
     */
    val digest: String? = null,
)

internal object ReleaseJson {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** The release in [text], or null when it isn't a release object GitHub would send. */
    fun parse(text: String): Release? = try {
        json.decodeFromString(Release.serializer(), text).takeIf { it.tag.isNotBlank() }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun encode(release: Release): String = json.encodeToString(Release.serializer(), release)
}
