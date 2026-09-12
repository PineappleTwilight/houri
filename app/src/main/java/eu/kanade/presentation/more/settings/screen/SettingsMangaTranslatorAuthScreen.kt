package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import mihon.app.di.globalAppGraph
import tachiyomi.presentation.core.util.collectAsState

object SettingsMangaTranslatorAuthScreen : Screen {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val prefs = remember { globalAppGraph.translationPreferences }
        val service = remember { globalAppGraph.mangaTranslatorService }
        val scope = rememberCoroutineScope()

        val savedEmailState = prefs.mangaTranslatorEmail().collectAsState()
        val savedEmail = savedEmailState.value
        val hasTokenState = prefs.mangaTranslatorAccessToken().collectAsState()
        val hasToken = hasTokenState.value
        var email by remember { mutableStateOf(savedEmail) }
        var password by remember { mutableStateOf("") }
        var status by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }

        // Keep email field in sync when navigating back
        LaunchedEffect(savedEmail) { email = savedEmail }

        // Fetch current user on enter if logged in
        LaunchedEffect(hasToken) {
            if (hasToken.isNotBlank()) {
                status = "Checking session…"
                try {
                    val user = service.getCurrentUser()
                    status = if (user != null) {
                        "Logged in as ${user.email ?: "anonymous"} · tier: ${user.subscriptionTier}"
                    } else {
                        "Session token present but server did not return user — try re-logging in"
                    }
                } catch (e: Exception) {
                    status = "Session check failed: ${e.message}"
                }
            } else {
                status = "Not logged in — translations work anonymously but quota is limited. Log in at ichigo.moe for more."
            }
        }

        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "MangaTranslator Account", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Same account as the Chrome extension (ichigo.moe / mangatranslator.ai). " +
                    "The app stores only a session token (no API key) — just like the extension does via its access_cookie. " +
                    "Anonymous use is allowed but rate-limited; logging in uses your site quota.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (hasToken.isNotBlank()) {
                Text(
                    text = "● Logged in token present (${hasToken.take(6)}…)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(text = "○ No session token — anonymous mode", style = MaterialTheme.typography.bodySmall)
            }
            status?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = !busy && email.contains("@") && password.length >= 6,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val result = service.login(email.trim(), password)
                                status = when (result) {
                                    exh.yakuyomi.LoginResult.Success -> "Login success — ${service.getCurrentUser()?.subscriptionTier ?: "free"} tier"
                                    exh.yakuyomi.LoginResult.BadPassword -> "Login failed: bad password"
                                    exh.yakuyomi.LoginResult.UnknownEmail -> "Login failed: unknown email"
                                    exh.yakuyomi.LoginResult.InvalidEmail -> "Invalid email format"
                                    exh.yakuyomi.LoginResult.RateLimited -> "Rate limited — try again later"
                                    exh.yakuyomi.LoginResult.Unknown -> "Login failed — check connection or credentials"
                                }
                                if (result == exh.yakuyomi.LoginResult.Success) {
                                    prefs.mangaTranslatorEmail().set(email.trim())
                                    context.toast("Logged in")
                                }
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(if (busy) "…" else "Log in") }

                TextButton(
                    enabled = !busy && email.contains("@") && password.length >= 6,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val result = service.signup(email.trim(), password)
                                status = when (result) {
                                    exh.yakuyomi.SignupResult.Success -> "Signup success — logged in"
                                    exh.yakuyomi.SignupResult.EmailTaken -> "Signup failed: email already taken"
                                    exh.yakuyomi.SignupResult.InvalidEmail -> "Invalid email"
                                    exh.yakuyomi.SignupResult.RateLimited -> "Rate limited"
                                    exh.yakuyomi.SignupResult.Unknown -> "Signup failed"
                                }
                                if (result == exh.yakuyomi.SignupResult.Success) {
                                    prefs.mangaTranslatorEmail().set(email.trim())
                                    context.toast("Signed up")
                                }
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Sign up") }

                TextButton(
                    enabled = !busy && hasToken.isNotBlank(),
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                service.logout()
                                status = "Logged out — anonymous mode"
                                context.toast("Logged out")
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Log out") }
            }

            Text(
                text = "Fingerprint and client ID are stored locally (like the extension) and sent with each translation to identify your device — not your reading history. Base URL must be https; private hosts are rejected.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { navigator.pop() }) { Text("Back") }
        }
    }
}
