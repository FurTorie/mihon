package mihon.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mihon.sync.drive.DriveFile

/**
 * What this device knows about one library shard, the last time it reconciled it.
 *
 * Both sides are recorded on purpose:
 *
 * - [remoteVersion] is Drive's own counter. Comparing it — rather than modification times — is what
 *   stops a device re-downloading the shards it just uploaded, with no dependence on clocks agreeing
 *   between devices.
 * - [localVersion] and [localModifiedAt] are re-read *after* a merge. Restoring chapters bumps the
 *   local version through a database trigger, so without this a pull would immediately look like a
 *   local change and be pushed straight back — and the other device would do the same, forever.
 */
@Serializable
data class SyncShardState(
    @SerialName("fileId") val fileId: String,
    @SerialName("remoteVersion") val remoteVersion: String = "",
    @SerialName("localVersion") val localVersion: Long = 0,
    @SerialName("localModifiedAt") val localModifiedAt: Long = 0,
    /**
     * Chapter count and newest chapter modification, as of the last reconciliation.
     *
     * The manga row alone is not enough to notice a source publishing a new release: inserting
     * chapters fires no trigger, so neither the version nor the modification time moves. Without
     * these two the new chapters would sit locally and never be published.
     */
    @SerialName("chapterCount") val chapterCount: Long = -1,
    @SerialName("chapterModifiedAt") val chapterModifiedAt: Long = -1,
    /**
     * Digest of the payload believed to be on Drive right now.
     *
     * This is what actually decides whether to publish. Comparing local counters against themselves
     * cannot tell "the merge accepted the remote value, so there is nothing to send" from "the merge
     * rejected it and the local value must be sent back" — and getting that wrong the second way
     * leaves a device silently holding a change no one else ever receives.
     */
    @SerialName("contentHash") val contentHash: String = "",
    /**
     * The rules [contentHash] was worked out under. A shard reconciled under older ones is rebuilt
     * and compared again even when nothing local moved: before [CURRENT_FORMAT], for one, entries
     * referred to their categories by position rather than by id.
     */
    @SerialName("format") val format: Int = 1,
    /**
     * Checksum of the content behind [remoteVersion]. Drive bumps a file's version again shortly
     * after an upload with nothing changed, which made every device download back the shard it had
     * just published — and record a change in the history that never happened.
     */
    @SerialName("remoteMd5") val remoteMd5: String = "",
) {
    /** Whether Drive still holds what this device last reconciled for the shard. */
    fun matches(remote: DriveFile): Boolean =
        remote.version == remoteVersion || (remoteMd5.isNotEmpty() && remote.md5Checksum == remoteMd5)

    companion object {
        const val CURRENT_FORMAT = 2
    }
}
