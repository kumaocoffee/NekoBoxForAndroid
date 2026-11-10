package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutRouteItemBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.UndoSnackbarManager

class RouteFragment : ToolbarFragment(R.layout.layout_route), Toolbar.OnMenuItemClickListener {

    enum class RouteFilter { ALL, PROXY, BYPASS, BLOCK }

    lateinit var activity: MainActivity
    lateinit var ruleListView: RecyclerView
    lateinit var ruleAdapter: RuleAdapter
    lateinit var undoManager: UndoSnackbarManager<RuleEntity>
    lateinit var itemTouchHelper: ItemTouchHelper

    lateinit var chipGroup: ChipGroup
    lateinit var chipAll: Chip
    lateinit var chipProxy: Chip
    lateinit var chipBypass: Chip
    lateinit var chipBlock: Chip

    var currentFilter: RouteFilter = RouteFilter.ALL

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activity = requireActivity() as MainActivity

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_route)
        toolbar.inflateMenu(R.menu.add_route_menu)
        toolbar.setOnMenuItemClickListener(this)

        chipGroup = view.findViewById(R.id.filter_chip_group)
        chipAll = view.findViewById(R.id.chip_all)
        chipProxy = view.findViewById(R.id.chip_proxy)
        chipBypass = view.findViewById(R.id.chip_bypass)
        chipBlock = view.findViewById(R.id.chip_block)

        chipGroup.setOnCheckedChangeListener { _, checkedId ->
            currentFilter = when (checkedId) {
                R.id.chip_proxy -> RouteFilter.PROXY
                R.id.chip_bypass -> RouteFilter.BYPASS
                R.id.chip_block -> RouteFilter.BLOCK
                else -> RouteFilter.ALL
            }
            ruleAdapter.applyFilter()
        }

        ruleListView = view.findViewById(R.id.route_list)
        ruleListView.layoutManager = FixedLinearLayoutManager(ruleListView)
        ruleAdapter = RuleAdapter()
        ProfileManager.addListener(ruleAdapter)
        ruleListView.adapter = ruleAdapter
        undoManager = UndoSnackbarManager(activity, ruleAdapter)

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int {
                return ItemTouchHelper.START
            }

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int {
                return if (currentFilter == RouteFilter.ALL) {
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN
                } else {
                    0
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                if (index == RecyclerView.NO_POSITION || index >= ruleAdapter.displayedList.size) return
                val rule = ruleAdapter.displayedList[index]
                ruleAdapter.remove(index)
                undoManager.remove(index to rule)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                if (currentFilter != RouteFilter.ALL) return false
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                ruleAdapter.move(from, to)
                return true
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                ruleAdapter.commitMove()
            }
        })
        itemTouchHelper.attachToRecyclerView(ruleListView)
    }

    override fun onDestroy() {
        if (::ruleAdapter.isInitialized) {
            ProfileManager.removeListener(ruleAdapter)
        }
        super.onDestroy()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_route -> {
                startActivity(Intent(context, RouteSettingsActivity::class.java))
            }
            R.id.action_reset_route -> {
                MaterialAlertDialogBuilder(activity).setTitle(R.string.confirm)
                    .setMessage(R.string.clear_profiles_message)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        runOnDefaultDispatcher {
                            SagerDatabase.rulesDao.reset()
                            DataStore.rulesFirstCreate = false
                            ruleAdapter.reload()
                        }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }
            R.id.action_manage_assets -> {
                startActivity(Intent(requireContext(), AssetsActivity::class.java))
            }
            R.id.action_route_help -> {
                requireContext().launchCustomTab("https://matsuridayo.github.io/nb4a-route/")
            }
        }
        return true
    }

    inner class RuleAdapter : RecyclerView.Adapter<RuleAdapter.RuleHolder>(),
        ProfileManager.RuleListener, UndoSnackbarManager.Interface<RuleEntity> {

        val ruleList = ArrayList<RuleEntity>()
        val displayedList = ArrayList<RuleEntity>()

        fun applyFilter() {
            displayedList.clear()
            when (currentFilter) {
                RouteFilter.ALL -> displayedList.addAll(ruleList)
                RouteFilter.PROXY -> displayedList.addAll(ruleList.filter { it.outbound >= 0L })
                RouteFilter.BYPASS -> displayedList.addAll(ruleList.filter { it.outbound == -1L })
                RouteFilter.BLOCK -> displayedList.addAll(ruleList.filter { it.outbound == -2L })
            }
            notifyDataSetChanged()
            updateFilterTabs()
        }

        private fun updateFilterTabs() {
            if (!::chipAll.isInitialized) return
            val totalCount = ruleList.size
            val proxyCount = ruleList.count { it.outbound >= 0L }
            val bypassCount = ruleList.count { it.outbound == -1L }
            val blockCount = ruleList.count { it.outbound == -2L }

            chipAll.text = getString(R.string.filter_all, totalCount)
            chipProxy.text = getString(R.string.filter_proxy, proxyCount)
            chipBypass.text = getString(R.string.filter_bypass, bypassCount)
            chipBlock.text = getString(R.string.filter_block, blockCount)
        }

        suspend fun reload() {
            val rules = ProfileManager.getRules()
            ruleListView.post {
                ruleList.clear()
                ruleList.addAll(rules)
                applyFilter()
            }
        }

        init {
            runOnDefaultDispatcher {
                reload()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleHolder {
            return RuleHolder(LayoutRouteItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: RuleHolder, position: Int) {
            holder.bind(displayedList[position], position)
        }

        override fun getItemCount(): Int {
            return displayedList.size
        }

        override fun getItemId(position: Int): Long {
            return displayedList[position].id
        }

        fun move(from: Int, to: Int) {
            if (from < 0 || to < 0 || from >= displayedList.size || to >= displayedList.size || from == to) return

            if (from < to) {
                for (i in from until to) {
                    java.util.Collections.swap(displayedList, i, i + 1)
                }
            } else {
                for (i in from downTo to + 1) {
                    java.util.Collections.swap(displayedList, i, i - 1)
                }
            }

            // Sync with ruleList
            if (currentFilter == RouteFilter.ALL) {
                ruleList.clear()
                ruleList.addAll(displayedList)
            }

            notifyItemMoved(from, to)
        }

        fun commitMove() {
            // Re-sequence userOrder cleanly (1, 2, 3...)
            displayedList.forEachIndexed { index, ruleEntity ->
                ruleEntity.userOrder = (index + 1).toLong()
            }
            if (currentFilter == RouteFilter.ALL) {
                ruleList.clear()
                ruleList.addAll(displayedList)
            }

            // Update visual index badges after the drop is completed
            notifyItemRangeChanged(0, displayedList.size)

            val toPersist = ruleList.toList()
            runOnDefaultDispatcher {
                SagerDatabase.rulesDao.updateRules(toPersist)
                needReload()
            }
        }

        fun remove(index: Int) {
            if (index < 0 || index >= displayedList.size) return
            val rule = displayedList.removeAt(index)
            ruleList.remove(rule)
            notifyItemRemoved(index)
            updateFilterTabs()
        }

        override fun undo(actions: List<Pair<Int, RuleEntity>>) {
            for ((_, item) in actions) {
                if (!ruleList.any { it.id == item.id }) {
                    ruleList.add(item)
                }
            }
            ruleList.sortBy { it.userOrder }
            applyFilter()
        }

        override fun commit(actions: List<Pair<Int, RuleEntity>>) {
            val rules = actions.map { it.second }
            runOnDefaultDispatcher {
                ProfileManager.deleteRules(rules)
            }
        }

        override suspend fun onAdd(rule: RuleEntity) {
            ruleListView.post {
                ruleList.add(rule)
                applyFilter()
                needReload()
            }
        }

        override suspend fun onUpdated(rule: RuleEntity) {
            val index = ruleList.indexOfFirst { it.id == rule.id }
            if (index == -1) return
            ruleListView.post {
                ruleList[index] = rule
                applyFilter()
                needReload()
            }
        }

        override suspend fun onRemoved(ruleId: Long) {
            val index = ruleList.indexOfFirst { it.id == ruleId }
            if (index == -1) {
                onMainDispatcher {
                    needReload()
                }
            } else ruleListView.post {
                ruleList.removeAt(index)
                applyFilter()
                needReload()
            }
        }

        override suspend fun onCleared() {
            ruleListView.post {
                ruleList.clear()
                displayedList.clear()
                notifyDataSetChanged()
                updateFilterTabs()
                needReload()
            }
        }

        inner class RuleHolder(val binding: LayoutRouteItemBinding) :
            RecyclerView.ViewHolder(binding.root) {

            lateinit var rule: RuleEntity

            @SuppressLint("ClickableViewAccessibility")
            fun bind(ruleEntity: RuleEntity, position: Int) {
                rule = ruleEntity

                // 1. Order Index Badge
                binding.orderBadge.text = "#${position + 1}"

                // 2. Profile Display Name
                binding.profileName.text = rule.displayName()

                // 3. Outbound Action Pill Badge
                val isNight = (itemView.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                when (rule.outbound) {
                    0L -> {
                        binding.actionBadge.text = "✈️ " + getString(R.string.route_proxy)
                        binding.actionBadge.setBackgroundResource(R.drawable.bg_badge_proxy)
                        binding.actionBadge.setTextColor(Color.parseColor(if (isNight) "#64B5F6" else "#1976D2"))
                    }
                    -1L -> {
                        binding.actionBadge.text = "⚡ " + getString(R.string.route_bypass)
                        binding.actionBadge.setBackgroundResource(R.drawable.bg_badge_bypass)
                        binding.actionBadge.setTextColor(Color.parseColor(if (isNight) "#81C784" else "#2E7D32"))
                    }
                    -2L -> {
                        binding.actionBadge.text = "🚫 " + getString(R.string.route_block)
                        binding.actionBadge.setBackgroundResource(R.drawable.bg_badge_block)
                        binding.actionBadge.setTextColor(Color.parseColor(if (isNight) "#E57373" else "#D32F2F"))
                    }
                    else -> {
                        val profileName = ProfileManager.getProfile(rule.outbound)?.displayName()
                            ?: getString(R.string.route_profile)
                        binding.actionBadge.text = "🔀 $profileName"
                        binding.actionBadge.setBackgroundResource(R.drawable.bg_badge_custom)
                        binding.actionBadge.setTextColor(Color.parseColor(if (isNight) "#BA68C8" else "#7B1FA2"))
                    }
                }

                // 4. Condition Chips
                // Domain
                if (rule.domains.isNotBlank()) {
                    val domainCount = rule.domains.split("\n", ",").filter { it.isNotBlank() }.size
                    binding.tagDomain.isVisible = true
                    binding.tagDomain.text = if (domainCount == 1) {
                        "🌐 " + rule.domains.trim()
                    } else {
                        "🌐 域名: ${domainCount}项"
                    }
                } else {
                    binding.tagDomain.isVisible = false
                }

                // IP
                if (rule.ip.isNotBlank()) {
                    val ipCount = rule.ip.split("\n", ",").filter { it.isNotBlank() }.size
                    binding.tagIp.isVisible = true
                    binding.tagIp.text = if (ipCount == 1) {
                        "🎯 " + rule.ip.trim()
                    } else {
                        "🎯 IP: ${ipCount}项"
                    }
                } else {
                    binding.tagIp.isVisible = false
                }

                // Port
                if (rule.port.isNotBlank()) {
                    binding.tagPort.isVisible = true
                    binding.tagPort.text = "🔌 端口: ${rule.port.trim()}"
                } else {
                    binding.tagPort.isVisible = false
                }

                // Packages/Apps
                if (rule.packages.isNotEmpty()) {
                    binding.tagApps.isVisible = true
                    binding.tagApps.text = "📱 应用: ${rule.packages.size}个"
                } else {
                    binding.tagApps.isVisible = false
                }

                // Network
                if (rule.network.isNotBlank()) {
                    binding.tagNetwork.isVisible = true
                    binding.tagNetwork.text = "📡 " + rule.network.uppercase()
                } else {
                    binding.tagNetwork.isVisible = false
                }

                // Custom Config
                binding.tagCustom.isVisible = rule.config.isNotBlank()

                // 5. Card click: Directly enters RouteSettingsActivity (fixes accidental switch toggle!)
                itemView.setOnClickListener {
                    startActivity(Intent(it.context, RouteSettingsActivity::class.java).apply {
                        putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, rule.id)
                    })
                }

                // 6. Switch toggle: Isolated click behavior
                binding.enable.setOnCheckedChangeListener(null)
                binding.enable.isChecked = rule.enabled
                binding.enable.setOnCheckedChangeListener { _, isChecked ->
                    runOnDefaultDispatcher {
                        rule.enabled = isChecked
                        SagerDatabase.rulesDao.updateRule(rule)
                        onMainDispatcher {
                            needReload()
                        }
                    }
                }

                // 7. Drag Handle Touch Listener
                binding.dragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        if (currentFilter == RouteFilter.ALL) {
                            itemTouchHelper.startDrag(this)
                        } else {
                            Toast.makeText(
                                itemView.context,
                                R.string.route_drag_disabled_hint,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    false
                }
            }
        }

    }

}