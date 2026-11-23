package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.LiveTvLoadReport
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.serialization.json.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Address lookups for desktop IPTV HTTP requests (mpv uses the OS lookup).
 * The setting itself is [LiveTvDns] (shared code); this does the lookups.
 *
 * - "System" (the default) uses the phone/TV's normal lookup, like every other app.
 * - Cloudflare or Google ask that service instead (over HTTPS, so an internet company's own
 *   lookup can't block it). If it finds nothing (a home server name like "nas.local", or Wi-Fi
 *   that blocks the service), the normal lookup is used after all.
 * - On "System", an IPTV provider's server the normal lookup can't find is tried once more with
 *   Cloudflare, then Google. Only provider addresses get that retry, so home ad blockers and
 *   family filters keep working for everything else.
 *
 * Answers are remembered for a few minutes, so only the first lookup of a server is slower.
 * Per device (not synced): which lookup works depends on the network the device is on.
 */
internal object ForkDns {
    private const val KEY = "choice"
    private const val MIN_TTL_S = 60L
    private const val MAX_TTL_S = 600L
    private const val MAX_CACHE = 256

    private val prefs = DesktopStorage.store("livetv_dns")
    @Volatile var choice: DnsChoice = prefs.getString(KEY)?.let { runCatching { DnsChoice.valueOf(it) }.getOrNull() } ?: DnsChoice.SYSTEM
        private set

    private class Answer(val addresses: List<InetAddress>, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, Answer>()
    private val reported = ConcurrentHashMap.newKeySet<String>()
    fun set(value: DnsChoice) {
        if (value == choice) return
        choice = value
        prefs.putString(KEY, value.name)
        cache.clear()
        reported.clear()
        LiveTvLoadReport.add("DNS: ${label(value)}")
    }

    /** Sports, sync: the chosen service, no automatic retry. */
    val app: Dns = Lookup { false }

    /** Clients that only talk to IPTV providers (playlists, guides, On Demand, portals, recordings). */
    val provider: Dns = Lookup { true }

    private class Lookup(private val retryFor: (String) -> Boolean) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val host = hostname.lowercase()
            val allowRetry = retryFor(host)
            if (!isPublicName(host)) return Dns.SYSTEM.lookup(hostname)
            val chosen = choice
            if (chosen != DnsChoice.SYSTEM) {
                cached(host)?.let { return it }
                resolve(chosen, hostname)?.let { return it }
                return Dns.SYSTEM.lookup(hostname)
            }
            try {
                return Dns.SYSTEM.lookup(hostname)
            } catch (notFound: UnknownHostException) {
                if (!allowRetry) throw notFound
                cached(host)?.let { return it }
                for (service in listOf(DnsChoice.CLOUDFLARE, DnsChoice.GOOGLE)) {
                    val found = resolve(service, hostname) ?: continue
                    if (reported.add(host)) LiveTvLoadReport.add("DNS: normal lookup failed; ${label(service)} resolved the provider")
                    return found
                }
                if (reported.add(host)) LiveTvLoadReport.add("DNS: provider not found (normal lookup, Cloudflare and Google)")
                throw notFound
            }
        }
    }

    /** Home-network names, plain IPs and "localhost" always use the normal lookup. */
    private fun isPublicName(host: String): Boolean {
        if (host.isEmpty() || '.' !in host || host == "localhost") return false
        if (host.startsWith("[") || ':' in host) return false
        if (host.all { it.isDigit() || it == '.' }) return false
        val local = listOf(".local", ".lan", ".home", ".internal", ".localdomain", ".home.arpa")
        return local.none { host.endsWith(it) }
    }

    private fun cached(host: String): List<InetAddress>? {
        val answer = cache[host] ?: return null
        if (answer.expiresAt < System.currentTimeMillis()) { cache.remove(host); return null }
        return answer.addresses
    }

    private val client: OkHttpClient by lazy {
        // The services are reached by their IP address, so no lookup is needed to reach them.
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private fun endpoints(service: DnsChoice): List<String> = when (service) {
        DnsChoice.CLOUDFLARE -> listOf("https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query")
        DnsChoice.GOOGLE -> listOf("https://8.8.8.8/resolve", "https://8.8.4.4/resolve")
        DnsChoice.SYSTEM -> emptyList()
    }

    /** The service's answer (IPv4 first, then IPv6), or null if it found nothing or couldn't be reached. */
    private fun resolve(service: DnsChoice, hostname: String): List<InetAddress>? {
        for (endpoint in endpoints(service)) {
            val v4 = query(endpoint, hostname, type = 1) ?: continue
            val all = if (v4.first.isNotEmpty()) v4 else (query(endpoint, hostname, type = 28) ?: v4)
            if (all.first.isEmpty()) return null
            val ttl = all.second.coerceIn(MIN_TTL_S, MAX_TTL_S)
            if (cache.size >= MAX_CACHE) cache.clear()
            cache[hostname.lowercase()] = Answer(all.first, System.currentTimeMillis() + ttl * 1000)
            return all.first
        }
        return null
    }

    /** One question to one server: (addresses, shortest TTL), or null if the server couldn't be asked. */
    private fun query(endpoint: String, hostname: String, type: Int): Pair<List<InetAddress>, Long>? = try {
        val request = Request.Builder()
            .url("$endpoint?name=${java.net.URLEncoder.encode(hostname, "UTF-8")}&type=$type")
            .header("Accept", "application/dns-json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            val status = json["Status"]?.jsonPrimitive?.intOrNull
            // 0 = found, 3 = no such name: both are real answers. Anything else: ask the next server.
            if (status != 0 && status != 3) return null
            val answers = json["Answer"] as? JsonArray
            val list = ArrayList<InetAddress>()
            var ttl = MAX_TTL_S
            for (answer in answers.orEmpty()) {
                val a = answer as? JsonObject ?: continue
                if (a["type"]?.jsonPrimitive?.intOrNull != type) continue
                val data = a["data"]?.jsonPrimitive?.contentOrNull ?: continue
                val literal = when (type) {
                    1 -> data.takeIf { d -> d.isNotEmpty() && d.all { it.isDigit() || it == '.' } }
                    else -> data.takeIf { d -> d.isNotEmpty() && ':' in d && d.all { it.isLetterOrDigit() || it == ':' || it == '.' } }
                } ?: continue
                // A literal address: no lookup happens here.
                val parsed = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: continue
                list += InetAddress.getByAddress(hostname, parsed.address)
                ttl = minOf(ttl, a["TTL"]?.jsonPrimitive?.longOrNull ?: MAX_TTL_S)
            }
            list to ttl
        }
    } catch (_: Exception) {
        null
    }

    fun label(value: DnsChoice): String = when (value) {
        DnsChoice.SYSTEM -> "System"
        DnsChoice.CLOUDFLARE -> "Cloudflare (1.1.1.1)"
        DnsChoice.GOOGLE -> "Google (8.8.8.8)"
    }
}
