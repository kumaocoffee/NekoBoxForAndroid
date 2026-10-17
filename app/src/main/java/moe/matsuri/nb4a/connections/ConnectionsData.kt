package moe.matsuri.nb4a.connections

import com.google.gson.annotations.SerializedName

data class ClashConnections(
    @SerializedName("downloadTotal") val downloadTotal: Long = 0,
    @SerializedName("uploadTotal") val uploadTotal: Long = 0,
    @SerializedName("connections") val connections: List<ClashConnection>? = null
)

data class ClashConnection(
    @SerializedName("id") val id: String,
    @SerializedName("metadata") val metadata: ClashMetadata? = null,
    @SerializedName("upload") val upload: Long = 0,
    @SerializedName("download") val download: Long = 0,
    @SerializedName("start") val start: String? = null,
    @SerializedName("chains") val chains: List<String>? = null,
    @SerializedName("rule") val rule: String? = null,
    @SerializedName("rulePayload") val rulePayload: String? = null
) {
    fun displayHost(): String {
        val host = metadata?.host
        if (!host.isNullOrBlank()) return host
        val destIp = metadata?.destinationIP
        if (!destIp.isNullOrBlank()) return destIp
        return "Unknown"
    }

    fun displayPort(): String {
        return metadata?.destinationPort ?: ""
    }

    fun displayDestination(): String {
        val port = displayPort()
        val host = displayHost()
        return if (port.isNotBlank()) "$host:$port" else host
    }

    fun displaySource(): String {
        val srcIp = metadata?.sourceIP ?: ""
        val srcPort = metadata?.sourcePort ?: ""
        val proc = metadata?.processPath ?: ""
        val prefix = if (proc.isNotBlank()) proc.substringAfterLast("/") + " · " else ""
        return if (srcPort.isNotBlank()) "$prefix$srcIp:$srcPort" else "$prefix$srcIp"
    }

    fun displayOutbound(): String {
        val chain = chains?.lastOrNull()
        if (!chain.isNullOrBlank()) return chain
        return "proxy"
    }

    fun displayRule(): String {
        val r = rule
        val payload = rulePayload
        return when {
            !r.isNullOrBlank() && !payload.isNullOrBlank() -> "$r ($payload)"
            !r.isNullOrBlank() -> r
            else -> "Match"
        }
    }
}

data class ClashMetadata(
    @SerializedName("network") val network: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("sourceIP") val sourceIP: String? = null,
    @SerializedName("destinationIP") val destinationIP: String? = null,
    @SerializedName("sourcePort") val sourcePort: String? = null,
    @SerializedName("destinationPort") val destinationPort: String? = null,
    @SerializedName("host") val host: String? = null,
    @SerializedName("dnsMode") val dnsMode: String? = null,
    @SerializedName("processPath") val processPath: String? = null
)
