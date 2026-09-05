package dev.nami.feature.search

import dev.nami.core.model.TrackId
import dev.nami.domain.SearchRepository
import dev.nami.domain.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `updating query debounces then emits results`() = runTest {
        val fakeRepo = object : SearchRepository {
            override suspend fun search(query: String): List<SearchResult> =
                listOf(SearchResult.TrackResult(TrackId("t1"), "Window View", "Farewell225"))
            override suspend fun rebuildIndex() {}
        }
        val viewModel = SearchViewModel(fakeRepo)

        viewModel.onQueryChange("window")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.results.size)
        assertTrue(viewModel.uiState.value.results[0] is SearchResult.TrackResult)
    }

    @Test
    fun `blank query clears results without calling search`() = runTest {
        var searchCalled = false
        val fakeRepo = object : SearchRepository {
            override suspend fun search(query: String): List<SearchResult> {
                searchCalled = true
                return emptyList()
            }
            override suspend fun rebuildIndex() {}
        }
        val viewModel = SearchViewModel(fakeRepo)

        viewModel.onQueryChange("")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.results.size)
        assertTrue(!searchCalled)
    }
}
