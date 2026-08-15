package com.lopleec.kotj.data

internal data class LocalMergeNote(
    val id: String,
    val updatedAt: Long,
)

internal data class BackupMergePlan(
    val categoriesToInsert: List<RestoredCategory>,
    val cloudNotesToInstall: List<RestoredNote>,
    val importedCloudNoteIds: Set<String>,
    val localNoteIdsToKeep: Set<String>,
    val finalCategoryCount: Int,
    val finalNoteCount: Int,
    val importedNoteCount: Int,
    val updatedNoteCount: Int,
    val retainedLocalNoteCount: Int,
)

/**
 * Builds a deterministic, non-destructive merge plan.
 *
 * Notes are identified by their stable UUID. A cloud copy replaces a local copy only when its
 * updatedAt value is strictly newer; ties stay local so a restore never surprises the user by
 * replacing content that already exists on the device. Notes present on only one side are kept.
 */
internal object BackupMergePlanner {
    fun plan(
        localCategoryIds: Set<String>,
        localNotes: List<LocalMergeNote>,
        cloud: RestoredArchive,
    ): BackupMergePlan {
        require(localNotes.map(LocalMergeNote::id).toSet().size == localNotes.size) {
            "Local notes contain duplicate IDs"
        }
        val localById = localNotes.associateBy(LocalMergeNote::id)
        val cloudNotesToInstall = ArrayList<RestoredNote>(cloud.notes.size)
        val importedCloudNoteIds = linkedSetOf<String>()
        val cloudWinningIds = linkedSetOf<String>()
        var updatedNoteCount = 0

        cloud.notes.forEach { cloudNote ->
            val local = localById[cloudNote.id]
            when {
                local == null -> {
                    cloudNotesToInstall += cloudNote
                    importedCloudNoteIds += cloudNote.id
                    cloudWinningIds += cloudNote.id
                }
                cloudNote.updatedAt > local.updatedAt -> {
                    cloudNotesToInstall += cloudNote
                    cloudWinningIds += cloudNote.id
                    updatedNoteCount++
                }
            }
        }

        val localNoteIdsToKeep = localById.keys - cloudWinningIds
        val categoriesToInsert = cloud.categories.filterNot { it.id in localCategoryIds }
        return BackupMergePlan(
            categoriesToInsert = categoriesToInsert,
            cloudNotesToInstall = cloudNotesToInstall,
            importedCloudNoteIds = importedCloudNoteIds,
            localNoteIdsToKeep = localNoteIdsToKeep,
            finalCategoryCount = (localCategoryIds + cloud.categories.map(RestoredCategory::id)).size,
            finalNoteCount = (localById.keys + cloud.notes.map(RestoredNote::id)).size,
            importedNoteCount = importedCloudNoteIds.size,
            updatedNoteCount = updatedNoteCount,
            retainedLocalNoteCount = localNoteIdsToKeep.size,
        )
    }
}
