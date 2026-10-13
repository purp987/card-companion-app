package com.cardprice.app.data.vault

import java.io.IOException

/** A recorded request to the fake server. */
data class Call(val method: String, val path: String, val headers: Map<String, String>, val body: String?)

/**
 * In-memory stand-in for TCG Vault: routes "METHOD /path" (query string included in [Call.path]) to
 * canned replies and records every request. No network involved.
 */
class FakeServer : VaultTransport {
    val calls = mutableListOf<Call>()
    private val routes = mutableListOf<Pair<(Call) -> Boolean, (Call) -> VaultResponse>>()
    /** When set, every request fails as if the server were unreachable. */
    var offline = false

    fun on(method: String, pathPrefix: String, reply: (Call) -> VaultResponse): FakeServer {
        routes += ({ c: Call -> c.method == method && c.path.startsWith(pathPrefix) } to reply)
        return this
    }

    fun json(method: String, pathPrefix: String, code: Int = 200, body: () -> String) = on(method, pathPrefix) { VaultResponse(code, body()) }

    override fun execute(method: String, url: String, headers: Map<String, String>, body: String?): VaultResponse {
        if (offline) throw IOException("connect failed")
        val path = url.removePrefix(BASE)
        val call = Call(method, path, headers, body)
        calls += call
        val route = routes.lastOrNull { it.first(call) } ?: return VaultResponse(404, "<pre>Cannot $method $path</pre>") // like Express
        return route.second(call)
    }

    fun api(token: String? = "tok") = VaultApi(baseUrl = { BASE }, token = { token }, transport = this)

    companion object {
        const val BASE = "https://vault.test:8443"
    }
}
