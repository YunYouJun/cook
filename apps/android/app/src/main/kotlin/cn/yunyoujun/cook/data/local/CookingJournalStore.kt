package cn.yunyoujun.cook.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Serializable
sealed interface JournalRecipeId {
    @Serializable
    data class Catalog(val id: Int) : JournalRecipeId

    @Serializable
    data class Personal(val customId: String) : JournalRecipeId
}

@Serializable
data class CookingRecord(
    val recipeId: JournalRecipeId,
    val recipeName: String,
    val dateMs: Long,
    val id: String = UUID.randomUUID().toString(),
)

@Serializable
data class LearningConfirmation(val record: CookingRecord, val autoCookedId: String?)

data class JournalEntry(val record: CookingRecord, val cookedId: String?, val learnedId: String?) {
    val learned: Boolean get() = learnedId != null
}

@Serializable
data class CookingJournal(
    val cooked: List<CookingRecord> = emptyList(),
    val learned: List<CookingRecord> = emptyList(),
) {
    fun cookedOn(recipeId: JournalRecipeId, dateMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        cooked.any { it.recipeId == recipeId && sameDay(it.dateMs, dateMs, zone) }

    fun entries(learnedOnly: Boolean, zone: ZoneId = ZoneId.systemDefault()): List<JournalEntry> {
        if (learnedOnly) return learned.sortedByDescending { it.dateMs }.map { JournalEntry(it, null, it.id) }
        val milestones = learned.associateBy { it.recipeId }
        val joined = mutableSetOf<String>()
        val timeline = cooked.map { record ->
            val milestone = milestones[record.recipeId]?.takeIf { sameDay(it.dateMs, record.dateMs, zone) }
            milestone?.let { joined.add(it.id) }
            JournalEntry(record, record.id, milestone?.id)
        }
        return (timeline + learned.filterNot { it.id in joined }.map { JournalEntry(it, null, it.id) })
            .sortedWith(compareByDescending<JournalEntry> { it.record.dateMs }.thenBy { it.record.id })
    }
}

private fun sameDay(firstMs: Long, secondMs: Long, zone: ZoneId): Boolean =
    Instant.ofEpochMilli(firstMs).atZone(zone).toLocalDate() ==
        Instant.ofEpochMilli(secondMs).atZone(zone).toLocalDate()

class CookingJournalStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "cooking-journal-v1.json")
    private val initial = runCatching {
        AtomicJsonFiles.readTextRecoverable(file)?.let {
            AtomicJsonFiles.json.decodeFromString<CookingJournal>(it)
        } ?: CookingJournal()
    }
    val loadFailed = initial.isFailure
    private val mutableState = MutableStateFlow(initial.getOrDefault(CookingJournal()))
    val state = mutableState.asStateFlow()

    @Synchronized
    fun recordCooked(recipeId: JournalRecipeId, name: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val journal = state.value
        if (journal.cookedOn(recipeId, nowMs)) return false
        save(journal.copy(cooked = journal.cooked + CookingRecord(recipeId, name, nowMs)))
        return true
    }

    @Synchronized
    fun confirmLearned(
        recipeId: JournalRecipeId,
        name: String,
        nowMs: Long = System.currentTimeMillis(),
    ): LearningConfirmation? {
        val journal = state.value
        if (journal.learned.any { it.recipeId == recipeId }) return null
        val learned = CookingRecord(recipeId, name, nowMs)
        val cooked = if (journal.cookedOn(recipeId, nowMs)) null else CookingRecord(recipeId, name, nowMs)
        save(journal.copy(cooked = journal.cooked + listOfNotNull(cooked), learned = journal.learned + learned))
        return LearningConfirmation(learned, cooked?.id)
    }

    @Synchronized
    fun undoConfirmation(confirmation: LearningConfirmation) {
        val journal = state.value
        if (journal.learned.none { it.id == confirmation.record.id }) return
        save(
            journal.copy(
                cooked = journal.cooked.filterNot { it.id == confirmation.autoCookedId },
                learned = journal.learned.filterNot { it.id == confirmation.record.id },
            ),
        )
    }

    @Synchronized
    fun removeCooked(id: String) = save(state.value.copy(cooked = state.value.cooked.filterNot { it.id == id }))

    @Synchronized
    fun removeLearned(id: String) =
        save(state.value.copy(learned = state.value.learned.filterNot { it.id == id }))

    @Synchronized
    internal fun restoreBackup(backup: CookingJournal) {
        val current = state.value
        val recordIds = (current.cooked + current.learned).mapTo(HashSet()) { it.id }
        val learned = current.learned.mapTo(HashSet()) { it.recipeId }
        save(
            CookingJournal(
                cooked = current.cooked + backup.cooked.filterNot { it.id in recordIds },
                learned = current.learned + backup.learned.filterNot { it.id in recordIds || it.recipeId in learned },
            ),
        )
    }

    private fun save(journal: CookingJournal) {
        check(!loadFailed) { "做菜足迹暂时无法读取，原始记录已保留。" }
        if (state.value == journal) return
        AtomicJsonFiles.writeAtomic(file, journal)
        mutableState.value = journal
    }
}
