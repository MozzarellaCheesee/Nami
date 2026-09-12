package dev.nami.player

import android.util.Log
import dev.nami.core.model.Track
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

private const val TAG = "LocalHttpServer"

/** Группа G "сеть" - минимальный HTTP/1.1 сервер на голых сокетах, GET-only. Ни одна из
 * стандартных Java HTTP-сереверных API (com.sun.net.httpserver) не входит в Android SDK, а
 * тянуть Ktor/NanoHTTPD ради четырёх ручек - overkill. Один accept-поток, обработка запроса в
 * отдельном потоке (локальная сеть, запросов единицы одновременно).
 *
 * Лежит в :player, а не в :data (где живёт его первый потребитель, Wi-Fi Drop): Cast тоже раздаёт
 * им локальные файлы на телевизор, а :data зависит от :player, не наоборот - обратная зависимость
 * дала бы цикл. Все ручки кроме /track опциональны, Cast использует только её. */
class LocalHttpServer(
    private val port: Int,
    private val trackByIdBlocking: (String) -> Track?,
    private val deviceName: String = "NAMI",
    private val nowPlayingJsonBlocking: () -> JSONObject? = { null },
    private val dropTrackBlocking: () -> Track? = { null },
    private val manifestJsonBlocking: () -> JSONObject = { JSONObject() },
    /** Теги раздаваемого трека из БАЗЫ отдающего (см. /dropmeta ниже) - null когда не раздаётся. */
    private val dropMetaJsonBlocking: () -> JSONObject? = { null },
    /** Теги ЛЮБОГО трека по его id - тем же форматом, что /dropmeta. Нужны "слушать вместе":
     * гость решает добавить в библиотеку то, что играет, и должен получить те же теги и картинки,
     * что и при обычной раздаче, а не голый файл. */
    private val trackMetaJsonBlocking: (String) -> JSONObject? = { null },
    /** Файл обложки трека по его id - null если её нет. */
    private val trackCoverFileBlocking: (String) -> File? = { null },
    /** Файл фото артиста трека по его id - null если его нет. */
    private val artistPhotoFileBlocking: (String) -> File? = { null },
) {
    private var serverSocket: ServerSocket? = null
    private val clients = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "LocalHttpServer-client").apply { isDaemon = true }
    }

    /** Кто недавно спрашивал /nowplaying - это и есть "слушают вместе". Отдельного "подключения" в
     * протоколе нет (обычные stateless GET), так что живость гостя определяется тем же способом,
     * что и у него самого разрыв связи: спрашивал недавно - значит слушает. */
    private val guests = RecentGuests()

    /** Сколько человек слушает прямо сейчас. Верхнего лимита нет и осознанно не вводится: сценарий
     * тут - домашняя сеть на 2-5 человек, а ServerSocket с потоком на клиента столько тянет без
     * оговорок; лимит был бы защитой от несуществующей проблемы. */
    fun guestCount(): Int = guests.count()

    fun start() {
        val socket = ServerSocket(port)
        serverSocket = socket
        Thread({
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    client.soTimeout = 15_000
                    clients.execute { handleClient(client) }
                } catch (e: IOException) {
                    break // socket closed via stop()
                }
            }
        }, "LocalHttpServer-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // Already closed - fine.
        }
        serverSocket = null
        clients.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                val input = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = input.readLine() ?: return
                // Из заголовков нужен только Range (Chromecast качает файл кусками и без 206 не
                // умеет перематывать); остальные ручки заголовков не читают, но поток дочитать надо.
                var range: String? = null
                while (true) {
                    val line = input.readLine()
                    if (line.isNullOrEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                }
                val path = requestLine.split(" ").getOrNull(1) ?: return
                val output = s.getOutputStream()
                when {
                    path == "/info" -> writeJson(output, JSONObject().put("name", deviceName))
                    path == "/nowplaying" -> {
                        s.inetAddress?.hostAddress?.let { guests.seen(it) }
                        val json = nowPlayingJsonBlocking()
                        if (json != null) writeJson(output, json) else writeStatus(output, 204)
                    }
                    path == "/manifest" -> writeJson(output, manifestJsonBlocking())
                    path == "/drop" -> serveTrack(output, dropTrackBlocking(), range)
                    // Теги и обложка живут в БАЗЕ отдающего, а не обязательно в самом файле:
                    // трек без тегов (а такие в библиотеке обычные) приезжал получателю голым -
                    // название из имени файла (то есть UUID), без артиста, альбома и обложки.
                    path == "/dropmeta" -> {
                        val meta = dropMetaJsonBlocking()
                        if (meta != null) writeJson(output, meta) else writeStatus(output, 404)
                    }
                    path.startsWith("/meta/") -> {
                        val meta = trackMetaJsonBlocking(path.removePrefix("/meta/"))
                        if (meta != null) writeJson(output, meta) else writeStatus(output, 404)
                    }
                    path.startsWith("/cover/") -> serveFile(output, trackCoverFileBlocking(path.removePrefix("/cover/")), "image/*")
                    path.startsWith("/artistphoto/") -> serveFile(output, artistPhotoFileBlocking(path.removePrefix("/artistphoto/")), "image/*")
                    // substringBeforeLast('.'): часть приёмников (Яндекс Станция - точно)
                    // отказывается качать ссылку без расширения файла, поэтому DLNA/AirPlay/Станции
                    // отдаётся "/track/<id>.flac". В самих id точек не бывает (UUID), так что для
                    // Cast и Wi-Fi Drop, которые шлют голый id, ничего не меняется.
                    path.startsWith("/track/") ->
                        serveTrack(output, trackByIdBlocking(path.removePrefix("/track/").substringBeforeLast('.')), range)
                    else -> writeStatus(output, 404)
                }
            } catch (e: Exception) {
                Log.w(TAG, "request failed", e)
            }
        }
    }

    private fun serveTrack(output: OutputStream, track: Track?, range: String?) {
        if (track == null) {
            writeStatus(output, 404)
            return
        }
        val file = File(track.path)
        if (!file.exists()) {
            writeStatus(output, 404)
            return
        }
        val total = file.length()
        val requested = parseByteRange(range, total)
        val partial = requested != null
        val from = requested?.first ?: 0L
        val to = requested?.last ?: (total - 1)
        val length = to - from + 1
        val header = buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $length\r\n")
            if (partial) append("Content-Range: bytes $from-$to/$total\r\n")
            append("X-Original-Filename: ${encodeFilenameHeader(downloadFileName(track, file))}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        file.inputStream().use { stream ->
            stream.skip(from)
            val buffer = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read <= 0) break
                output.write(buffer, 0, read)
                left -= read
            }
        }
        output.flush()
    }

    /** Отдать файл целиком, без Range - обложка маленькая, куски ей ни к чему. */
    private fun serveFile(output: OutputStream, file: File?, contentType: String) {
        if (file == null || !file.exists()) {
            writeStatus(output, 404)
            return
        }
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${file.length()}\r\n" +
            "X-Original-Filename: ${encodeFilenameHeader(file.name)}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        file.inputStream().use { it.copyTo(output) }
        output.flush()
    }

    private fun writeJson(output: OutputStream, json: JSONObject) {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private fun writeStatus(output: OutputStream, code: Int) {
        val text = if (code == 404) "Not Found" else if (code == 204) "No Content" else "Error"
        output.write("HTTP/1.1 $code $text\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
    }
}

/** Счётчик "кто слушает сейчас" по адресу источника запроса. Окно взято с запасом относительно
 * интервала опроса гостя (1.5 с): три пропущенных опроса подряд - уже не сетевой джиттер, а уход.
 * Часы - elapsedRealtime-подобный System.nanoTime (переводом системного времени не сбивается). */
internal class RecentGuests(
    private val windowMs: Long = 6_000L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lastSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun seen(address: String) {
        lastSeen[address] = nowMs()
    }

    fun count(): Int {
        val cutoff = nowMs() - windowMs
        lastSeen.entries.removeAll { it.value < cutoff }
        return lastSeen.size
    }
}

/** Осмысленное имя файла для получателя (Wi-Fi Drop, кэш "слушать вместе"). Брать file.name
 * нельзя: в библиотеке файлы лежат под UUID-именами, и трек без тегов приезжал на другое
 * устройство с названием вида "367a78a5-dee5-4a7a-...", ровно так и попадая в библиотеку - импорт
 * берёт название из имени файла, когда тегов нет. Расширение сохраняем: по нему определяется
 * формат при импорте. */
internal fun downloadFileName(track: Track, file: File): String {
    val extension = file.extension.ifBlank { "audio" }
    val artist = track.artistName?.takeIf { it.isNotBlank() }?.let { "$it - " } ?: ""
    val base = "$artist${track.title}".replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").trim().take(120)
    return if (base.isBlank()) file.name else "$base.$extension"
}

/** Заголовки HTTP - ISO-8859-1, а имена треков сплошь и рядом не ASCII (японские названия в этой
 * библиотеке - обычное дело). Без процентного кодирования получатель читал бы кракозябры и
 * сохранял трек под ними же. Обратно разбирается URLDecoder'ом на стороне клиента. */
internal fun encodeFilenameHeader(name: String): String =
    java.net.URLEncoder.encode(name, "UTF-8")

/** "bytes=NNN-" и "bytes=NNN-MMM" (единственные формы, которые шлют Chromecast и браузеры) в
 * готовые границы включительно. null означает "отдать файл целиком, 200" - в том числе для всего,
 * что не разобралось, и для Wi-Fi Drop, который Range вообще не шлёт. Multipart-диапазоны
 * ("bytes=0-99,200-299") не поддерживаем: их не запрашивает ни один реальный клиент этих ручек. */
internal fun parseByteRange(header: String?, total: Long): LongRange? {
    if (header == null || total <= 0) return null
    val spec = header.substringAfter("bytes=", "").ifEmpty { return null }
    val from = spec.substringBefore('-').toLongOrNull() ?: return null
    if (from < 0 || from >= total) return null
    val to = spec.substringAfter('-', "").toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
    if (to < from) return null
    return from..to
}

/** Адрес телефона в текущей Wi-Fi/Wi-Fi Direct сети - то, что должен позвать другой участник ЛВС
 * (телевизор с Chromecast, второй телефон). null означает "нет ни Wi-Fi, ни Wi-Fi Direct" -
 * раздавать физически некому, а не "адрес ещё не появился".
 *
 * Берётся перебором интерфейсов, а не через WifiManager.connectionInfo.ipAddress: тот знает
 * ТОЛЬКО обычную Wi-Fi (wlan0) и отдаёт 0 на Wi-Fi Direct (интерфейс p2p-*) - из-за этого экран
 * группы G показывал "0.0.0.0:47821" ровно в том сценарии, ради которого Wi-Fi Direct и нужен.
 * wlan0 остаётся приоритетным (Chromecast живёт именно там), p2p - следующим.
 *
 * Раньше при отсутствии wlan0/p2p падало на "всё остальное" (любой up-интерфейс) - на телефоне
 * без Wi-Fi, но с мобильным интернетом это отдавало адрес интерфейса вроде rmnet (например
 * "10.0.0.1") - настоящий IP, который выглядел рабочим, но на самом деле никуда в локальной сети
 * не ведёт (у мобильного интерфейса нет соседей по LAN). Честнее явно сказать "нет Wi-Fi", чем
 * показать правдоподобный, но фактически бесполезный адрес. */
fun localIpAddress(): String? = localIpAddresses().firstOrNull()

/** Все свои IPv4-адреса разом (только Wi-Fi/Wi-Fi Direct интерфейсы, см. [localIpAddress]) - для
 * отсева самого себя в автопоиске (NSD видит и собственную регистрацию). Сравнение с одним
 * [localIpAddress] тут не годится: в Wi-Fi Direct устройство анонсирует себя по p2p-адресу, а
 * localIpAddress вернул бы wlan0, и устройство показывало бы само себя в списке найденных.
 * Порядок: wlan0, потом p2p. */
fun localIpAddresses(): List<String> = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        .filter { it.name.startsWith("wlan") || it.name.startsWith("p2p") }
        .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        .flatMap { iface -> iface.inetAddresses.asSequence() }
        .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
        .mapNotNull { it.hostAddress }
        .toList()
}.getOrDefault(emptyList())
