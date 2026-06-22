package rechoraccoon.trackpanda

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.URL

data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false
)

object LastFmService {
    private const val API_KEY = "7e69fc909a996215626d3499782b94f2"

    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying

    private var pollingScope: CoroutineScope? = null
    private var currentUsername = ""

    fun startPolling(username: String) {
        currentUsername = username.trim()
        pollingScope?.cancel()
        if (currentUsername.isEmpty()) return
        pollingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        pollingScope?.launch {
            while (isActive) {
                fetchNowPlaying()
                delay(2000L)
            }
        }
    }

    fun stopPolling() {
        pollingScope?.cancel()
        pollingScope = null
        _nowPlaying.value = NowPlaying()
    }

    fun setNowPlaying(nowPlaying: NowPlaying) {
        _nowPlaying.value = nowPlaying
    }

    private fun fetchNowPlaying() {
        try {
            val url = "https://ws.audioscrobbler.com/2.0/?method=user.getrecenttracks&user=$currentUsername&api_key=$API_KEY&format=json&limit=1"
            val json = JSONObject(URL(url).readText())
            if (json.has("error")) { _nowPlaying.value = NowPlaying(); return }
            val tracks = json.getJSONObject("recenttracks").getJSONArray("track")
            if (tracks.length() == 0) { _nowPlaying.value = NowPlaying(); return }
            val track = tracks.getJSONObject(0)
            val isPlaying = track.optJSONObject("@attr")?.optString("nowplaying") == "true"
            _nowPlaying.value = NowPlaying(
                title = track.optString("name", ""),
                artist = track.optJSONObject("artist")?.optString("#text", "") ?: "",
                isPlaying = isPlaying
            )
        } catch (e: Exception) { e.printStackTrace() }
    }
}
