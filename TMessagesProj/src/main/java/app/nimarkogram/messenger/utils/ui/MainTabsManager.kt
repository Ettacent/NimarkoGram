package app.nimarkogram.messenger.utils.ui

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import app.nimarkogram.messenger.NimarkoConfig
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.glass.GlassTabView

object MainTabsManager {

    enum class TabType {
        CHATS,
        SETTINGS,
        PROFILE,
        SEARCH
    }

    data class Tab(
        var type: TabType,

        @JvmField
        var enabled: Boolean
    )

    private data class TabsSnapshot(
        val order: String?,
        val tabs: List<Tab>,
        val enabledTabs: List<Tab>,
        val positions: Map<TabType, Int>
    )

    @Volatile
    private var tabsSnapshot: TabsSnapshot? = null

    private fun getSnapshot(): TabsSnapshot {
        val order = NimarkoConfig.mainTabsOrder
        tabsSnapshot?.let { if (it.order == order) return it }
        return synchronized(this) {
            tabsSnapshot?.let { if (it.order == order) return@synchronized it }
            val tabs = loadTabs(order)
            val enabled = tabs.filter { it.enabled }.let { result ->
                if (result.any { it.type == TabType.CHATS }) {
                    result
                } else {
                    listOf(Tab(TabType.CHATS, true)) + result
                }
            }
            val pagerTabs = enabled.filterNot { it.type == TabType.SEARCH }
            TabsSnapshot(
                order,
                tabs,
                enabled,
                pagerTabs.mapIndexed { index, tab -> tab.type to index }.toMap()
            ).also { tabsSnapshot = it }
        }
    }

    @JvmOverloads
    fun getEnabledTabs(includeSearch: Boolean = false): List<Tab> {
        val enabled = getSnapshot().enabledTabs
        val result = if (includeSearch) {
            enabled
        } else {
            enabled.filterNot { it.type == TabType.SEARCH }
        }
        return result.map { it.copy() }
    }

    fun getAllTabs(): List<Tab> {
        return getSnapshot().tabs.map { it.copy() }
    }

    private fun loadTabs(value: String?): List<Tab> {
        val allPossibleTypes = TabType.entries.toMutableList()

        val result = mutableListOf<Tab>()

        if (value == null) {
            result.add(Tab(TabType.PROFILE, true))
            result.add(Tab(TabType.CHATS, true))
            result.add(Tab(TabType.SETTINGS, true))
        } else {
            val parts = value.split(",")

            for (p in parts) {
                val enabled = !p.startsWith("!")
                val typeName = if (enabled) p else p.substring(1)

                try {
                    val type = TabType.valueOf(typeName)
                    if (allPossibleTypes.remove(type)) {
                        result.add(Tab(type, if (type == TabType.CHATS) true else enabled))
                    }
                } catch (_: Exception) {
                }
            }

            for (newType in allPossibleTypes) {
                result.add(Tab(newType, true))
            }
        }

        return result
    }

    fun createTabView(
        context: Context,
        resourceProvider: Theme.ResourcesProvider?,
        currentAccount: Int,
        type: TabType,
        fromSettings: Boolean,
        showSearch: Boolean
    ): GlassTabView {
        return when (type) {
            TabType.CHATS -> GlassTabView.createMainTab(
                context,
                resourceProvider,
                GlassTabView.TabAnimation.CHATS,
                R.string.MainTabsChats
            )

            TabType.SETTINGS -> {
                if (!hasTab(TabType.PROFILE) && !fromSettings) {
                    GlassTabView.createAvatar(
                        context,
                        resourceProvider,
                        currentAccount,
                        R.string.Settings
                    )
                } else {
                    GlassTabView.createMainTab(
                        context,
                        resourceProvider,
                        GlassTabView.TabAnimation.SETTINGS,
                        R.string.Settings
                    )
                }
            }

            TabType.PROFILE -> GlassTabView.createAvatar(
                context,
                resourceProvider,
                currentAccount,
                R.string.MainTabsProfile
            )

            TabType.SEARCH -> {
                if (showSearch) {
                    GlassTabView.createStaticTab(
                        context,
                        resourceProvider,
                        R.drawable.outline_header_search,
                        R.string.Search,
                        false
                    )
                } else {
                    GlassTabView(context).apply {
                        visibility = View.GONE
                        layoutParams = RecyclerView.LayoutParams(0, 0)
                    }
                }
            }
        }
    }

    fun getPosition(type: TabType): Int {
        return getSnapshot().positions[type] ?: -1
    }

    fun hasTab(type: TabType): Boolean {
        return getPosition(type) != -1
    }

    fun saveTabs(tabs: List<Tab>) {
        val seen = mutableSetOf<TabType>()
        val normalized = tabs.filter { seen.add(it.type) }.toMutableList()
        if (normalized.none { it.type == TabType.CHATS }) normalized.add(0, Tab(TabType.CHATS, true))
        normalized.first { it.type == TabType.CHATS }.enabled = true
        normalized.firstOrNull { it.type == TabType.SEARCH }?.let {
            normalized.remove(it)
            normalized.add(it)
        }
        val order = normalized.map { tab ->
            val prefix = if (tab.enabled || tab.type == TabType.CHATS) "" else "!"
            "$prefix${tab.type.name}"
        }
        NimarkoConfig.setMainTabsOrder(order.joinToString(","))
        tabsSnapshot = null
    }

}
