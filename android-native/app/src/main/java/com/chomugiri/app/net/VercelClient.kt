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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw VercelException("Vercel returned an unexpected response (HTTP ${resp.code}).")
            }
            if (!resp.isSuccessful) {
                val msg = json.optJSONObject("error")?.optString("message")
                    ?: "Vercel deploy failed (HTTP ${resp.code})"
                throw VercelException(msg)
            }

            // Vercel's "Standard Protection" is on by default for new projects, and it covers the
            // generated deployment URL (the one with the random hash) that `url` carries — opening
            // it just shows Vercel's login page instead of the site, which is what made every
            // deployed link look broken. Turning it off is what actually makes a generated site a
            // public website; without it the link is useless to share.
            json.optString("projectId").takeIf { it.isNotBlank() }
                ?.let { disableDeploymentProtection(token, it) }

            // Prefer a production alias (clean domain, never protection-gated) over the hashed
            // deployment URL, falling back to it when no alias came back.
            val alias = json.optJSONArray("alias")
                ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) } }
                ?.minByOrNull { it.length }
            val url = alias ?: json.optString("url").takeIf { it.isNotBlank() }
            DeployResult(url = url?.let { "https://$it" }, id = json.optString("id"))
        }
    }

    /**
     * Best-effort: a team/plan that enforces protection will refuse this, and that is not worth
     * failing an otherwise-successful deploy over — the deploy still happened, the link is just
     * login-gated, which the caller can still hand to the user.
     */
    private fun disableDeploymentProtection(token: String, projectId: String) {
        try {
            val body = JSONObject()
                .put("ssoProtection", JSONObject.NULL)
                .put("passwordProtection", JSONObject.NULL)
            val req = Request.Builder()
                .url("https://api.vercel.com/v9/projects/$projectId")
                .addHeader("Authorization", "Bearer $token")
                .patch(body.toString().toRequestBody(JSON))
                .build()
            http.newCall(req).execute().close()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Deliberately swallowed — see the doc comment above.
        }
    }
}
