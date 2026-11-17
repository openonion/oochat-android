package ai.openonion.oochat.ui.rem

import ai.openonion.oochat.di.appContainer
import ai.openonion.oochat.domain.model.RemOutcome
import ai.openonion.oochat.domain.usecase.ConnectToAgentUseCaseContract
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the Rem screen shows. [outcome] is null while a read is in flight.
 * [ownAddress] is this device's identity, for the "ask the owner to add you"
 * step; [openPageId] survives rotation so a page stays open.
 */
data class RemUiState(
    val outcome: RemOutcome? = null,
    val ownAddress: String? = null,
    val openPageId: String? = null
)

/**
 * Reads the connected agent's Rem notebook (W16AAPPLE-69) over the shared
 * connection, the same one ChatViewModel talks to, so it needs no socket of
 * its own and only ever reads the agent the chat is on.
 */
class RemViewModel @JvmOverloads constructor(
    application: Application,
    private val connectToAgentUseCase: ConnectToAgentUseCaseContract = application.appContainer.connectToAgentUseCase
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(RemUiState())
    val uiState: StateFlow<RemUiState> = _uiState.asStateFlow()

    private var readJob: Job? = null

    init {
        load()
    }

    /** Read the notebook again. A read already in flight is replaced, not doubled. */
    fun load() {
        readJob?.cancel()
        _uiState.update { it.copy(outcome = null) }
        readJob = viewModelScope.launch {
            val outcome = connectToAgentUseCase.readRem()
            // ConnectionState's address is the identity this device connected
            // as, which is what the owner has to add as an admin.
            val own = connectToAgentUseCase.connectionState.value.addressOrNull()
            _uiState.update { state ->
                val pages = (outcome as? RemOutcome.Loaded)?.notebook?.pages.orEmpty()
                state.copy(
                    outcome = outcome,
                    ownAddress = own,
                    // A refresh keeps the open page only if it still exists.
                    openPageId = state.openPageId?.takeIf { id -> pages.any { it.id == id } }
                )
            }
        }
    }

    fun openPage(id: String) = _uiState.update { it.copy(openPageId = id) }

    fun closePage() = _uiState.update { it.copy(openPageId = null) }
}
