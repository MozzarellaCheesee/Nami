package dev.nami.player.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "NsdScanner"

/** Разрешённая mDNS-запись: имя, адрес, порт и TXT-поля. */
data class NsdEntry(
    val name: String,
    val host: String,
    val port: Int,
    val attributes: Map<String, String>,
)

/**
 * Обёртка над системным [NsdManager] под разовый поиск "найди всё за N секунд и верни список".
 * Тем же системным API (только со своим service type) уже пользуется автопоиск Wi-Fi Drop -
 * второго механизма обнаружения в приложении не заводим.
 *
 * ponytail: resolveService выполняем строго по одному под мьютексом. До Android 12 NsdManager
 * умеет ровно один разрешаемый сервис за раз и отвечает FAILURE_ALREADY_ACTIVE на параллельные -
 * очередь дешевле, чем ретраи. Потолок: N устройств разрешаются последовательно, при десятке
 * приёмников поиск упирается в таймаут. Апгрейд - registerServiceInfoCallback на API 34+.
 */
suspend fun nsdScan(context: Context, serviceType: String, timeoutMs: Long = 5000): List<NsdEntry> {
    val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyList()
    val found = mutableListOf<NsdServiceInfo>()
    val resolveLock = Mutex()
    val result = linkedMapOf<String, NsdEntry>()

    val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "не удалось начать поиск $serviceType: $errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceFound(service: NsdServiceInfo) {
            synchronized(found) { found += service }
        }
        override fun onServiceLost(service: NsdServiceInfo) = Unit
    }

    runCatching { manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
        .onFailure { return emptyList() }

    try {
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val next = synchronized(found) { found.removeFirstOrNull() } ?: run {
                    kotlinx.coroutines.delay(200)
                    null
                } ?: continue
                resolveLock.withLock {
                    val resolved = resolveOne(manager, next)
                    if (resolved != null) result[resolved.name] = resolved
                }
            }
        }
    } finally {
        runCatching { manager.stopServiceDiscovery(listener) }
    }
    return result.values.toList()
}

private suspend fun resolveOne(manager: NsdManager, service: NsdServiceInfo): NsdEntry? {
    val deferred = CompletableDeferred<NsdEntry?>()
    @Suppress("DEPRECATION")
    manager.resolveService(
        service,
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                deferred.complete(null)
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                deferred.complete(
                    if (host == null) {
                        null
                    } else {
                        NsdEntry(
                            name = info.serviceName ?: host,
                            host = host,
                            port = info.port,
                            attributes = info.attributes.orEmpty()
                                .mapNotNull { (k, v) -> v?.let { k to String(it, Charsets.UTF_8) } }
                                .toMap(),
                        )
                    },
                )
            }
        },
    )
    // Ответ может не прийти вообще (устройство пропало между анонсом и разрешением) - без своего
    // таймаута очередь встала бы навсегда на первом же таком.
    return withTimeoutOrNull(3000) { deferred.await() }
}
