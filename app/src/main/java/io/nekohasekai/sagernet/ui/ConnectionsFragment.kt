package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.ItemConnectionBinding
import io.nekohasekai.sagernet.databinding.LayoutConnectionsBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.*
import moe.matsuri.nb4a.connections.ClashConnection
import moe.matsuri.nb4a.connections.ClashConnections
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class ConnectionsFragment : ToolbarFragment(R.layout.layout_connections),
    Toolbar.OnMenuItemClickListener {

    private lateinit var binding: LayoutConnectionsBinding
    private val adapter = ConnectionsAdapter()
    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private var isPaused = false
    private var filterQuery = ""
    private var allConnections = listOf<ClashConnection>()

    private var lastUploadTotal = 0L
    private var lastDownloadTotal = 0L
    private var lastFetchTime = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_connections)
        toolbar.inflateMenu(R.menu.connections_menu)
        toolbar.setOnMenuItemClickListener(this)

        binding = LayoutConnectionsBinding.bind(view)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)

        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                fetchConnections()
                withContext(Dispatchers.Main) {
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }

        binding.editFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterQuery = s?.toString()?.trim() ?: ""
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Polling loop
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!isPaused) {
                    fetchConnections()
                }
                delay(1000)
            }
        }
    }

    private suspend fun fetchConnections() {
        val request = Request.Builder()
            .url("http://127.0.0.1:9090/connections")
            .get()
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val data = gson.fromJson(body, ClashConnections::class.java)
                    val list = data.connections ?: emptyList()
                    val now = System.currentTimeMillis()
                    val timeDeltaSec = if (lastFetchTime > 0) {
                        ((now - lastFetchTime) / 1000.0).coerceAtLeast(0.1)
                    } else 1.0

                    val upSpeed = if (lastUploadTotal > 0 && data.uploadTotal >= lastUploadTotal) {
                        ((data.uploadTotal - lastUploadTotal) / timeDeltaSec).toLong()
                    } else 0L

                    val downSpeed = if (lastDownloadTotal > 0 && data.downloadTotal >= lastDownloadTotal) {
                        ((data.downloadTotal - lastDownloadTotal) / timeDeltaSec).toLong()
                    } else 0L

                    lastUploadTotal = data.uploadTotal
                    lastDownloadTotal = data.downloadTotal
                    lastFetchTime = now

                    withContext(Dispatchers.Main) {
                        updateStats(list.size, upSpeed, downSpeed, data.uploadTotal, data.downloadTotal)
                        allConnections = list
                        applyFilter()
                    }
                    return
                }
            }
        } catch (_: IOException) {
            // Service not running or connection refused
        }

        withContext(Dispatchers.Main) {
            if (isAdded) {
                binding.textActiveCount.setText(R.string.connections_empty)
                binding.textSpeedOverview.text = ""
                binding.textTotals.text = ""
                allConnections = emptyList()
                applyFilter()
            }
        }
    }

    private fun updateStats(
        count: Int,
        upSpeed: Long,
        downSpeed: Long,
        upTotal: Long,
        downTotal: Long
    ) {
        if (!isAdded) return
        val ctx = requireContext()
        binding.textActiveCount.text = getString(R.string.connections_total_count, count)
        binding.textSpeedOverview.text = "↑ ${Formatter.formatFileSize(ctx, upSpeed)}/s   ↓ ${Formatter.formatFileSize(ctx, downSpeed)}/s"
        binding.textTotals.text = "${getString(R.string.connections_upload_total, Formatter.formatFileSize(ctx, upTotal))}   ${getString(R.string.connections_download_total, Formatter.formatFileSize(ctx, downTotal))}"
    }

    private fun applyFilter() {
        val filtered = if (filterQuery.isBlank()) {
            allConnections
        } else {
            val q = filterQuery.lowercase()
            allConnections.filter {
                it.displayHost().lowercase().contains(q) ||
                    (it.metadata?.destinationIP?.lowercase()?.contains(q) == true) ||
                    (it.rule?.lowercase()?.contains(q) == true) ||
                    (it.displayOutbound().lowercase().contains(q)) ||
                    (it.metadata?.processPath?.lowercase()?.contains(q) == true)
            }
        }
        adapter.submitList(filtered)
        binding.layoutEmpty.isVisible = filtered.isEmpty()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_pause_resume -> {
                isPaused = !isPaused
                if (isPaused) {
                    item.setIcon(R.drawable.ic_baseline_play_arrow_24)
                    item.title = getString(R.string.connections_resume)
                    snackbar(getString(R.string.connections_pause)).show()
                } else {
                    item.setIcon(R.drawable.ic_baseline_pause_24)
                    item.title = getString(R.string.connections_pause)
                    snackbar(getString(R.string.connections_resume)).show()
                }
                return true
            }

            R.id.action_close_all -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.connections_close_all)
                    .setMessage(R.string.connections_close_all_confirm)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        closeAllConnections()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return true
            }
        }
        return false
    }

    private fun closeAllConnections() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val request = Request.Builder()
                .url("http://127.0.0.1:9090/connections")
                .delete()
                .build()
            try {
                httpClient.newCall(request).execute()
                withContext(Dispatchers.Main) {
                    snackbar(getString(R.string.connections_closed_all)).show()
                    fetchConnections()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    snackbar(e.readableMessage).show()
                }
            }
        }
    }

    private fun closeSingleConnection(connectionId: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val request = Request.Builder()
                .url("http://127.0.0.1:9090/connections/$connectionId")
                .delete()
                .build()
            try {
                httpClient.newCall(request).execute()
                withContext(Dispatchers.Main) {
                    snackbar(getString(R.string.connection_disconnected)).show()
                    fetchConnections()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    snackbar(e.readableMessage).show()
                }
            }
        }
    }

    private fun showConnectionDetails(item: ClashConnection) {
        val ctx = requireContext()
        val network = item.metadata?.network?.uppercase() ?: "TCP"
        val dest = item.displayDestination()
        val src = item.displaySource()
        val outbound = item.displayOutbound()
        val rule = item.displayRule()
        val tx = Formatter.formatFileSize(ctx, item.upload)
        val rx = Formatter.formatFileSize(ctx, item.download)
        val proc = item.metadata?.processPath ?: ""

        val sb = StringBuilder()
        sb.append("${getString(R.string.connection_destination)}: $dest\n")
        sb.append("${getString(R.string.connection_source)}: $src\n")
        sb.append("网络协议: $network\n")
        sb.append("${getString(R.string.connection_outbound)}: $outbound\n")
        sb.append("${getString(R.string.connection_rule)}: $rule\n")
        sb.append("${getString(R.string.connection_traffic)}: ↑ $tx   ↓ $rx\n")
        if (proc.isNotBlank()) {
            sb.append("${getString(R.string.connection_process)}: $proc\n")
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(item.displayHost())
            .setMessage(sb.toString().trim())
            .setPositiveButton(R.string.connection_disconnect) { _, _ ->
                closeSingleConnection(item.id)
            }
            .setNeutralButton(R.string.connection_add_rule) { _, _ ->
                showQuickAddRuleDialog(item.displayHost())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showQuickAddRuleDialog(target: String) {
        val options = arrayOf(
            getString(R.string.route_bypass),  // 直连
            getString(R.string.route_proxy),   // 代理
            getString(R.string.route_block)    // 阻断
        )
        val outboundValues = longArrayOf(-1L, 0L, -2L)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.connection_add_rule) + ": $target")
            .setItems(options) { _, which ->
                val chosenOutbound = outboundValues[which]
                val isIp = target.isIpAddress()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val nextOrder = SagerDatabase.rulesDao.nextOrder() ?: 1L
                    val newRule = RuleEntity(
                        name = target,
                        enabled = true,
                        userOrder = nextOrder,
                        domains = if (!isIp) target else "",
                        ip = if (isIp) target else "",
                        outbound = chosenOutbound
                    )
                    SagerDatabase.rulesDao.createRule(newRule)
                    SagerNet.reloadService()
                    withContext(Dispatchers.Main) {
                        snackbar("已为 $target 添加分流规则并重新加载").show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class ConnectionsAdapter :
        RecyclerView.Adapter<ConnectionsAdapter.ConnectionViewHolder>() {

        private var items = listOf<ClashConnection>()

        @SuppressLint("NotifyDataSetChanged")
        fun submitList(newItems: List<ClashConnection>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConnectionViewHolder {
            val binding = ItemConnectionBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ConnectionViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ConnectionViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ConnectionViewHolder(private val itemBinding: ItemConnectionBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(item: ClashConnection) {
                val ctx = itemView.context
                itemBinding.textDestination.text = item.displayDestination()

                val net = item.metadata?.network?.uppercase() ?: "TCP"
                itemBinding.badgeNetwork.text = net

                val outbound = item.displayOutbound()
                itemBinding.badgeOutbound.text = outbound

                when (outbound.lowercase()) {
                    "bypass", "direct" -> {
                        itemBinding.badgeOutbound.setTextColor(Color.parseColor("#4CAF50")) // Green
                    }
                    "block", "reject" -> {
                        itemBinding.badgeOutbound.setTextColor(Color.parseColor("#F44336")) // Red
                    }
                    else -> {
                        itemBinding.badgeOutbound.setTextColor(Color.parseColor("#00ACC1")) // Cyan/Primary
                    }
                }

                itemBinding.textRule.text = "命中: ${item.displayRule()}"
                itemBinding.textSourceProcess.text = item.displaySource()

                val tx = Formatter.formatFileSize(ctx, item.upload)
                val rx = Formatter.formatFileSize(ctx, item.download)
                itemBinding.textTraffic.text = "↑ $tx  ↓ $rx"

                itemView.setOnClickListener {
                    showConnectionDetails(item)
                }
            }
        }
    }
}
