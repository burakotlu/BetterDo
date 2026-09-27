package com.burakotlu.betterdo

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AccountViewModel(application: Application) : AndroidViewModel(application) {
    val configured = BuildConfig.BACKEND_URL.isNotBlank()
    private val api = if (configured) AccountApi(BuildConfig.BACKEND_URL) else null
    private val store = AccountSessionStore(application)
    private var session: AccountSession? = null
    var user by mutableStateOf<AccountUser?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var awaitingEmail by mutableStateOf<String?>(null)
        private set

    init {
        if (api != null) runAction {
            val token = withContext(Dispatchers.IO) { store.read() } ?: return@runAction
            val restored = withContext(Dispatchers.IO) { api.refresh(token) }
            accept(restored)
        }
    }

    fun signUp(email: String, password: String, name: String) = runAction {
        require(email.trim().matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) && email.length <= 254) { "Enter a valid email address." }
        require(password.length >= 8) { "Use a password with at least 8 characters." }
        require(name.trim().length in 1..80) { "Enter a name of at most 80 characters." }
        withContext(Dispatchers.IO) { requireNotNull(api).signUp(email.trim(), password, name.trim()) }
        awaitingEmail = email.trim()
        notice = "Check your email for the confirmation code, then enter it here."
    }

    fun verify(code: String) = runAction {
        val email = requireNotNull(awaitingEmail) { "Start by creating an account." }
        require(code.matches(Regex("[0-9]{8}"))) { "Enter the eight-digit code from your email." }
        val verified = withContext(Dispatchers.IO) { requireNotNull(api).verify(email, code) }
        require(verified.user.verified) { "Email verification is still pending." }
        accept(verified)
        awaitingEmail = null
        notice = "Email verified. Your account is ready."
    }

    fun signIn(email: String, password: String) = runAction {
        val signedIn = withContext(Dispatchers.IO) { requireNotNull(api).signIn(email.trim(), password) }
        require(signedIn.user.verified) { "Confirm your email before signing in." }
        accept(signedIn)
        awaitingEmail = null
        notice = null
    }

    fun updateName(name: String) = runAction {
        require(name.trim().length in 1..80) { "Enter a name of at most 80 characters." }
        val current = activeSession()
        user = withContext(Dispatchers.IO) { requireNotNull(api).updateName(current.accessToken, name.trim()) }
        notice = "Name saved to your account."
    }

    fun signOut() = runAction {
        val current = requireNotNull(session)
        val remote = withContext(Dispatchers.IO) { runCatching { requireNotNull(api).signOut(current.accessToken) } }
        withContext(Dispatchers.IO) { store.clear() }
        session = null
        user = null
        notice = if (remote.isSuccess) "Signed out. Your lessons and progress remain on this device."
            else "Signed out on this device. Server sign-out could not be confirmed."
    }

    fun dismissNotice() { notice = null }

    fun accessToken(): String? = session?.accessToken

    private suspend fun accept(value: AccountSession) {
        withContext(Dispatchers.IO) { store.save(value.refreshToken) }
        session = value
        user = value.user
    }

    private suspend fun activeSession(): AccountSession {
        val current = requireNotNull(session) { "Sign in to continue." }
        val refreshed = withContext(Dispatchers.IO) { requireNotNull(api).refresh(current.refreshToken) }
        accept(refreshed)
        return refreshed
    }

    private fun runAction(block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try { block() } catch (error: Exception) {
                notice = error.message?.take(200) ?: "Account request failed. Try again."
            } finally { busy = false }
        }
    }
}
