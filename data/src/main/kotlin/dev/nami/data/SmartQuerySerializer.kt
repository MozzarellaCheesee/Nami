package dev.nami.data

import dev.nami.domain.SmartField
import dev.nami.domain.SmartOperator
import dev.nami.domain.SmartQuery
import dev.nami.domain.SmartRule
import dev.nami.domain.SmartSortField
import org.json.JSONArray
import org.json.JSONObject

/** Same plain-org.json approach as AppSettingsRepository's Session serialization -- one small
 * JSON blob in a TEXT column, no separate rules table needed for something this size. */
object SmartQuerySerializer {
    fun serialize(query: SmartQuery): String {
        val rules = JSONArray()
        query.rules.forEach { rule ->
            rules.put(
                JSONObject().apply {
                    put("field", rule.field.name)
                    put("operator", rule.operator.name)
                    put("value", rule.value)
                },
            )
        }
        return JSONObject().apply {
            put("rules", rules)
            put("sortBy", query.sortBy.name)
            put("sortDescending", query.sortDescending)
            put("limit", query.limit ?: JSONObject.NULL)
        }.toString()
    }

    /** Null on any parse failure -- a corrupted/hand-edited value just means "no rules match
     * anything" at the call site, not a crash. */
    fun parse(json: String): SmartQuery? = runCatching {
        val root = JSONObject(json)
        val rulesArray = root.getJSONArray("rules")
        val rules = (0 until rulesArray.length()).map { i ->
            val obj = rulesArray.getJSONObject(i)
            SmartRule(
                field = SmartField.valueOf(obj.getString("field")),
                operator = SmartOperator.valueOf(obj.getString("operator")),
                value = obj.getString("value"),
            )
        }
        SmartQuery(
            rules = rules,
            sortBy = SmartSortField.valueOf(root.optString("sortBy", SmartSortField.DATE_ADDED.name)),
            sortDescending = root.optBoolean("sortDescending", true),
            limit = root.opt("limit")?.takeIf { it != JSONObject.NULL } as? Int,
        )
    }.getOrNull()
}
