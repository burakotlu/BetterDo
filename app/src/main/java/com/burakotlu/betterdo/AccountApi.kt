package com.burakotlu.betterdo

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class AccountUser(val id: String, val email: String, val displayName: String, val verified: Boolean)
data class AccountSession(val accessToken: String, val refreshToken: String, val user: AccountUser)

/** BetterDo's own account service. No shared server credential is included in the app. */
class AccountApi(private val origin: String) {
    init { VideoApi.validateBaseUrl(origin, BuildConfig.DEBUG) }

    fun signUp(email: String, password: String, name: String) {
        request("POST", "/api/account/signup", JSONObject().put("email", email).put("password", password).put("displayName", name))
    }

    fun verify(email: String, code: String): AccountSession = session(request("POST", "/api/account/verify",
        JSONObject().put("email", email).put("code", code)))

    fun signIn(email: String, password: String): AccountSession = session(request("POST", "/api/account/signin",
        JSONObject().put("email", email).put("password", password)))

    fun refresh(token: String): AccountSession = session(request("GET", "/api/account/session", token = token))

    fun updateName(token: String, name: String): AccountUser = user(request("PUT", "/api/account/profile",
        JSONObject().put("displayName", name), token))

    fun signOut(token: String) { request("POST", "/api/account/signout", token = token) }

    private fun session(json: JSONObject): AccountSession {
        val access = json.getString("access_token")
        require(access.length in 32..128)
        return AccountSession(access, json.getString("refresh_token"), user(json.getJSONObject("user")))
    }

    private fun user(json: JSONObject): AccountUser {
        val id = json.getString("id")
        val email = json.getString("email")
        require(id.matches(Regex("[0-9a-f]{32}")) && email.length <= 254)
        return AccountUser(id, email, json.getString("display_name").take(80), json.getBoolean("verified"))
    }

    private fun request(method: String, path: String, body: JSONObject? = null, token: String? = null): JSONObject {
        val connection = URL(origin.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream()
            stream?.use {
                val buffer = ByteArray(4096)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 65_536) throw IOException("Account response is too large.")
                    output.write(buffer, 0, count)
                }
            }
            val response = if (output.size() == 0) JSONObject() else JSONObject(output.toString("UTF-8"))
            if (status !in 200..299) throw IOException(response.optString("error", "Account request failed (HTTP $status).").take(200))
            return response
        } finally { connection.disconnect() }
    }
}
