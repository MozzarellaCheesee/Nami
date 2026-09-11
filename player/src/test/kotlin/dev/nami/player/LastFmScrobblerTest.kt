package dev.nami.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LastFmScrobblerTest {

    @Test
    fun `calculateSignature produces correct md5 hash from sorted params`() {
        val params = mapOf(
            "method" to "track.scrobble",
            "api_key" to "test_api_key",
            "sk" to "test_session_key",
            "artist" to "RADWIMPS",
            "track" to "Zenzenzense",
            "timestamp" to "1600000000",
        )
        val secret = "my_secret_token"
        // Sorted: api_key + artist + method + sk + timestamp + track + secret
        // "api_keytest_api_keyartistRADWIMPSmethodtrack.scrobblesktest_session_keytimestamp1600000000trackZenzenzensemy_secret_token"
        val sig = LastFmScrobbler.calculateSignature(params, secret)

        val expectedInput = "api_keytest_api_keyartistRADWIMPSmethodtrack.scrobblesktest_session_keytimestamp1600000000trackZenzenzensemy_secret_token"
        val md = java.security.MessageDigest.getInstance("MD5")
        val expectedSig = md.digest(expectedInput.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        assertEquals(expectedSig, sig)
    }

    @Test
    fun `calculateSignature filters out format and callback`() {
        val paramsWithFormat = mapOf(
            "format" to "json",
            "callback" to "myCb",
            "method" to "track.scrobble",
            "artist" to "Test",
        )
        val paramsWithout = mapOf(
            "method" to "track.scrobble",
            "artist" to "Test",
        )
        val secret = "secret"
        val sig1 = LastFmScrobbler.calculateSignature(paramsWithFormat, secret)
        val sig2 = LastFmScrobbler.calculateSignature(paramsWithout, secret)

        assertEquals(sig2, sig1)
    }
}
