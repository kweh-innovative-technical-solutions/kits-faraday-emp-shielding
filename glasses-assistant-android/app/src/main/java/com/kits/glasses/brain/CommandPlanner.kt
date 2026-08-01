package com.kits.glasses.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * One decided phone action. The planner returns exactly one of these per turn.
 *
 * @property action  one of: open_app, tap, type, scroll, back, home, recents, none, speak
 * @property target  app name / text to find/tap/type / scroll direction ("up"|"down")
 * @property say     short spoken confirmation for the user
 */
@Serializable
data class PhoneCommand(
    val action: String,
    val target: String? = null,
    val say: String? = null
)

/**
 * Turns (transcript + screen snapshot) into a single [PhoneCommand] by asking
 * the Anthropic Messages API. The model is forced to call the phone_action
 * tool, so the response is always a structured, parseable action rather than
 * free prose.
 */
class CommandPlanner(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun plan(transcript: String, screen: String): PhoneCommand =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext PhoneCommand(
                    action = "none",
                    say = "No API key is configured."
                )
            }

            val body = buildRequest(transcript, screen).toString()
                .toRequestBody(JSON_MEDIA)

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body)
                .build()

            try {
                http.newCall(request).execute().use { resp ->
                    val payload = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        PhoneCommand(
                            action = "none",
                            say = "The assistant service returned an error."
                        )
                    } else {
                        parseToolUse(payload)
                    }
                }
            } catch (e: Exception) {
                PhoneCommand(action = "none", say = "I couldn't reach the assistant.")
            }
        }

    private fun buildRequest(transcript: String, screen: String): JsonObject =
        buildJsonObject {
            put("model", MODEL)
            put("max_tokens", 512)
            put("system", SYSTEM_PROMPT)
            putJsonArray("tools") {
                addJsonObject {
                    put("name", "phone_action")
                    put("description", "Perform exactly one action on the phone.")
                    putJsonObject("input_schema") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("action") {
                                put("type", "string")
                                putJsonArray("enum") {
                                    ACTIONS.forEach { add(it) }
                                }
                            }
                            putJsonObject("target") { put("type", "string") }
                            putJsonObject("say") { put("type", "string") }
                        }
                        putJsonArray("required") { add("action") }
                    }
                }
            }
            putJsonObject("tool_choice") {
                put("type", "tool")
                put("name", "phone_action")
            }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put(
                                "text",
                                "User said: \"$transcript\"\n\nCurrent screen:\n$screen"
                            )
                        }
                    }
                }
            }
        }

    private fun parseToolUse(payload: String): PhoneCommand = try {
        val root = json.parseToJsonElement(payload).jsonObject
        val content = root["content"]?.jsonArray
        val toolUse = content?.firstOrNull {
            it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_use"
        }?.jsonObject
        val input = toolUse?.get("input")?.jsonObject
        if (input == null) {
            PhoneCommand(action = "none")
        } else {
            PhoneCommand(
                action = input["action"]?.jsonPrimitive?.contentOrNull ?: "none",
                target = input["target"]?.jsonPrimitive?.contentOrNull,
                say = input["say"]?.jsonPrimitive?.contentOrNull
            )
        }
    } catch (e: Exception) {
        PhoneCommand(action = "none", say = "I couldn't understand the response.")
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()

        // Configurable model id. claude-opus-5 is the current default; swap for a
        // lower-latency model (e.g. claude-haiku-4-5) if turn speed matters more.
        private const val MODEL = "claude-opus-5"

        private val ACTIONS = listOf(
            "open_app", "tap", "type", "scroll", "back", "home", "recents", "none", "speak"
        )

        private val SYSTEM_PROMPT = """
            You are the decision engine for a hands-free phone assistant used with
            smart glasses. You receive the user's spoken request and a text snapshot
            of the current phone screen. Choose EXACTLY ONE phone action that best
            advances the request, then call the phone_action tool.

            Guidance:
            - Prefer open_app when the user names an app (target = the app name).
            - Use tap with the exact visible label text shown on screen.
            - Use scroll with target "up" or "down".
            - Keep 'say' to a single short spoken confirmation sentence.
            - If nothing sensible applies, use action "none" and explain briefly in 'say'.
        """.trimIndent()
    }
}
