/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.jellyfin

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EmbyMediaSourceTest {

    private val baseUrl = "http://localhost:8096"
    private val userId = "testUserId"
    private val apiKey = "testApiKey"

    // A single episode item returned when searching by parentId (Series/Season)
    private val episodeItemJson = """
        {
          "Items": [
            {
              "Name": "夜晚的水母",
              "Id": "763e3e132aab7ba605df6c1c1af22a68",
              "IndexNumber": 1,
              "Type": "Episode",
              "SeriesName": "夜晚的水母不会游泳",
              "SeriesId": "9479952c828319401e5c500da1dfe241",
              "SeasonId": "12fb6fb758ee952450268eaf92a95f5e",
              "SeasonName": "夜晚的水母不会游泳",
              "MediaStreams": []
            }
          ],
          "TotalRecordCount": 1,
          "StartIndex": 0
        }
    """.trimIndent()

    // A Series item returned from the initial search by name
    private val seriesSearchResultJson = """
        {
          "Items": [
            {
              "Name": "夜晚的水母不会游泳",
              "Id": "9479952c828319401e5c500da1dfe241",
              "Type": "Series",
              "MediaStreams": []
            }
          ],
          "TotalRecordCount": 1,
          "StartIndex": 0
        }
    """.trimIndent()

    // A Movie item with external ASS subtitles
    private val movieSearchResultJson = """
        {
          "Items": [
            {
              "Name": "魔法少女奈叶 Detonation",
              "Id": "8c96ca96d8f46cac5056aacf8893f6b5",
              "Type": "Movie",
              "MediaStreams": [
                {
                  "Codec": "ass",
                  "Language": "chs",
                  "Title": "chs&jpn",
                  "Type": "Subtitle",
                  "Index": 0,
                  "IsExternal": true,
                  "IsTextSubtitleStream": true
                }
              ]
            }
          ],
          "TotalRecordCount": 1,
          "StartIndex": 0
        }
    """.trimIndent()

    private val emptyJson = """{"Items":[],"TotalRecordCount":0,"StartIndex":0}"""

    /**
     * Creates an [EmbyMediaSource] backed by a [MockEngine].
     *
     * [responseSelector] receives the full request URL and returns the JSON body to respond with.
     */
    private fun createEmbyMediaSource(responseSelector: (String) -> String): EmbyMediaSource {
        val engine = MockEngine { request ->
            val url = request.url.toString()
            respond(
                content = responseSelector(url),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return EmbyMediaSource(
            config = MediaSourceConfig(
                arguments = mapOf(
                    "baseUrl" to baseUrl,
                    "userId" to userId,
                    "apikey" to apiKey,
                ),
            ),
            client = client.asScopedHttpClient(),
        )
    }

    @Test
    fun `fetch returns episode found via series search`() = runTest {
        // When the initial search returns a Series, the source performs a second search by parentId
        // to retrieve the episodes under that series.
        val source = createEmbyMediaSource { url ->
            when {
                url.contains("searchTerm=") -> seriesSearchResultJson
                url.contains("parentId=9479952c828319401e5c500da1dfe241") -> episodeItemJson
                else -> emptyJson
            }
        }

        val request = MediaFetchRequest(
            subjectId = "123",
            episodeId = "456",
            subjectNameCN = "夜晚的水母不会游泳",
            subjectNames = listOf("夜晚的水母不会游泳"),
            episodeSort = EpisodeSort(1),
            episodeName = "夜晚的水母",
        )

        val results = source.fetch(request).results.toList()

        assertEquals(1, results.size)
        val media = results[0].media
        assertEquals("夜晚的水母不会游泳", media.properties.subjectName)
        assertEquals(EmbyMediaSource.ID, media.mediaSourceId)
        assertIs<ResourceLocation.HttpStreamingFile>(media.download)
        assertEquals(
            "$baseUrl/Items/763e3e132aab7ba605df6c1c1af22a68/Download?api_key=$apiKey",
            (media.download as ResourceLocation.HttpStreamingFile).uri,
        )
        assertTrue(media.extraFiles.subtitles.isEmpty())
    }

    @Test
    fun `fetch returns movie with external subtitles`() = runTest {
        val source = createEmbyMediaSource { url ->
            if (url.contains("searchTerm=")) movieSearchResultJson else emptyJson
        }

        val request = MediaFetchRequest(
            subjectId = "789",
            episodeId = "101",
            subjectNameCN = "魔法少女奈叶",
            subjectNames = listOf("魔法少女奈叶"),
            episodeSort = EpisodeSort(1),
            episodeName = "魔法少女奈叶 Detonation",
        )

        val results = source.fetch(request).results.toList()

        assertEquals(1, results.size)
        val media = results[0].media
        assertEquals(EmbyMediaSource.ID, media.mediaSourceId)
        assertIs<ResourceLocation.HttpStreamingFile>(media.download)
        assertEquals(
            "$baseUrl/Items/8c96ca96d8f46cac5056aacf8893f6b5/Download?api_key=$apiKey",
            (media.download as ResourceLocation.HttpStreamingFile).uri,
        )

        // Subtitle should be extracted from the Movie's MediaStreams
        assertEquals(1, media.extraFiles.subtitles.size)
        val subtitle = media.extraFiles.subtitles[0]
        assertEquals(
            "$baseUrl/Videos/8c96ca96d8f46cac5056aacf8893f6b5/8c96ca96d8f46cac5056aacf8893f6b5/Subtitles/0/0/Stream.ass",
            subtitle.uri,
        )
        assertEquals("text/x-ass", subtitle.mimeType)
        assertEquals("chs&jpn", subtitle.label)
        assertEquals("chs", subtitle.language)
    }

    @Test
    fun `fetch deduplicates items with the same id`() = runTest {
        // A search that returns the same episode twice (e.g. via both Series and a direct Episode hit).
        val duplicateEpisodeJson = """
            {
              "Items": [
                {
                  "Name": "夜晚的水母",
                  "Id": "763e3e132aab7ba605df6c1c1af22a68",
                  "IndexNumber": 1,
                  "Type": "Episode",
                  "SeriesName": "夜晚的水母不会游泳",
                  "MediaStreams": []
                },
                {
                  "Name": "夜晚的水母",
                  "Id": "763e3e132aab7ba605df6c1c1af22a68",
                  "IndexNumber": 1,
                  "Type": "Episode",
                  "SeriesName": "夜晚的水母不会游泳",
                  "MediaStreams": []
                }
              ],
              "TotalRecordCount": 2,
              "StartIndex": 0
            }
        """.trimIndent()

        val source = createEmbyMediaSource { duplicateEpisodeJson }

        val request = MediaFetchRequest(
            subjectId = "123",
            episodeId = "456",
            subjectNameCN = "夜晚的水母不会游泳",
            subjectNames = listOf("夜晚的水母不会游泳"),
            episodeSort = EpisodeSort(1),
            episodeName = "夜晚的水母",
        )

        val results = source.fetch(request).results.toList()

        assertEquals(1, results.size, "Duplicate items with the same ID should be deduplicated")
    }

    @Test
    fun `fetch returns empty when server has no matching items`() = runTest {
        val source = createEmbyMediaSource { emptyJson }

        val request = MediaFetchRequest(
            subjectId = "123",
            episodeId = "456",
            subjectNameCN = "不存在的动画",
            subjectNames = listOf("不存在的动画"),
            episodeSort = EpisodeSort(1),
            episodeName = "第一集",
        )

        val results = source.fetch(request).results.toList()

        assertTrue(results.isEmpty())
    }
}
