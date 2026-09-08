package dev.nami.data.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume

private const val TAG = "WebRtcInternetLink"
private const val ICE_GATHER_TIMEOUT_MS = 8_000L
// DataChannel сообщения ограничены практическим потолком SCTP (~256КБ надёжно работает почти
// везде) - режем байты трека на куски заметно меньше этого, с запасом на служебные байты рамки.
private const val CHUNK_SIZE = 48_000
// Потолок неотправленного в очереди канала - дальше отправитель ждёт, см. sendTrackBytes.
private const val MAX_BUFFERED_BYTES = 1_000_000L
private const val FRAME_TEXT: Byte = 1
private const val FRAME_TRACK_META: Byte = 2
private const val FRAME_TRACK_CHUNK: Byte = 3
private const val FRAME_TRACK_END: Byte = 4
private const val FRAME_TRACK_REQUEST: Byte = 5

/** Один WebRTC P2P-канал (offer/answer вручную, публичный STUN Google, без своего сервера) -
 * см. LocalShareRepository.InternetLinkState doc. Инкапсулирует всю возню с PeerConnection/
 * DataChannel, наружу отдаёт только текст (nowplaying JSON) и байты трека покусочно. Один канал
 * на роль (хост ИЛИ гость) за раз - новый createInvite()/acceptInvite() должен звать close() на
 * предыдущем, если был. */
class WebRtcInternetLink(context: Context) {
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var iceCandidates = mutableListOf<IceCandidate>()
    private var iceGatheringDone: (() -> Unit)? = null

    var onTextMessage: ((String) -> Unit)? = null
    var onTrackRequest: ((trackId: String) -> Unit)? = null
    var onTrackMeta: ((trackId: String, fileName: String, totalBytes: Int) -> Unit)? = null
    var onTrackChunk: ((ByteArray) -> Unit)? = null
    var onTrackEnd: (() -> Unit)? = null
    var onChannelOpen: (() -> Unit)? = null
    var onChannelClosed: (() -> Unit)? = null

    init {
        val options = PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        PeerConnectionFactory.initialize(options)
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    private fun iceServers() = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
    )

    private fun newPeerConnection(observer: PeerConnection.Observer): PeerConnection? {
        val config = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        return factory?.createPeerConnection(config, observer)
    }

    private fun baseObserver(onDataChannelReceived: (DataChannel) -> Unit) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            iceCandidates.add(candidate)
        }
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) iceGatheringDone?.invoke()
        }
        override fun onDataChannel(channel: DataChannel) = onDataChannelReceived(channel)
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.d(TAG, "connection state: $newState")
            if (newState == PeerConnection.PeerConnectionState.FAILED || newState == PeerConnection.PeerConnectionState.CLOSED) {
                onChannelClosed?.invoke()
            }
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onAddStream(stream: org.webrtc.MediaStream) {}
        override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {}
    }

    private fun wireDataChannel(channel: DataChannel) {
        dataChannel = channel
        // Разбор "рамки" одного бинарного сообщения канала: первый байт - тип, дальше payload -
        // см. константы FRAME_* вверху файла. Текст (nowplaying JSON) и бинарные куски трека
        // делят один канал, а не два - меньше возни с ожиданием второго onDataChannel.
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) onChannelOpen?.invoke()
                if (channel.state() == DataChannel.State.CLOSED) onChannelClosed?.invoke()
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                if (bytes.isEmpty()) return
                when (bytes[0]) {
                    FRAME_TEXT -> onTextMessage?.invoke(String(bytes, 1, bytes.size - 1, StandardCharsets.UTF_8))
                    FRAME_TRACK_REQUEST -> onTrackRequest?.invoke(String(bytes, 1, bytes.size - 1, StandardCharsets.UTF_8))
                    FRAME_TRACK_META -> {
                        val json = JSONObject(String(bytes, 1, bytes.size - 1, StandardCharsets.UTF_8))
                        onTrackMeta?.invoke(json.getString("trackId"), json.getString("fileName"), json.getInt("totalBytes"))
                    }
                    FRAME_TRACK_CHUNK -> onTrackChunk?.invoke(bytes.copyOfRange(1, bytes.size))
                    FRAME_TRACK_END -> onTrackEnd?.invoke()
                }
            }
        })
    }

    private fun send(type: Byte, payload: ByteArray) {
        val frame = ByteBuffer.allocate(payload.size + 1)
        frame.put(type)
        frame.put(payload)
        frame.flip()
        dataChannel?.send(DataChannel.Buffer(frame, true))
    }

    fun sendText(text: String) = send(FRAME_TEXT, text.toByteArray(StandardCharsets.UTF_8))
    fun sendTrackRequest(trackId: String) = send(FRAME_TRACK_REQUEST, trackId.toByteArray(StandardCharsets.UTF_8))
    fun sendTrackMeta(trackId: String, fileName: String, totalBytes: Int) =
        send(FRAME_TRACK_META, JSONObject().put("trackId", trackId).put("fileName", fileName).put("totalBytes", totalBytes).toString().toByteArray(StandardCharsets.UTF_8))

    /** Режет файл на куски по CHUNK_SIZE и шлёт один за другим - DataChannel не гарантирует
     * порядок доставки при unordered, поэтому канал создаётся с ordered=true (см. createDataChannel).
     *
     * Между кусками ждёт, пока разгребётся очередь отправки: раньше весь файл заливался в канал
     * одним взрывом, и на треке чуть крупнее пары мегабайт очередь SCTP переполнялась - libwebrtc
     * в этом случае не отдаёт ошибку наверх, а просто рвёт DataChannel, и у гостя навсегда
     * оставалось "Скачивается...". Целые FLAC/большие mp3 без этого не доезжали вообще. */
    suspend fun sendTrackBytes(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            while ((dataChannel?.bufferedAmount() ?: 0L) > MAX_BUFFERED_BYTES) {
                if (dataChannel?.state() != DataChannel.State.OPEN) return
                delay(20)
            }
            val end = minOf(offset + CHUNK_SIZE, bytes.size)
            send(FRAME_TRACK_CHUNK, bytes.copyOfRange(offset, end))
            offset = end
        }
        send(FRAME_TRACK_END, ByteArray(0))
    }

    /** Хост: offer + ждёт сбора ICE-кандидатов (не trickle - код должен быть самодостаточным для
     * ручной передачи), возвращает JSON-код приглашения. */
    suspend fun createInvite(): String {
        iceCandidates = mutableListOf()
        val pc = newPeerConnection(baseObserver { }) ?: error("PeerConnection не создался")
        peerConnection = pc
        val channel = pc.createDataChannel("nami", DataChannel.Init().apply { ordered = true })
        wireDataChannel(channel)

        val offer = suspendCancellableCoroutine<SessionDescription> { cont ->
            pc.createOffer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    pc.setLocalDescription(object : SdpObserverAdapter() {
                        override fun onSetSuccess() { cont.resume(sdp) }
                    }, sdp)
                }
            }, MediaConstraints())
        }
        awaitIceGathering()
        return encodeLink("offer", offer.description, iceCandidates)
    }

    /** Гость: принимает код приглашения, возвращает JSON-код ответа для хоста. */
    suspend fun acceptInvite(inviteCode: String): String {
        iceCandidates = mutableListOf()
        val (type, sdp, candidates) = decodeLink(inviteCode)
        require(type == "offer") { "Это не код приглашения" }
        val pc = newPeerConnection(baseObserver { channel -> wireDataChannel(channel) }) ?: error("PeerConnection не создался")
        peerConnection = pc

        setRemoteDescription(pc, SessionDescription(SessionDescription.Type.OFFER, sdp))
        candidates.forEach { pc.addIceCandidate(it) }

        val answer = suspendCancellableCoroutine<SessionDescription> { cont ->
            pc.createAnswer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    pc.setLocalDescription(object : SdpObserverAdapter() {
                        override fun onSetSuccess() { cont.resume(sdp) }
                    }, sdp)
                }
            }, MediaConstraints())
        }
        awaitIceGathering()
        return encodeLink("answer", answer.description, iceCandidates)
    }

    /** Хост: завершает handshake кодом ответа гостя. */
    suspend fun completeLink(answerCode: String) {
        val pc = peerConnection ?: error("Нет активного приглашения")
        val (type, sdp, candidates) = decodeLink(answerCode)
        require(type == "answer") { "Это не код ответа" }
        setRemoteDescription(pc, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        candidates.forEach { pc.addIceCandidate(it) }
    }

    fun close() {
        // Колбэки снимаются ПЕРВЫМИ: close() ниже приводит соединение в CLOSED, а это тот же путь,
        // что и настоящий обрыв, - onChannelClosed прилетал уже после осознанного закрытия и
        // переводил экран в "ошибка связи" на ровном месте, при том что связи уже нет по нашей воле.
        onTextMessage = null
        onTrackRequest = null
        onTrackMeta = null
        onTrackChunk = null
        onTrackEnd = null
        onChannelOpen = null
        onChannelClosed = null
        dataChannel?.close()
        dataChannel = null
        peerConnection?.close()
        peerConnection = null
    }

    private suspend fun setRemoteDescription(pc: PeerConnection, sdp: SessionDescription) =
        suspendCancellableCoroutine<Unit> { cont ->
            pc.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() { cont.resume(Unit) }
            }, sdp)
        }

    // ICE-сбор изредка не доходит до COMPLETE (например пропала сеть на полпути) - таймаут не
    // даёт зависнуть навсегда, отдаём то, что успели собрать (обычно этого достаточно, кандидаты
    // добавляются по мере поступления).
    private suspend fun awaitIceGathering() {
        withTimeoutOrNull(ICE_GATHER_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                iceGatheringDone = { if (cont.isActive) cont.resume(Unit) }
            }
        }
        iceGatheringDone = null
    }

    private fun encodeLink(type: String, sdp: String, candidates: List<IceCandidate>): String {
        val candidatesJson = JSONArray()
        candidates.forEach {
            candidatesJson.put(JSONObject().put("sdpMid", it.sdpMid).put("sdpMLineIndex", it.sdpMLineIndex).put("candidate", it.sdp))
        }
        return JSONObject().put("type", type).put("sdp", sdp).put("candidates", candidatesJson).toString()
    }

    private fun decodeLink(code: String): Triple<String, String, List<IceCandidate>> {
        val json = JSONObject(code.trim())
        val candidates = (0 until json.getJSONArray("candidates").length()).map { i ->
            val c = json.getJSONArray("candidates").getJSONObject(i)
            IceCandidate(c.getString("sdpMid"), c.getInt("sdpMLineIndex"), c.getString("candidate"))
        }
        return Triple(json.getString("type"), json.getString("sdp"), candidates)
    }
}

/** SdpObserver has 4 methods with no defaults in the Java API - this adapter lets each call site
 * override only the one callback it actually cares about. */
private open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) { Log.w(TAG, "SDP create failed: $error") }
    override fun onSetFailure(error: String) { Log.w(TAG, "SDP set failed: $error") }
}
