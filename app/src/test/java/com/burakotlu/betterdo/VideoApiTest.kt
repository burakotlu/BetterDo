package com.burakotlu.betterdo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VideoApiTest {
    private fun completed() = JSONObject().put("id", "a".repeat(32)).put("lessonId", "en-sneeze")
        .put("status", "completed").put("provider", "mock").put("script", "Today's word is sneeze.")
        .put("videoUrl", "/media/${"a".repeat(32)}.mp4")

    @Test fun completedVideoUsesAuthenticatedServerMedia() {
        val video = VideoApi.decode(completed(), "en-sneeze")
        assertEquals(VideoStatus.COMPLETED, video.status)
        assertTrue(video.mock)
        assertEquals("https://example.com/media/${"a".repeat(32)}.mp4",
            VideoApi("https://example.com", "t".repeat(32), false).mediaUrl(video))
    }

    @Test fun rejectsForeignLessonOrMediaOrigin() {
        assertThrows(IllegalArgumentException::class.java) { VideoApi.decode(completed(), "de-niesen") }
        assertThrows(IllegalArgumentException::class.java) {
            VideoApi.decode(completed().put("videoUrl", "https://foreign.example/video.mp4"), "en-sneeze")
        }
    }

    @Test fun releaseRequiresHttpsAndDebugOnlyAllowsLocalHttp() {
        VideoApi.validateBaseUrl("http://10.0.2.2:8080", true)
        assertThrows(IllegalArgumentException::class.java) { VideoApi.validateBaseUrl("http://10.0.2.2:8080", false) }
        assertThrows(IllegalArgumentException::class.java) { VideoApi.validateBaseUrl("http://example.com", true) }
        assertThrows(IllegalArgumentException::class.java) { VideoApi.validateBaseUrl("https://user:password@example.com", false) }
    }
}
