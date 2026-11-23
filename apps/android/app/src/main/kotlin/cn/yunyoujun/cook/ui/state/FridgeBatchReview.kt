package cn.yunyoujun.cook.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import cn.yunyoujun.cook.data.remote.AccountSyncUiState
import cn.yunyoujun.cook.data.remote.FridgeVisionException
import cn.yunyoujun.cook.data.remote.FridgeVisionProvider
import cn.yunyoujun.cook.data.remote.FridgeVisionService
import cn.yunyoujun.cook.data.remote.MembershipEntitlement
import cn.yunyoujun.cook.data.remote.MembershipSession
import cn.yunyoujun.cook.data.remote.YunlefunAccountException
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** Retains the request and edits for this photo until the photo or account changes. */
internal class FridgeBatchReview(
    val jpeg: ByteArray,
    private val service: FridgeVisionService,
    private val sessions: YunlefunSessionManager,
    private val normalize: (String) -> String,
    private val scope: CoroutineScope,
) {
    sealed interface Phase {
        data object Checking : Phase
        data class LoginRequired(val sessionRejected: Boolean = false) : Phase
        data class MembershipRequired(
            val session: MembershipSession,
            val checkedEntitlement: MembershipEntitlement?,
        ) : Phase
        data class Unavailable(val message: String) : Phase
        data class Ready(
            val provider: FridgeVisionProvider,
            val uploading: Boolean = false,
            val message: String? = null,
            val providerCheck: Any = Any(),
        ) : Phase
        data class Results(
            val candidates: List<Candidate>,
            val saving: Boolean = false,
            val saveError: Boolean = false,
        ) : Phase
    }

    data class Candidate(val name: String, val included: Boolean = true)

    var phase by mutableStateOf<Phase>(Phase.Checking)
        private set
    val isSaving: Boolean get() = (phase as? Phase.Results)?.saving == true
    private var closed = false
    private var identity: MembershipSession? = null
    private var request: Job? = null
    private val accountChanges = scope.launch {
        launch {
            sessions.state.map { it.membershipSession }.distinctUntilChanged().collect { refreshAccess() }
        }
        // The purchase may finish before or after the vision service denies access.
        combine(sessions.state, snapshotFlow { phase as? Phase.MembershipRequired }) { account, blocked ->
            val entitlement = account.activeMembership()
            blocked?.takeIf {
                account.membershipSession == it.session && entitlement != null && entitlement != it.checkedEntitlement
            }
        }.collect { blocked ->
            if (blocked != null && phase == blocked && sessions.isCurrentMembershipSession(blocked.session)) {
                refreshAccess()
            }
        }
    }

    fun refreshAccess() {
        if (closed || isSaving) return
        request?.cancel()
        val current = sessions.state.value.membershipSession
        identity = current
        phase = Phase.Checking
        request = if (current == null) {
            phase = Phase.LoginRequired()
            null
        } else {
            scope.launch { loadProvider(current) }
        }
    }

    fun recognize(confirmed: Phase.Ready) {
        val ready = phase as? Phase.Ready ?: return
        if (ready.providerCheck !== confirmed.providerCheck || ready.uploading) return
        val current = identity ?: return
        if (!sessions.isCurrentMembershipSession(current)) return
        val checkedEntitlement = sessions.state.value.activeMembership()
        phase = ready.copy(uploading = true, message = null)
        request = scope.launch {
            try {
                val ingredients = sessions.withMembershipSession(current) { credential ->
                    try {
                        service.recognize(jpeg, ready.provider, credential)
                    } catch (_: FridgeVisionException.Login) {
                        loadProvider(
                            current,
                            notice = "已重新核对登录状态，请确认照片接收方后再识别。",
                            rejectedAccessToken = credential.accessToken,
                        )
                        null
                    }
                } ?: return@launch
                val result = ingredients.map(::reviewedName).distinct()
                if (result.any { !FridgeVisionService.validName(it) }) throw FridgeVisionException.InvalidResponse()
                ensureActive()
                if (sessions.isCurrentMembershipSession(current)) {
                    phase = Phase.Results(result.map { Candidate(it) })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FridgeVisionException.ProviderChanged) {
                loadProvider(current, e.message)
            } catch (e: Exception) {
                ensureActive()
                if (sessions.isCurrentMembershipSession(current)) {
                    phase = when (e) {
                        is YunlefunAccountException.ExpiredSession -> Phase.LoginRequired(sessionRejected = true)
                        is FridgeVisionException.Membership -> Phase.MembershipRequired(current, checkedEntitlement)
                        else -> ready.copy(message = e.message ?: "识图失败，请重试。")
                    }
                }
            }
        }
    }

    private suspend fun loadProvider(
        current: MembershipSession,
        notice: String? = null,
        rejectedAccessToken: String? = null,
    ) {
        currentCoroutineContext().ensureActive()
        if (!sessions.isCurrentMembershipSession(current)) return
        val checkedEntitlement = sessions.state.value.activeMembership()
        phase = Phase.Checking
        try {
            val provider = sessions.withMembershipSession(current, rejectedAccessToken) { credential ->
                try {
                    service.memberProvider(credential)
                } catch (e: FridgeVisionException.Login) {
                    currentCoroutineContext().ensureActive()
                    if (rejectedAccessToken != null) throw e
                    sessions.withMembershipSession(current, credential.accessToken, service::memberProvider)
                }
            }
            currentCoroutineContext().ensureActive()
            if (sessions.isCurrentMembershipSession(current)) phase = Phase.Ready(provider, message = notice)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (sessions.isCurrentMembershipSession(current)) {
                phase = when (e) {
                    is YunlefunAccountException.ExpiredSession, is FridgeVisionException.Login ->
                        Phase.LoginRequired(sessionRejected = true)
                    is FridgeVisionException.Membership -> Phase.MembershipRequired(current, checkedEntitlement)
                    else -> Phase.Unavailable(e.message ?: "暂时无法核对会员资格，请重试。")
                }
            }
        }
    }

    fun setIncluded(index: Int, included: Boolean) {
        updateCandidate(index) { it.copy(included = included) }
    }

    fun setName(index: Int, name: String) {
        val canonical = canonicalName(name)
        val limit = FridgeVisionService.MAX_NAME_CODE_POINTS
        val edited = if (canonical.codePointCount(0, canonical.length) > limit) {
            canonical.substring(0, canonical.offsetByCodePoints(0, limit))
        } else name
        updateCandidate(index) { it.copy(name = edited) }
    }

    private fun updateCandidate(index: Int, transform: (Candidate) -> Candidate) {
        val results = phase as? Phase.Results ?: return
        if (results.saving) return
        val candidate = results.candidates.getOrNull(index) ?: return
        val edited = results.candidates.toMutableList().apply { set(index, transform(candidate)) }
        phase = Phase.Results(edited)
    }

    private fun canonicalName(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC)

    private fun reviewedName(name: String): String = canonicalName(normalize(name))

    fun isValid(name: String): Boolean = FridgeVisionService.validName(reviewedName(name))

    fun namesToSave(results: Phase.Results): List<String> =
        results.candidates.filter { it.included }.map { reviewedName(it.name) }.distinct()
            .takeIf { it.all(FridgeVisionService::validName) }.orEmpty()

    fun save(writeItems: suspend (List<String>) -> Unit, onSaved: () -> Unit) {
        val results = phase as? Phase.Results ?: return
        val current = identity ?: return
        if (results.saving || !sessions.isCurrentMembershipSession(current)) return
        val names = namesToSave(results)
        if (names.isEmpty()) return

        phase = results.copy(saving = true, saveError = false)
        request = scope.launch {
            val committed = try {
                withContext(Dispatchers.IO) {
                    if (!sessions.isCurrentMembershipSession(current)) return@withContext false
                    writeItems(names)
                    true
                }
            } catch (_: Exception) {
                ensureActive()
                false
            }
            if (closed) return@launch
            if (committed) {
                onSaved()
            } else {
                phase = results.copy(saveError = true)
                if (!sessions.isCurrentMembershipSession(current)) refreshAccess()
            }
        }
    }

    fun close() {
        closed = true
        accountChanges.cancel()
        request?.cancel()
        identity = null
    }
}

private fun AccountSyncUiState.activeMembership(): MembershipEntitlement? =
    entitlement?.takeIf { it.userID == membershipSession?.subject && it.isActiveAt() }
