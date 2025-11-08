package local.test.camtest.protocol

import org.json.JSONObject

data class CTPMessage(
    val command: String,
    val suffix: Byte,
    val json: String,
    val op: String = "",
    val param: Map<String, Any> = emptyMap()
) {
    constructor(command: String, suffix: Byte, json: String) : this(
        command = command,
        suffix = suffix,
        json = json,
        op = parseOpFromJson(json),
        param = parseParamsFromJson(json)
    )

    companion object {
        private fun parseOpFromJson(json: String): String {
            return try {
                JSONObject(json).getString("op")
            } catch (e: Exception) {
                ""
            }
        }

        private fun parseParamsFromJson(json: String): Map<String, Any> {
            return try {
                val jsonObject = JSONObject(json)
                val paramObject = jsonObject.getJSONObject("param")
                val params = mutableMapOf<String, Any>()
                val keys = paramObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    params[key] = paramObject.get(key)
                }
                params
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }

    fun getParam(key: String): String? = param[key] as? String
    fun getParamInt(key: String): Int? =
        param[key] as? Int ?: (param[key] as? String)?.toIntOrNull()

    fun getParamBoolean(key: String): Boolean? = when (val value = param[key]) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }
}