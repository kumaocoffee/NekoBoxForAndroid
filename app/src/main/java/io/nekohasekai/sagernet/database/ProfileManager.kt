package io.nekohasekai.sagernet.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import java.io.IOException
import java.sql.SQLException
import java.util.*


object ProfileManager {

    interface Listener {
        suspend fun onAdd(profile: ProxyEntity)
        suspend fun onUpdated(data: TrafficData)
        suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean)
        suspend fun onRemoved(groupId: Long, profileId: Long)
    }

    interface RuleListener {
        suspend fun onAdd(rule: RuleEntity)
        suspend fun onUpdated(rule: RuleEntity)
        suspend fun onRemoved(ruleId: Long)
        suspend fun onCleared()
    }

    private val listeners = ArrayList<Listener>()
    private val ruleListeners = ArrayList<RuleListener>()

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    suspend fun ruleIterator(what: suspend RuleListener.() -> Unit) {
        val ruleListeners = synchronized(ruleListeners) {
            ruleListeners.toList()
        }
        for (listener in ruleListeners) {
            what(listener)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun addListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.add(listener)
        }
    }

    fun removeListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.remove(listener)
        }
    }

    suspend fun createProfile(groupId: Long, bean: AbstractBean): ProxyEntity {
        bean.applyDefaultValues()

        val profile = ProxyEntity(groupId = groupId).apply {
            id = 0
            putBean(bean)
            userOrder = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1
        }
        profile.id = SagerDatabase.proxyDao.addProxy(profile)
        iterator { onAdd(profile) }
        return profile
    }

    suspend fun updateProfile(profile: ProxyEntity) {
        SagerDatabase.proxyDao.updateProxy(profile)
        iterator { onUpdated(profile, false) }
    }

    suspend fun updateProfile(profiles: List<ProxyEntity>) {
        SagerDatabase.proxyDao.updateProxy(profiles)
        profiles.forEach {
            iterator { onUpdated(it, false) }
        }
    }

    suspend fun deleteProfile2(groupId: Long, profileId: Long) {
        if (SagerDatabase.proxyDao.deleteById(profileId) == 0) return
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
    }

    suspend fun deleteProfile(groupId: Long, profileId: Long) {
        if (SagerDatabase.proxyDao.deleteById(profileId) == 0) return
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
        iterator { onRemoved(groupId, profileId) }
        if (SagerDatabase.proxyDao.countByGroup(groupId) > 1) {
            GroupManager.rearrange(groupId)
        }
    }

    fun getProfile(profileId: Long): ProxyEntity? {
        if (profileId == 0L) return null
        return try {
            SagerDatabase.proxyDao.getById(profileId)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            null
        }
    }

    fun getProfiles(profileIds: List<Long>): List<ProxyEntity> {
        if (profileIds.isEmpty()) return listOf()
        return try {
            SagerDatabase.proxyDao.getEntities(profileIds)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            listOf()
        }
    }

    // postUpdate: post to listeners, don't change the DB

    suspend fun postUpdate(profileId: Long, noTraffic: Boolean = false) {
        postUpdate(getProfile(profileId) ?: return, noTraffic)
    }

    suspend fun postUpdate(profile: ProxyEntity, noTraffic: Boolean = false) {
        iterator { onUpdated(profile, noTraffic) }
    }

    suspend fun postUpdate(data: TrafficData) {
        iterator { onUpdated(data) }
    }

    suspend fun createRule(rule: RuleEntity, post: Boolean = true): RuleEntity {
        rule.userOrder = SagerDatabase.rulesDao.nextOrder() ?: 1
        rule.id = SagerDatabase.rulesDao.createRule(rule)
        if (post) {
            ruleIterator { onAdd(rule) }
        }
        return rule
    }

    suspend fun updateRule(rule: RuleEntity) {
        SagerDatabase.rulesDao.updateRule(rule)
        ruleIterator { onUpdated(rule) }
    }

    suspend fun deleteRule(ruleId: Long) {
        SagerDatabase.rulesDao.deleteById(ruleId)
        ruleIterator { onRemoved(ruleId) }
    }

    suspend fun deleteRules(rules: List<RuleEntity>) {
        SagerDatabase.rulesDao.deleteRules(rules)
        ruleIterator {
            rules.forEach {
                onRemoved(it.id)
            }
        }
    }

    val GOOGLE_PLAY_DOMAINS = listOf(
        "geosite:google",
        "geosite:youtube",
        "googlevideo.com",
        "xn--ngstr-lra8j.com",
        "googleapis.cn",
        "gvt1.com",
        "gvt2.com",
        "gvt3.com",
        "play.googleapis.com",
        "googleusercontent.com",
        "connectivitycheck.gstatic.com"
    ).joinToString("\n")

    val GOOGLE_PLAY_PACKAGES = setOf(
        "com.android.vending",
        "com.google.android.gms"
    )

    private suspend fun createDefaultRules() {
        val isZh = Locale.getDefault().language == "zh"

        // 1. 屏蔽 QUIC
        createRule(
            RuleEntity(
                name = if (isZh) "屏蔽 QUIC" else "Block QUIC",
                port = "443",
                network = "udp",
                outbound = -2,
                enabled = true
            ), false
        )
        // 2. 屏蔽广告
        createRule(
            RuleEntity(
                name = if (isZh) "屏蔽广告" else "Block Ads",
                domains = "geosite:category-ads-all",
                outbound = -2,
                enabled = true
            ), false
        )
        // 3. 📹 油管与谷歌生态
        createRule(
            RuleEntity(
                name = if (isZh) "📹 油管与谷歌生态" else "📹 YouTube & Google",
                domains = GOOGLE_PLAY_DOMAINS,
                packages = GOOGLE_PLAY_PACKAGES,
                outbound = 0,
                enabled = true
            ), false
        )
        // 4. 中国 域名规则
        createRule(
            RuleEntity(
                name = if (isZh) "中国 域名规则" else "Bypass CN Domains",
                domains = "geosite:cn",
                outbound = -1,
                enabled = true
            ), false
        )
        // 5. 中国 IP 规则
        createRule(
            RuleEntity(
                name = if (isZh) "中国 IP 规则" else "Bypass CN IP",
                ip = "geoip:cn",
                outbound = -1,
                enabled = true
            ), false
        )
        // 6. 🌍 国外媒体与GFW
        createRule(
            RuleEntity(
                name = if (isZh) "🌍 国外媒体与GFW" else "🌍 Global Media & GFW",
                domains = "geosite:geolocation-!cn",
                outbound = 0,
                enabled = true
            ), false
        )
    }

    suspend fun checkRulesMigration() {
        if (DataStore.rulesVersion < 4) {
            DataStore.rulesVersion = 4
            val rules = SagerDatabase.rulesDao.allRules()
            if (rules.isEmpty()) return

            // 1. 删除 OpenAI、Telegram、哔哩哔哩、微软服务等不需要的分流规则
            val toDelete = rules.filter {
                it.domains.contains("geosite:openai") ||
                it.domains.contains("geosite:telegram") ||
                it.domains.contains("geosite:bilibili") ||
                it.domains.contains("geosite:microsoft")
            }
            if (toDelete.isNotEmpty()) {
                SagerDatabase.rulesDao.deleteRules(toDelete)
            }

            // 2. 重新排列保留的 6 项规则，并按截图顺序严格编号对齐
            val remaining = SagerDatabase.rulesDao.allRules()
            val isZh = Locale.getDefault().language == "zh"

            val quicRule = remaining.find { it.port == "443" && it.network == "udp" && it.outbound == -2L }
                ?: createRule(RuleEntity(name = if (isZh) "屏蔽 QUIC" else "Block QUIC", port = "443", network = "udp", outbound = -2, enabled = true), false)
            val adsRule = remaining.find { it.domains.contains("geosite:category-ads-all") && it.outbound == -2L }
                ?: createRule(RuleEntity(name = if (isZh) "屏蔽广告" else "Block Ads", domains = "geosite:category-ads-all", outbound = -2, enabled = true), false)
            val googleRule = remaining.find { (it.domains.contains("googlevideo.com") || it.packages.contains("com.android.vending") || it.domains.contains("googleapis.cn")) && it.outbound == 0L }
                ?: createRule(RuleEntity(name = if (isZh) "📹 油管与谷歌生态" else "📹 YouTube & Google", domains = GOOGLE_PLAY_DOMAINS, packages = GOOGLE_PLAY_PACKAGES, outbound = 0, enabled = true), false)
            val cnDomainRule = remaining.find { it.domains.contains("geosite:cn") && it.outbound == -1L }
                ?: createRule(RuleEntity(name = if (isZh) "中国 域名规则" else "Bypass CN Domains", domains = "geosite:cn", outbound = -1, enabled = true), false)
            val cnIpRule = remaining.find { it.ip.contains("geoip:cn") && it.outbound == -1L }
                ?: createRule(RuleEntity(name = if (isZh) "中国 IP 规则" else "Bypass CN IP", ip = "geoip:cn", outbound = -1, enabled = true), false)
            val gfwRule = remaining.find { it.domains.contains("geosite:geolocation-!cn") && it.outbound == 0L }
                ?: createRule(RuleEntity(name = if (isZh) "🌍 国外媒体与GFW" else "🌍 Global Media & GFW", domains = "geosite:geolocation-!cn", outbound = 0, enabled = true), false)

            val orderedList = listOf(quicRule, adsRule, googleRule, cnDomainRule, cnIpRule, gfwRule)
            orderedList.forEachIndexed { index, rule ->
                rule.userOrder = (index + 1).toLong()
                rule.enabled = true
                when (index) {
                    0 -> rule.name = if (isZh) "屏蔽 QUIC" else "Block QUIC"
                    1 -> rule.name = if (isZh) "屏蔽广告" else "Block Ads"
                    2 -> {
                        rule.name = if (isZh) "📹 油管与谷歌生态" else "📹 YouTube & Google"
                        rule.domains = GOOGLE_PLAY_DOMAINS
                        rule.packages = GOOGLE_PLAY_PACKAGES
                    }
                    3 -> rule.name = if (isZh) "中国 域名规则" else "Bypass CN Domains"
                    4 -> {
                        rule.name = if (isZh) "中国 IP 规则" else "Bypass CN IP"
                        rule.ip = "geoip:cn"
                    }
                    5 -> rule.name = if (isZh) "🌍 国外媒体与GFW" else "🌍 Global Media & GFW"
                }
                SagerDatabase.rulesDao.updateRule(rule)
            }
        }
    }

    suspend fun getRules(): List<RuleEntity> {
        var rules = SagerDatabase.rulesDao.allRules()
        if (rules.isEmpty() && !DataStore.rulesFirstCreate) {
            DataStore.rulesFirstCreate = true
            DataStore.rulesVersion = 4
            createDefaultRules()
            rules = SagerDatabase.rulesDao.allRules()
        } else {
            checkRulesMigration()
            rules = SagerDatabase.rulesDao.allRules()
        }
        return rules
    }

}
