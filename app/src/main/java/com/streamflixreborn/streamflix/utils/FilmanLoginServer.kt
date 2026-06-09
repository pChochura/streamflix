package com.streamflixreborn.streamflix.utils

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.FormBody
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume

class FilmanLoginServer {

    companion object {
        private const val TAG = "FilmanLogin"
        private const val PORT = 8392
        private const val FILMAN_BASE = "https://filman.cc"
    }

    private var server: LoginHttpServer? = null
    private var dialog: AlertDialog? = null
    private var activeContinuation: kotlinx.coroutines.CancellableContinuation<Boolean>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Starts the local login server, shows a QR code dialog on the TV,
     * and suspends until the user logs in from their phone.
     * Returns true if login was successful.
     */
    suspend fun requestLogin(): Boolean {
        // Clean up any previous state
        dismissDialog()
        stopServer()

        return suspendCancellableCoroutine { continuation ->
            activeContinuation = continuation

            val localIp = getLocalIpAddress()
            if (localIp == null) {
                Log.e(TAG, "Could not determine local IP address")
                activeContinuation = null
                if (continuation.isActive) continuation.resume(false)
                return@suspendCancellableCoroutine
            }

            val loginUrl = "http://$localIp:$PORT"
            Log.d(TAG, "Starting login server at $loginUrl")

            server = LoginHttpServer(PORT) { success ->
                Log.d(TAG, "Login completed: success=$success")
                mainHandler.post {
                    dismissDialog()
                    stopServer()
                }
                activeContinuation = null
                if (continuation.isActive) continuation.resume(success)
            }

            try {
                server?.start()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start login server", e)
                activeContinuation = null
                if (continuation.isActive) continuation.resume(false)
                return@suspendCancellableCoroutine
            }

            mainHandler.post {
                showQrDialog(loginUrl)
            }

            continuation.invokeOnCancellation {
                mainHandler.post {
                    activeContinuation = null
                    dismissDialog()
                    stopServer()
                }
            }
        }
    }

    private fun showQrDialog(url: String) {
        val activity = ActivityTracker.getCurrentActivity()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.e(TAG, "No valid activity to show QR dialog")
            return
        }

        val qrBitmap = generateQrCode(url, 512)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(activity).apply {
            text = "Zaloguj się do Filman.cc"
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        container.addView(title)

        val subtitle = TextView(activity).apply {
            text = "Zeskanuj kod QR telefonem\ni zaloguj się na stronie"
            setTextColor(Color.parseColor("#B0B0B0"))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        container.addView(subtitle)

        if (qrBitmap != null) {
            val qrView = ImageView(activity).apply {
                setImageBitmap(qrBitmap)
                setPadding(16, 16, 16, 16)
                setBackgroundColor(Color.WHITE)
            }
            val qrParams = LinearLayout.LayoutParams(520, 520).apply {
                gravity = Gravity.CENTER
            }
            container.addView(qrView, qrParams)
        }

        val urlText = TextView(activity).apply {
            text = url
            setTextColor(Color.parseColor("#64FFDA"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 16)
        }
        container.addView(urlText)

        val hint = TextView(activity).apply {
            text = "Lub wpisz adres w przeglądarce telefonu"
            setTextColor(Color.parseColor("#808080"))
            textSize = 12f
            gravity = Gravity.CENTER
        }
        container.addView(hint)

        dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
            .setView(container)
            .setCancelable(true)
            .setOnCancelListener {
                Log.d(TAG, "QR dialog cancelled by user")
                stopServer()
                val cont = activeContinuation
                activeContinuation = null
                if (cont != null && cont.isActive) cont.resume(false)
            }
            .create()

        dialog?.show()
    }

    private fun dismissDialog() {
        try {
            dialog?.dismiss()
            dialog = null
        } catch (_: Exception) {}
    }

    private fun stopServer() {
        try {
            server?.stop()
            server = null
        } catch (_: Exception) {}
    }

    private fun generateQrCode(text: String, size: Int): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "QR code generation failed", e)
            null
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP", e)
        }
        return null
    }

    /**
     * NanoHTTPD server that serves a login form and proxies credentials to filman.cc.
     */
    private class LoginHttpServer(
        port: Int,
        private val onLoginResult: (Boolean) -> Unit
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            return when {
                session.method == Method.GET && session.uri == "/" -> serveLoginPage()
                session.method == Method.POST && session.uri == "/login" -> handleLogin(session)
                session.method == Method.GET && session.uri == "/success" -> serveSuccessPage()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        }

        private fun serveLoginPage(): Response {
            val html = """
<!DOCTYPE html>
<html lang="pl">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Filman.cc - Logowanie</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, #1A1A2E 0%, #16213E 50%, #0F3460 100%);
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            color: #fff;
        }
        .card {
            background: rgba(255,255,255,0.08);
            backdrop-filter: blur(20px);
            border: 1px solid rgba(255,255,255,0.12);
            border-radius: 20px;
            padding: 40px;
            width: 100%;
            max-width: 400px;
            margin: 20px;
        }
        h1 {
            text-align: center;
            margin-bottom: 8px;
            font-size: 24px;
        }
        .subtitle {
            text-align: center;
            color: rgba(255,255,255,0.6);
            margin-bottom: 32px;
            font-size: 14px;
        }
        .form-group { margin-bottom: 20px; }
        label {
            display: block;
            margin-bottom: 6px;
            font-size: 14px;
            color: rgba(255,255,255,0.8);
        }
        input[type="text"], input[type="password"] {
            width: 100%;
            padding: 14px 16px;
            border: 1px solid rgba(255,255,255,0.2);
            border-radius: 12px;
            background: rgba(255,255,255,0.06);
            color: #fff;
            font-size: 16px;
            outline: none;
            transition: border-color 0.2s;
        }
        input:focus { border-color: #64FFDA; }
        button {
            width: 100%;
            padding: 14px;
            border: none;
            border-radius: 12px;
            background: linear-gradient(135deg, #64FFDA, #00BFA5);
            color: #1A1A2E;
            font-size: 16px;
            font-weight: 600;
            cursor: pointer;
            transition: transform 0.1s;
        }
        button:active { transform: scale(0.98); }
        .error {
            background: rgba(255,82,82,0.15);
            border: 1px solid rgba(255,82,82,0.3);
            border-radius: 10px;
            padding: 12px;
            margin-bottom: 20px;
            color: #FF5252;
            text-align: center;
            font-size: 14px;
            display: none;
        }
        .spinner {
            display: none;
            text-align: center;
            padding: 20px;
        }
        .spinner::after {
            content: '';
            width: 32px; height: 32px;
            border: 3px solid rgba(255,255,255,0.2);
            border-top-color: #64FFDA;
            border-radius: 50%;
            display: inline-block;
            animation: spin 0.8s linear infinite;
        }
        @keyframes spin { to { transform: rotate(360deg); } }
    </style>
</head>
<body>
    <div class="card">
        <h1>🎬 Filman.cc</h1>
        <p class="subtitle">Zaloguj się, aby odblokować treści w Streamflix</p>
        <div id="error" class="error"></div>
        <form id="loginForm">
            <div class="form-group">
                <label for="login">Login</label>
                <input type="text" id="login" name="login" autocomplete="username" required autofocus>
            </div>
            <div class="form-group">
                <label for="password">Hasło</label>
                <input type="password" id="password" name="password" autocomplete="current-password" required>
            </div>
            <button type="submit">Zaloguj się</button>
        </form>
        <div id="spinner" class="spinner"></div>
    </div>
    <script>
        document.getElementById('loginForm').addEventListener('submit', async (e) => {
            e.preventDefault();
            const form = e.target;
            const error = document.getElementById('error');
            const spinner = document.getElementById('spinner');
            error.style.display = 'none';
            form.style.display = 'none';
            spinner.style.display = 'block';
            
            try {
                const resp = await fetch('/login', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: new URLSearchParams({
                        login: document.getElementById('login').value,
                        password: document.getElementById('password').value
                    })
                });
                const data = await resp.json();
                if (data.success) {
                    window.location.href = '/success';
                } else {
                    error.textContent = data.message || 'Błąd logowania';
                    error.style.display = 'block';
                    form.style.display = 'block';
                    spinner.style.display = 'none';
                }
            } catch (err) {
                error.textContent = 'Błąd połączenia';
                error.style.display = 'block';
                form.style.display = 'block';
                spinner.style.display = 'none';
            }
        });
    </script>
</body>
</html>
            """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }

        private fun handleLogin(session: IHTTPSession): Response {
            val bodyMap = mutableMapOf<String, String>()
            try {
                session.parseBody(bodyMap)
            } catch (_: Exception) {}

            val postData = session.parms
            val login = postData["login"] ?: ""
            val password = postData["password"] ?: ""

            if (login.isBlank() || password.isBlank()) {
                return jsonResponse(false, "Podaj login i hasło")
            }

            return try {
                // First, GET the login page to capture any CSRF token and initial cookies
                val client = NetworkClient.default

                val getLoginRequest = Request.Builder()
                    .url("$FILMAN_BASE/logowanie")
                    .header("User-Agent", NetworkClient.USER_AGENT)
                    .header("Referer", FILMAN_BASE)
                    .build()

                val getResponse = client.newCall(getLoginRequest).execute()
                val loginPageHtml = getResponse.body?.string() ?: ""

                // Extract CSRF token if present
                val csrfToken = Regex("""name="_token"\s+value="([^"]+)"""").find(loginPageHtml)
                    ?.groupValues?.getOrNull(1)
                    ?: Regex("""name="csrf_token"\s+value="([^"]+)"""").find(loginPageHtml)
                        ?.groupValues?.getOrNull(1)

                // Build the login POST request
                val formBuilder = FormBody.Builder()
                    .add("login", login)
                    .add("password", password)

                if (!csrfToken.isNullOrBlank()) {
                    formBuilder.add("_token", csrfToken)
                }

                val postRequest = Request.Builder()
                    .url("$FILMAN_BASE/logowanie")
                    .header("User-Agent", NetworkClient.USER_AGENT)
                    .header("Referer", "$FILMAN_BASE/logowanie")
                    .header("Origin", FILMAN_BASE)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .post(formBuilder.build())
                    .build()

                val postResponse = client.newCall(postRequest).execute()
                val responseHtml = postResponse.body?.string() ?: ""
                val responseUrl = postResponse.request.url.toString()

                // Check if login was successful:
                // - Redirected away from login page
                // - Or page no longer contains the login form
                val isStillOnLogin = responseUrl.contains("/logowanie") ||
                        responseHtml.contains("Nieprawidłowy login") ||
                        responseHtml.contains("Nieprawidłowe hasło") ||
                        responseHtml.contains("Błędne dane")

                if (isStillOnLogin && responseHtml.contains("login-form", ignoreCase = true)) {
                    jsonResponse(false, "Nieprawidłowy login lub hasło")
                } else {
                    // Cookies are automatically saved by NetworkClient.cookieJar
                    // Also sync to WebView CookieManager
                    val cookieManager = CookieManager.getInstance()
                    val cookies = cookieManager.getCookie(FILMAN_BASE)
                    Log.d(TAG, "Login successful. Cookies: $cookies")

                    onLoginResult(true)
                    jsonResponse(true, "Zalogowano pomyślnie!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login request failed", e)
                jsonResponse(false, "Błąd połączenia z filman.cc: ${e.message}")
            }
        }

        private fun serveSuccessPage(): Response {
            val html = """
<!DOCTYPE html>
<html lang="pl">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Zalogowano!</title>
    <style>
        * { margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, #1A1A2E 0%, #16213E 50%, #0F3460 100%);
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            color: #fff;
        }
        .card {
            background: rgba(255,255,255,0.08);
            backdrop-filter: blur(20px);
            border: 1px solid rgba(255,255,255,0.12);
            border-radius: 20px;
            padding: 40px;
            text-align: center;
            max-width: 400px;
            margin: 20px;
        }
        .check { font-size: 64px; margin-bottom: 16px; }
        h1 { margin-bottom: 12px; color: #64FFDA; }
        p { color: rgba(255,255,255,0.7); }
    </style>
</head>
<body>
    <div class="card">
        <div class="check">✅</div>
        <h1>Zalogowano!</h1>
        <p>Możesz zamknąć tę stronę i wrócić do Streamflix na TV.</p>
    </div>
</body>
</html>
            """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }

        private fun jsonResponse(success: Boolean, message: String): Response {
            val json = """{"success": $success, "message": "$message"}"""
            return newFixedLengthResponse(Response.Status.OK, "application/json", json)
        }
    }
}
