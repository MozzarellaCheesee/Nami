package dev.nami.feature.library

import androidx.paging.PagingData
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.ImportProgress
import dev.nami.domain.ImportSource
import dev.nami.domain.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `importing emits progress then reaches total`() = runTest {
        val fakeRepo = object : LibraryRepository {
            override fun tracks(): Flow<PagingData<Track>> = flowOf(PagingData.empty())
            override fun track(id: TrackId): Flow<Track?> = flowOf(null)
            override suspend fun import(source: ImportSource): Flow<ImportProgress> =
                flowOf(ImportProgress(1, 2), ImportProgress(2, 2))
        }
        val viewModel = LibraryViewModel(fakeRepo)

        viewModel.importFiles(listOf("content://fake/1", "content://fake/2"))

        assertEquals(ImportProgress(2, 2), viewModel.uiState.value.importProgress)
    }
}
