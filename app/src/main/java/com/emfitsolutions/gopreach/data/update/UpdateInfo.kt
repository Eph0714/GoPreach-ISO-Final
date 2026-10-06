package com.emfitsolutions.gopreach.data.update

/**
 * The update manifest for this app, sourced from GitHub Releases' own public
 * API rather than a bespoke backend — see [UpdateManifestRepository] for why.
 * Every field here maps directly onto GitHub's release/asset JSON.
 */
data class UpdateInfo(
    val version: String,
    val apkUrl: String,
    val releaseNotes: String,
    val releaseDate: String,
    val sha256: String?,
    /** "Required/Critical Update" — no Remind Me Later, not dismissable,
     * shown every time regardless of any snooze. Driven by the release
     * notes themselves rather than a separate manifest field GitHub
     * Releases has no place for: a release is critical when its own notes
     * contain the literal marker `[CRITICAL]` (case-insensitive) — see
     * [UpdateManifestRepository]'s parsing. To ship a mandatory update,
     * include that marker anywhere in the release notes, e.g.
     * "[CRITICAL] Fixes a security issue in..." */
    val isCritical: Boolean = false,
)
