package dev.nami.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.nami.domain.TrashRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NamiApplication : Application() {

    @Inject lateinit var trashRepository: TrashRepository

    override fun onCreate() {
        super.onCreate()
        // ponytail: fire-and-forget startup sweep, not a scheduled job — see the plan's
        // "purge mechanism" note for why WorkManager is out of scope for now.
        CoroutineScope(Dispatchers.IO).launch { trashRepository.purgeExpired() }
    }
}
