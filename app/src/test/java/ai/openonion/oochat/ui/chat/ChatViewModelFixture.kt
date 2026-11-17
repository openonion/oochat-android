package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.data.local.IgnoredIdsManager
import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.domain.usecase.ConversationHistoryUseCase
import ai.openonion.oochat.domain.usecase.RecentChatsSync
import ai.openonion.oochat.domain.usecase.RecentChatsSyncResult
import ai.openonion.oochat.network.AgentDiscoveryService
import android.app.Application
import androidx.test.core.app.ApplicationProvider

/**
 * A real [ChatViewModel] over in-memory fakes, with one agent saved. Under Robolectric the view
 * model cannot be built the way the app builds it, through the Android factory, so tests make it
 * here and hand it to the screen under test.
 */
internal fun chatViewModelOver(connectUseCase: RecompFakeConnectUseCase): ChatViewModel {
    val agentRepo = RecompFakeAgentRepository()
    val sessionRepo = RecompFakeSessionRepository()
    val messageRepo = RecompFakeMessageRepository()
    agentRepo.seed(
        AgentProfile(
            id = "agent-1",
            name = "Home Agent",
            address = "0x1a2b3c4d5e6f7a8b",
            serverUrl = "wss://relay.test",
            createdAt = 0L,
            isActive = true
        )
    )
    return ChatViewModel(
        application = ApplicationProvider.getApplicationContext<Application>(),
        agentDiscovery = AgentDiscoveryService(),
        connectUseCase = connectUseCase,
        agentRepository = agentRepo,
        configRepository = RecompFakeConfigRepository(),
        ignoredIdsManager = IgnoredIdsManager(RecompFakeIgnoredIdsStorage()),
        appSettings = RecompFakeAppSettings(),
        messageRepository = messageRepo,
        conversationHistory = ConversationHistoryUseCase(
            agentRepo, sessionRepo, messageRepo,
            FakePersistenceTransaction(messageRepo, sessionRepo)
        ),
        initialMyAddress = "0xTEST",
        networkMonitor = RecompFakeNetworkMonitor(),
        // Session Sync has its own tests, and the real one loads identity crypto, which a JVM cannot.
        recentChatsSync = RecentChatsSync { _, _, _, _ -> RecentChatsSyncResult.Unsupported },
    )
}
