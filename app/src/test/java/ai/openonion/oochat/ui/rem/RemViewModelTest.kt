package ai.openonion.oochat.ui.rem

import ai.openonion.oochat.domain.model.RemNotebook
import ai.openonion.oochat.domain.model.RemOutcome
import ai.openonion.oochat.domain.model.RemPage
import ai.openonion.oochat.domain.usecase.ConnectToAgentUseCaseContract
import ai.openonion.oochat.ui.chat.RecompFakeConnectUseCase
import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * W16AAPPLE-69: what the Rem screen keeps across a read. Refreshing must not
 * close the page the owner is reading, unless that page has gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemViewModelTest {

    /** Answers each read with the next queued outcome; a read with none queued waits. */
    private class FakeUseCase(
        private val inner: RecompFakeConnectUseCase = RecompFakeConnectUseCase()
    ) : ConnectToAgentUseCaseContract by inner {
        val answers = ArrayDeque<CompletableDeferred<RemOutcome>>()

        fun answerNext(outcome: RemOutcome) = CompletableDeferred(outcome).also { answers.addLast(it) }

        override suspend fun readRem(): RemOutcome = answers.removeFirst().await()
    }

    private fun notebook(vararg ids: String) = RemOutcome.Loaded(
        RemNotebook(ids.map { RemPage(it, it.substringBefore('/'), it, "# $it") })
    )

    private val useCase = FakeUseCase()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(first: RemOutcome) = RemViewModel(Application(), useCase.also { it.answerNext(first) })

    @Test
    fun `a refresh keeps the open page`() {
        val viewModel = viewModel(notebook("people/sam.md", "projects/apollo.md"))
        viewModel.openPage("people/sam.md")

        useCase.answerNext(notebook("people/sam.md", "projects/apollo.md"))
        viewModel.load()

        assertEquals("people/sam.md", viewModel.uiState.value.openPageId)
        assertEquals(notebook("people/sam.md", "projects/apollo.md"), viewModel.uiState.value.outcome)
    }

    @Test
    fun `the open page survives while the refresh is in flight`() {
        val viewModel = viewModel(notebook("people/sam.md"))
        viewModel.openPage("people/sam.md")

        useCase.answers.addLast(CompletableDeferred())
        viewModel.load()

        assertNull("loading", viewModel.uiState.value.outcome)
        assertEquals("people/sam.md", viewModel.uiState.value.openPageId)
    }

    @Test
    fun `a refresh closes a page that is no longer in the notebook`() {
        val viewModel = viewModel(notebook("people/sam.md"))
        viewModel.openPage("people/sam.md")

        useCase.answerNext(notebook("projects/apollo.md"))
        viewModel.load()

        assertNull(viewModel.uiState.value.openPageId)
    }

    @Test
    fun `closing a page clears it`() {
        val viewModel = viewModel(notebook("people/sam.md"))
        viewModel.openPage("people/sam.md")
        viewModel.closePage()

        assertNull(viewModel.uiState.value.openPageId)
    }
}
