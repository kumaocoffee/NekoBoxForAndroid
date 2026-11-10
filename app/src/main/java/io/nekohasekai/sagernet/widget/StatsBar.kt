package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import androidx.core.view.isVisible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var ipText: TextView
    private lateinit var locationText: TextView
    private lateinit var ipContainer: View
    private lateinit var behavior: YourBehavior
    private var ipJob: Job? = null
    private var testDebounceJob: Job? = null
    private var testJob: Job? = null

    var allowShow = true

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior { allowShow }
        return behavior
    }

    class YourBehavior(val getAllowShow: () -> Boolean) : Behavior() {

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: BottomAppBar, target: View,
            dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int, consumed: IntArray,
        ) {
            super.onNestedScroll(
                coordinatorLayout,
                child,
                target,
                dxConsumed,
                dyConsumed + dyUnconsumed,
                dxUnconsumed,
                0,
                type,
                consumed
            )
        }

        override fun slideUp(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideUp(child)
        }

        override fun slideDown(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideDown(child)
        }
    }

    private fun initViews() {
        if (!this::statusText.isInitialized) {
            statusText = findViewById(R.id.status)
            txText = findViewById(R.id.tx)
            rxText = findViewById(R.id.rx)
            ipText = findViewById(R.id.ip)
            locationText = findViewById(R.id.location)
            ipContainer = findViewById(R.id.ip_container)
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        initViews()
    }

    override fun setOnClickListener(l: OnClickListener?) {
        initViews()
        super.setOnClickListener(l)
    }

    private fun setStatus(text: CharSequence) {
        initViews()
        statusText.text = text
        TooltipCompat.setTooltipText(this, text)
    }

    private fun countryCodeToEmoji(countryCode: String): String {
        if (countryCode.length != 2) return ""
        val upper = countryCode.uppercase(Locale.ROOT)
        if (upper[0] !in 'A'..'Z' || upper[1] !in 'A'..'Z') return ""
        val firstChar = upper[0].code - 'A'.code + 0x1F1E6
        val secondChar = upper[1].code - 'A'.code + 0x1F1E6
        return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
    }

    @JvmOverloads
    fun fetchIpAndLocation(reset: Boolean = false) {
        val activity = context as? MainActivity ?: return
        initViews()
        ipJob?.cancel()

        if (reset || ipText.text.isNullOrBlank()) {
            ipText.text = "... "
            locationText.text = context.getString(R.string.connecting)
        }
        ipContainer.isVisible = true

        ipJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("http://ip-api.com/json/?lang=zh-CN")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "NekoBox")
                }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                if (stream != null) {
                    val jsonStr = stream.bufferedReader().use { it.readText() }
                    val obj = JSONObject(jsonStr)
                    if (obj.optString("status") == "success") {
                        val query = obj.optString("query")
                        val countryCode = obj.optString("countryCode")
                        val country = obj.optString("country")
                        val city = obj.optString("city")
                        val flag = countryCodeToEmoji(countryCode)
                        val ipDisplay = if (flag.isNotEmpty()) "$flag $query" else query
                        val locDisplay = if (country.isNotEmpty() && city.isNotEmpty()) {
                            "$country · $city"
                        } else {
                            country.ifEmpty { city }
                        }
                        withContext(Dispatchers.Main) {
                            ipText.text = ipDisplay
                            locationText.text = locDisplay
                            ipContainer.isVisible = true
                        }
                        return@launch
                    }
                }
                withContext(Dispatchers.Main) {
                    if (ipText.text == "... ") {
                        ipText.text = "—"
                        locationText.text = context.getString(R.string.connection_test_error, "")
                    }
                }
            } catch (e: Exception) {
                Logs.w("Fetch IP and location error: $e")
                withContext(Dispatchers.Main) {
                    if (ipText.text == "... ") {
                        ipText.text = "—"
                        locationText.text = context.getString(R.string.connection_test_error, "")
                    }
                }
            }
        }
    }

    fun resetIpAndLocation() {
        ipJob?.cancel()
        initViews()
        ipText.text = ""
        locationText.text = ""
        ipContainer.isVisible = false
    }

    fun resetAndTestConnection() {
        if (!DataStore.serviceState.connected) return
        val activity = context as? MainActivity ?: return
        initViews()
        ipJob?.cancel()
        testJob?.cancel()
        testDebounceJob?.cancel()

        ipText.text = "... "
        locationText.text = context.getString(R.string.connecting)
        ipContainer.isVisible = true
        setStatus(app.getText(R.string.connection_test_testing))
        isEnabled = false

        testDebounceJob = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(300L)
            if (DataStore.serviceState.connected) {
                testConnection()
            }
        }
    }

    fun changeState(state: BaseService.State) {
        val activity = (context as? MainActivity) ?: return
        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.whenStarted { what() }
        }
        if ((state == BaseService.State.Connected).also { hideOnScroll = it }) {
            postWhenStarted {
                if (allowShow) performShow()
                resetAndTestConnection()
            }
        } else {
            testDebounceJob?.cancel()
            testJob?.cancel()
            postWhenStarted {
                performHide()
            }
            updateSpeed(0, 0)
            resetIpAndLocation()
            setStatus(
                context.getText(
                    when (state) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            )
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        initViews()
        txText.text = "▲  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, txRate)
            )
        }"
        rxText.text = "▼  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, rxRate)
            )
        }"
    }

    fun testConnection() {
        val activity = context as? MainActivity ?: return
        if (!DataStore.serviceState.connected) return
        val targetProxyId = DataStore.selectedProxy
        if (targetProxyId <= 0L) return
        testJob?.cancel()
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing))
        fetchIpAndLocation(reset = true)
        testJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val elapsed = activity.urlTest()
                if (DataStore.selectedProxy == targetProxyId) {
                    val profile = SagerDatabase.proxyDao.getById(targetProxyId)
                    if (profile != null) {
                        profile.status = 1
                        profile.ping = elapsed
                        ProfileManager.updateProfile(profile)
                    }
                }
                withContext(Dispatchers.Main) {
                    if (DataStore.selectedProxy == targetProxyId) {
                        setStatus(
                            app.getString(
                                if (DataStore.connectionTestURL.startsWith("https://")) {
                                    R.string.connection_test_available
                                } else {
                                    R.string.connection_test_available_http
                                }, elapsed
                            )
                        )
                    }
                    isEnabled = true
                }

            } catch (e: Exception) {
                Logs.w(e.toString())
                val err = e.readableMessage
                val isNotStarted = err.contains("not started") || err.contains("core not started")
                if (!isNotStarted && DataStore.selectedProxy == targetProxyId) {
                    val profile = SagerDatabase.proxyDao.getById(targetProxyId)
                    if (profile != null) {
                        profile.status = 2
                        profile.error = err
                        ProfileManager.updateProfile(profile)
                    }
                }
                withContext(Dispatchers.Main) {
                    if (DataStore.selectedProxy == targetProxyId) {
                        if (isNotStarted) {
                            setStatus(app.getText(R.string.connecting))
                        } else {
                            val isTimeout = err.lowercase().contains("timeout") ||
                                    err.lowercase().contains("deadline") ||
                                    err == app.getString(R.string.connection_test_timeout)
                            setStatus(
                                if (isTimeout) app.getString(R.string.connection_test_timeout)
                                else app.getString(R.string.connection_test_error, err)
                            )
                        }
                    }
                    isEnabled = true
                }
            }
        }
    }

}
