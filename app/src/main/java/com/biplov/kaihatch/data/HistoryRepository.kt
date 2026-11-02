package com.biplov.kaihatch.data

import android.content.Context
import android.content.SharedPreferences
import com.biplov.kaihatch.model.OperationRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class HistoryRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("kaihatch_history", Context.MODE_PRIVATE)

    private val _records = MutableStateFlow<List<OperationRecord>>(emptyList())
    val records: StateFlow<List<OperationRecord>> = _records.asStateFlow()

    private val _totalReclaimedBytes = MutableStateFlow(0L)
    val totalReclaimedBytes: StateFlow<Long> = _totalReclaimedBytes.asStateFlow()

    init {
        loadHistory()
    }

    private fun loadHistory() {
        val jsonStr = prefs.getString("operations_json", null)
        val list = mutableListOf<OperationRecord>()
        var totalReclaimed = 0L

        if (jsonStr != null) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val rec = OperationRecord(
                        id = obj.getString("id"),
                        type = obj.getString("type"),
                        sourceName = obj.getString("sourceName"),
                        targetPath = obj.getString("targetPath"),
                        originalSize = obj.getLong("originalSize"),
                        reclaimedOrSavedSize = obj.getLong("reclaimedOrSavedSize"),
                        entryCount = obj.getInt("entryCount"),
                        timestamp = obj.getLong("timestamp"),
                        durationMs = obj.getLong("durationMs"),
                        success = obj.getBoolean("success")
                    )
                    list.add(rec)
                    if (rec.type == "HATCH_EXTRACT" && rec.success) {
                        totalReclaimed += rec.reclaimedOrSavedSize
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        _records.value = list.sortedByDescending { it.timestamp }
        _totalReclaimedBytes.value = totalReclaimed
    }

    fun addRecord(record: OperationRecord) {
        val current = _records.value.toMutableList()
        current.add(0, record)
        if (current.size > 100) {
            current.removeAt(current.size - 1)
        }
        _records.value = current

        if (record.type == "HATCH_EXTRACT" && record.success) {
            _totalReclaimedBytes.value += record.reclaimedOrSavedSize
        }

        saveToPrefs(current)
    }

    fun clearHistory() {
        _records.value = emptyList()
        _totalReclaimedBytes.value = 0L
        prefs.edit().remove("operations_json").apply()
    }

    private fun saveToPrefs(list: List<OperationRecord>) {
        try {
            val array = JSONArray()
            for (rec in list) {
                val obj = JSONObject().apply {
                    put("id", rec.id)
                    put("type", rec.type)
                    put("sourceName", rec.sourceName)
                    put("targetPath", rec.targetPath)
                    put("originalSize", rec.originalSize)
                    put("reclaimedOrSavedSize", rec.reclaimedOrSavedSize)
                    put("entryCount", rec.entryCount)
                    put("timestamp", rec.timestamp)
                    put("durationMs", rec.durationMs)
                    put("success", rec.success)
                }
                array.put(obj)
            }
            prefs.edit().putString("operations_json", array.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
