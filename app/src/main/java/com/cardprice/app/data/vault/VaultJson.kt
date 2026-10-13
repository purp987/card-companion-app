package com.cardprice.app.data.vault

import org.json.JSONArray
import org.json.JSONObject

/** Parsing (and request bodies) for the TCG Vault API. Defensive: unknown or missing fields become null. */
internal object VaultJson {

    // ---- helpers ----
    fun JSONObject.str(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }
    fun JSONObject.dbl(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }
    fun JSONObject.lng(k: String): Long? = if (!has(k) || isNull(k)) null else (opt(k) as? Number)?.toLong() ?: optString(k).toLongOrNull()
    fun JSONObject.int(k: String): Int? = lng(k)?.toInt()
    fun JSONObject.bool(k: String): Boolean = when (val v = opt(k)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v == "1" || v.equals("true", ignoreCase = true)
        else -> false
    }

    inline fun <T> JSONArray?.list(parse: (JSONObject) -> T?): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (i in 0 until length()) optJSONObject(i)?.let(parse)?.let(out::add)
        return out
    }

    private fun Any?.orNull(): Any = this ?: JSONObject.NULL

    // ---- catalog ----
    fun product(o: JSONObject): ProductItem? {
        val id = o.lng("id") ?: o.lng("product_id") ?: return null
        return ProductItem(
            id = id,
            name = o.str("name") ?: "",
            imageUrl = o.str("image_url"),
            number = o.str("number"),
            rarity = o.str("rarity"),
            isSealed = o.bool("is_sealed"),
            price = o.dbl("price"),
            price7d = o.dbl("price_7d"),
            groupId = o.lng("group_id") ?: 0,
            setName = o.str("set_name") ?: "",
            categoryId = o.lng("category_id") ?: 0,
            categoryName = o.str("category_name"),
            subType = o.str("sub_type"),
            changePct = o.dbl("change_pct"),
            changeAbs = o.dbl("change_abs"),
            changeWindow = o.str("change_window"),
            qty = o.int("qty"),
        )
    }

    fun set(o: JSONObject): VaultSet? {
        val id = o.lng("id") ?: return null
        return VaultSet(
            id = id,
            name = o.str("name") ?: "",
            abbreviation = o.str("abbreviation"),
            publishedOn = o.str("published_on"),
            imageUrl = o.str("image_url"),
            logoUrl = o.str("logo_url"),
            cardCount = o.int("card_count") ?: 0,
            sealedCount = o.int("sealed_count") ?: 0,
            categoryId = o.lng("category_id"),
            topCard = o.dbl("top_card"),
        )
    }

    fun home(json: String): HomeFeed {
        val o = JSONObject(json)
        val sections = o.optJSONArray("sections").list { s ->
            val kind = s.str("kind") ?: "products"
            val items = s.optJSONArray("items")
            HomeSection(
                id = s.str("id") ?: "",
                kind = kind,
                title = s.str("title") ?: "",
                subtitle = s.str("subtitle"),
                link = s.str("link"),
                products = if (kind == "sets") emptyList() else items.list(::product),
                sets = if (kind == "sets") items.list(::set) else emptyList(),
            )
        }
        return HomeFeed(o.str("asOf"), o.int("historyDays") ?: 0, o.str("window"), sections)
    }

    fun search(json: String): SearchPage {
        val o = JSONObject(json)
        return SearchPage(o.optJSONArray("items").list(::product), o.int("total") ?: 0, o.str("window"))
    }

    fun sets(json: String): List<VaultSet> = JSONObject(json).optJSONArray("sets").list(::set)

    fun setDetail(json: String): SetDetail {
        val o = JSONObject(json)
        val st = o.optJSONObject("stats") ?: JSONObject()
        return SetDetail(
            set = set(o) ?: error("set without id"),
            categoryName = o.str("category_name"),
            stats = SetStats(st.dbl("cards_value"), st.dbl("cards_value_7d"), st.dbl("top_card")),
        )
    }

    fun variant(o: JSONObject): Variant? {
        val id = o.lng("id") ?: return null
        return variant(o, id)
    }

    private fun variant(o: JSONObject, id: Long) = Variant(
        id = id,
        subType = o.str("sub_type") ?: "Normal",
        market = o.dbl("market"),
        low = o.dbl("low"),
        mid = o.dbl("mid"),
        high = o.dbl("high"),
        directLow = o.dbl("direct_low"),
    )

    fun holding(o: JSONObject): Holding? {
        val id = o.lng("id") ?: return null
        return holding(o, id)
    }

    private fun holding(o: JSONObject, id: Long) = Holding(
        id = id,
        portfolioId = o.lng("portfolio_id") ?: 0,
        productId = o.lng("product_id") ?: 0,
        variantId = o.lng("variant_id"),
        qty = o.int("qty") ?: 1,
        condition = o.str("condition"),
        grade = o.str("grade"),
        purchasePrice = o.dbl("purchase_price"),
        customValue = o.dbl("custom_value"),
        addedOn = o.str("added_on"),
        notes = o.str("notes"),
        name = o.str("name"),
        imageUrl = o.str("image_url"),
        number = o.str("number"),
        isSealed = o.bool("is_sealed"),
        setName = o.str("set_name"),
        subType = o.str("sub_type"),
        portfolioName = o.str("portfolio_name"),
        kind = o.str("kind"),
        unitValue = o.dbl("unit_value"),
        value = o.dbl("value"),
        cost = o.dbl("cost"),
    )

    fun productDetail(json: String): ProductDetail {
        val o = JSONObject(json)
        val cm = o.optJSONObject("cardmarket")
        val ext = o.optJSONArray("ext").list { e ->
            val v = e.str("value") ?: return@list null
            ExtField(e.str("displayName") ?: e.str("name") ?: "", v)
        }
        return ProductDetail(
            product = product(o) ?: error("product without id"),
            url = o.str("url"),
            setAbbr = o.str("set_abbr"),
            releasedOn = o.str("released_on"),
            primaryVariantId = o.lng("primary_variant_id"),
            variants = o.optJSONArray("variants").list(::variant),
            watched = o.bool("watched"),
            holdings = o.optJSONArray("holdings").list(::holding),
            cardmarket = cm?.let {
                Cardmarket(it.dbl("trend"), it.dbl("avg"), it.dbl("low"), it.dbl("avg1"), it.dbl("avg7"), it.dbl("avg30"), it.dbl("trend_holo"), it.str("day"), it.str("match_status"))
            },
            graded = o.optJSONArray("graded").list { g ->
                GradedPrice(g.str("grader") ?: "", g.str("grade") ?: "", g.str("grade_key") ?: "", g.dbl("price"), g.int("sales") ?: 0, g.str("trend"), g.str("confidence"))
            },
            gradedFetchedAt = o.optJSONObject("graded_fetch")?.lng("fetched_at"),
            creditsRemaining = o.optJSONObject("api_credits")?.int("remaining"),
            ebaySales = o.optJSONArray("ebay_sales").list { s ->
                EbaySale(
                    id = s.str("id") ?: "",
                    soldDate = s.str("sold_date") ?: "",
                    price = s.dbl("price") ?: return@list null,
                    priceIsRange = s.bool("price_is_range"),
                    title = s.str("title") ?: "",
                    kind = s.str("kind"),
                    grader = s.str("grader"),
                    grade = s.str("grade"),
                    quantity = s.int("quantity") ?: 1,
                )
            },
            jpTwin = o.optJSONObject("jp_twin")?.let(::product),
            ext = ext,
        )
    }

    private fun points(a: JSONArray?): List<PricePoint> = a.list { p ->
        PricePoint(p.str("d") ?: return@list null, p.dbl("v") ?: return@list null)
    }

    fun history(json: String): PriceHistory {
        val o = JSONObject(json)
        return PriceHistory(points(o.optJSONArray("series")), points(o.optJSONArray("cardmarket")))
    }

    fun portfolios(json: String): List<Portfolio> = JSONArray(json).list { p ->
        Portfolio(p.lng("id") ?: return@list null, p.str("name") ?: "Portfolio", p.int("items") ?: 0)
    }

    fun portfolio(json: String): PortfolioView {
        val o = JSONObject(json)
        val s = o.optJSONObject("summary") ?: JSONObject()
        val b = o.optJSONObject("breakdown") ?: JSONObject()
        fun bucket(k: String) = b.optJSONObject(k)?.let { Bucket(it.int("count") ?: 0, it.dbl("value") ?: 0.0) } ?: Bucket(0, 0.0)
        return PortfolioView(
            holdings = o.optJSONArray("holdings").list(::holding),
            summary = PortfolioSummary(s.dbl("value") ?: 0.0, s.dbl("cost") ?: 0.0, s.dbl("costCoveredValue") ?: 0.0, s.int("items") ?: 0),
            cards = bucket("cards"),
            sealed = bucket("sealed"),
            graded = bucket("graded"),
        )
    }

    fun portfolioHistory(json: String): List<PricePoint> = points(JSONObject(json).optJSONArray("series"))

    fun watchlist(json: String): List<ProductItem> = JSONArray(json).list(::product)

    fun rates(json: String): Map<String, Double> {
        val o = JSONObject(json)
        return o.keys().asSequence().filter { !it.startsWith("_") }.mapNotNull { k -> o.dbl(k)?.let { k to it } }.toMap()
    }

    fun tracked(a: JSONArray?): List<TrackedCategory> = a.list { c ->
        TrackedCategory(c.lng("id") ?: return@list null, c.str("name") ?: "", c.str("lang"), c.str("code"))
    }

    fun status(json: String): ServerStatus {
        val o = JSONObject(json)
        return ServerStatus(o.str("lastDay"), tracked(o.optJSONArray("tracked")))
    }

    fun me(json: String): Me {
        val o = JSONObject(json)
        return Me(
            deviceId = o.lng("deviceId") ?: 0,
            deviceName = o.str("deviceName"),
            serverName = o.str("serverName"),
            serverVersion = o.str("serverVersion"),
            lastDay = o.str("lastDay"),
            tracked = tracked(o.optJSONArray("tracked")),
        )
    }

    fun pair(json: String): PairResult {
        val o = JSONObject(json)
        return PairResult(o.str("token") ?: error("no token in pairing reply"), o.lng("deviceId") ?: 0, o.str("serverName"))
    }

    fun resolve(json: String): List<ResolveMatch> = JSONObject(json).optJSONArray("matches").list { m ->
        ResolveMatch(product(m) ?: return@list null, m.optJSONArray("variants").list(::variant), m.dbl("score") ?: 0.0, m.str("how"))
    }

    fun importResult(json: String): ImportResult {
        val o = JSONObject(json)
        return ImportResult(
            imported = o.int("imported") ?: 0,
            unmatched = o.optJSONArray("unmatched").list { u -> Unmatched(u.str("ref") ?: "", u.str("reason") ?: "") },
            portfolioId = o.lng("portfolioId"),
            updated = o.int("updated") ?: 0,
            dryRun = o.bool("dryRun"),
            matched = o.optJSONArray("matched").list { m ->
                ImportMatch(m.str("ref") ?: "", m.lng("productId") ?: return@list null, m.dbl("score") ?: 0.0, m.str("how"))
            },
        )
    }

    fun createdId(json: String): Long? = JSONObject(json).lng("id")

    fun errorMessage(body: String): String? = runCatching { JSONObject(body).str("error") }.getOrNull()

    // ---- request bodies ----
    fun pairBody(code: String, deviceName: String): String = JSONObject().put("code", code).put("deviceName", deviceName).toString()

    fun resolveBody(r: ResolveRequest): String = resolveJson(r).toString()

    fun resolveJson(r: ResolveRequest): JSONObject = JSONObject()
        .put("lang", r.lang.orNull())
        .put("setCode", r.setCode.orNull())
        .put("setName", r.setName.orNull())
        .put("number", r.number)
        .put("name", r.name.orNull())
        .put("limit", r.limit)

    fun resolveRequest(o: JSONObject): ResolveRequest = ResolveRequest(
        lang = o.str("lang"),
        setCode = o.str("setCode"),
        setName = o.str("setName"),
        number = o.str("number") ?: "",
        name = o.str("name"),
        limit = o.int("limit") ?: 5,
    )

    fun importBody(portfolio: String, items: List<ImportItem>, dryRun: Boolean): String {
        val arr = JSONArray()
        items.forEach { i ->
            arr.put(
                JSONObject()
                    .put("ref", i.ref)
                    .put("lang", i.lang.orNull())
                    .put("setCode", i.setCode.orNull())
                    .put("setName", i.setName.orNull())
                    .put("number", i.number.orNull())
                    .put("name", i.name.orNull())
                    .put("variant", i.variant.orNull())
                    .put("qty", i.qty)
                    .put("condition", i.condition.orNull())
                    .put("grade", i.grade.orNull())
                    .put("purchasePrice", i.purchasePrice.orNull())
                    .put("addedOn", i.addedOn.orNull())
                    .put("notes", i.notes.orNull())
                    .put("sealed", i.sealed)
                    .put("productName", i.productName.orNull()),
            )
        }
        return JSONObject().put("portfolio", portfolio).put("items", arr).put("dryRun", dryRun).toString()
    }

    fun holdingBody(h: HoldingInput): String = JSONObject()
        .put("portfolio_id", h.portfolioId)
        .put("product_id", h.productId)
        .put("variant_id", h.variantId.orNull())
        .put("qty", h.qty)
        .put("condition", h.condition.orNull())
        .put("grade", h.grade.orNull())
        .put("purchase_price", h.purchasePrice.orNull())
        .put("custom_value", h.customValue.orNull())
        .put("added_on", h.addedOn.orNull())
        .put("notes", h.notes.orNull())
        .toString()

    fun nameBody(name: String): String = JSONObject().put("name", name).toString()
}
