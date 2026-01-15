package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.ActivityCodeEntryBinding
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CodeEntryActivity : ThemedActivity() {

    private lateinit var binding: ActivityCodeEntryBinding

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already activated, skip this screen.
        if (DataStore.authActivated && DataStore.selectedProxy != 0L) {
            openMain(autoConnect = false)
            return
        }

        binding = ActivityCodeEntryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Prefill from last time.
        binding.codeEdit.setText(DataStore.authCode)

        if (DataStore.authDeviceId.isBlank()) {
            DataStore.authDeviceId = generateDeviceId()
        }

        binding.activateButton.setOnClickListener {
            val code = binding.codeEdit.text?.toString()?.trim().orEmpty()
            if (code.isBlank()) {
                binding.codeLayout.error = getString(R.string.activation_code_required)
                return@setOnClickListener
            }
            binding.codeLayout.error = null
            DataStore.authCode = code
            activate(code)
        }
    }

    private fun activate(code: String) {
        setBusy(true)

        lifecycleScope.launch {
            try {
                val vlessLink = withContext(Dispatchers.IO) {
                    postLogin(
                        url = DataStore.authServerUrl,
                        authCode = code,
                        deviceId = DataStore.authDeviceId,
                    )
                }

                val profileBean = parseProxies(vlessLink).firstOrNull()
                    ?: error(getString(R.string.no_proxies_found))

                val targetGroupId = DataStore.selectedGroupForImport()
                val profile = withContext(Dispatchers.IO) {
                    ProfileManager.createProfile(targetGroupId, profileBean)
                }

                DataStore.selectedProxy = profile.id
                DataStore.currentProfile = profile.id
                DataStore.authActivated = true

                Snackbar.make(binding.root, R.string.activation_success, Snackbar.LENGTH_SHORT).show()

                openMain(autoConnect = true)
            } catch (e: Exception) {
                Snackbar.make(binding.root, e.readableMessage, Snackbar.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun openMain(autoConnect: Boolean) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_AUTO_CONNECT, autoConnect)
        )
        finish()
    }

    private fun setBusy(busy: Boolean) {
        binding.progressBar.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        binding.activateButton.isEnabled = !busy
        binding.codeEdit.isEnabled = !busy
    }

    private fun postLogin(url: String, authCode: String, deviceId: String): String {
        val json = JSONObject()
            .put("auth_code", authCode)
            .put("device_id", deviceId)
            .toString()

        val req = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // Server returns Russian error text in body; surface it if present.
                val msg = bodyStr.ifBlank { "HTTP ${resp.code}" }
                throw IllegalStateException(msg)
            }
            val link = bodyStr.trim()
            if (!link.startsWith("vless://")) {
                throw IllegalStateException(getString(R.string.activation_invalid_response))
            }
            return link
        }
    }

    @SuppressLint("HardwareIds")
    private fun generateDeviceId(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return androidId?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
    }

    companion object {
        const val EXTRA_AUTO_CONNECT = "extra_auto_connect"
    }
}

