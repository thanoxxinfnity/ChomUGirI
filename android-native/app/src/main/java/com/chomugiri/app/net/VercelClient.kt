package com.chomugiri.app.net

import com.chomugiri.app.core.GeneratedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class VercelException(message: String) : Exception(message)

data class DeployResult(val url: String?, val id: String?)

/**
 * Ships a project's files as one Vercel deployment via the inline-file form of the v13
 * deployments API. Fine for small generated projects; a very large/many-file artifact would
 * need the chunked upload flow instead, which isn't implemented here.
 */
object VercelClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun safeName(name: String): String {
        val cleaned = name.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').take(100)
        return cleaned.ifBlank { "chomugiri-app" }
    }

    suspend fun deploy(
        token: String,
        projectName: String,
        files: List<GeneratedFile>,
    ): DeployResult = withContext(Dispatchers.IO) {
        if (token.isBlank()) throw VercelException("No Vercel token set — add one in Settings to deploy.")
        if (files.isEmpty()) throw VercelException("No files to deploy.")

        val fileArr = JSONArray()
        files.forEach { f -> fileArr.put(JSONObject().put("file", f.path).put("data", f.content)) }

        val body = JSONObject()
            .put("name", safeName(projectName))
            .put("target", "production")
            .put("files", fileArr)
            .put("projectSettings", JSONObject().put("framework", JSONObject.NULL))

        val req = Request.Builder()
            .url("https://api.vercel.com/v13/deployments")
            .addHeader("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(JSON))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw VercelException("Vercel returned an unexpected response (HTTP ${resp.code}).")
            }
            if (!resp.isSuccessful) {
                val msg = json.optJSONObject("error")?.optString("message")
                    ?: "Vercel deploy failed (HTTP ${resp.code})"
                throw VercelException(msg)
            }
            val url = json.optString("url").takeIf { it.isNotBlank() }
            DeployResult(url = url?.let { "https://$it" }, id = json.optString("id"))
        }
    }
}
