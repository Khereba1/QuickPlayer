package com.myplayer.app

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * مؤثرات الصوت (إكولايزر + باس + رفع الصوت).
 * بتتربط بجلسة الصوت بتاعة المشغل في الخدمة، والواجهة بتتحكم فيها مباشرة لأن الاتنين في نفس العملية.
 */
object AudioFx {
    var available by mutableStateOf(false)
        private set
    var enabled by mutableStateOf(false)
        private set
    var bandLevels by mutableStateOf<List<Int>>(emptyList())
        private set
    var bandFreqs by mutableStateOf<List<Int>>(emptyList())
        private set
    var minLevel by mutableIntStateOf(-1500)
        private set
    var maxLevel by mutableIntStateOf(1500)
        private set
    var presets by mutableStateOf<List<String>>(emptyList())
        private set
    var preset by mutableIntStateOf(-1)
        private set
    var bass by mutableIntStateOf(0)
        private set
    var bassSupported by mutableStateOf(false)
        private set
    var boost by mutableIntStateOf(0)
        private set

    private var prefs: SharedPreferences? = null
    private var eq: Equalizer? = null
    private var bassFx: BassBoost? = null
    private var loud: LoudnessEnhancer? = null
    private var session = 0

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        enabled = p.getBoolean("fx_enabled", false)
        bass = p.getInt("fx_bass", 0)
        boost = p.getInt("fx_boost", 0)
    }

    private fun savedBands(): List<Int> {
        val raw = prefs?.getString("fx_bands", "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return try {
            raw.split(",").map { it.trim().toInt() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persist() {
        val p = prefs ?: return
        p.edit()
            .putBoolean("fx_enabled", enabled)
            .putInt("fx_bass", bass)
            .putInt("fx_boost", boost)
            .putString("fx_bands", bandLevels.joinToString(","))
            .apply()
    }

    fun attach(sessionId: Int) {
        if (sessionId <= 0) return
        if (sessionId == session && eq != null) return
        release()
        session = sessionId

        try {
            val e = Equalizer(0, sessionId)
            val bands = e.numberOfBands.toInt()
            val range = e.bandLevelRange
            minLevel = range[0].toInt()
            maxLevel = range[1].toInt()

            val freqs = ArrayList<Int>()
            for (i in 0 until bands) freqs.add(e.getCenterFreq(i.toShort()) / 1000)
            bandFreqs = freqs

            val names = ArrayList<String>()
            for (i in 0 until e.numberOfPresets.toInt()) names.add(e.getPresetName(i.toShort()))
            presets = names

            val saved = savedBands()
            val levels = ArrayList<Int>()
            for (i in 0 until bands) {
                val level = if (saved.size == bands) saved[i] else e.getBandLevel(i.toShort()).toInt()
                val clamped = level.coerceIn(minLevel, maxLevel)
                e.setBandLevel(i.toShort(), clamped.toShort())
                levels.add(clamped)
            }
            bandLevels = levels
            e.setEnabled(enabled)
            eq = e
            available = true
        } catch (t: Throwable) {
            AppLog.log("FX", "Equalizer unavailable", t)
            available = false
        }

        try {
            val b = BassBoost(0, sessionId)
            bassSupported = b.strengthSupported
            if (bassSupported) b.setStrength(bass.toShort())
            b.setEnabled(enabled && bass > 0)
            bassFx = b
        } catch (t: Throwable) {
            AppLog.log("FX", "BassBoost unavailable", t)
            bassSupported = false
        }

        try {
            val l = LoudnessEnhancer(sessionId)
            l.setTargetGain(boost)
            l.setEnabled(enabled && boost > 0)
            loud = l
        } catch (t: Throwable) {
            AppLog.log("FX", "LoudnessEnhancer unavailable", t)
        }
    }

    private fun applyEnabled() {
        try {
            eq?.setEnabled(enabled)
            bassFx?.setEnabled(enabled && bass > 0)
            loud?.setEnabled(enabled && boost > 0)
        } catch (t: Throwable) {
            AppLog.log("FX", "applyEnabled failed", t)
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        applyEnabled()
        persist()
    }

    fun setBand(index: Int, level: Int) {
        val levels = bandLevels.toMutableList()
        if (index < 0 || index >= levels.size) return
        val clamped = level.coerceIn(minLevel, maxLevel)
        levels[index] = clamped
        bandLevels = levels
        preset = -1
        try {
            eq?.setBandLevel(index.toShort(), clamped.toShort())
        } catch (t: Throwable) {
            AppLog.log("FX", "setBand failed", t)
        }
        if (!enabled) setEnabled(true) else persist()
    }

    fun usePreset(index: Int) {
        val e = eq ?: return
        try {
            e.usePreset(index.toShort())
            val levels = ArrayList<Int>()
            for (i in 0 until e.numberOfBands.toInt()) levels.add(e.getBandLevel(i.toShort()).toInt())
            bandLevels = levels
            preset = index
        } catch (t: Throwable) {
            AppLog.log("FX", "usePreset failed", t)
        }
        if (!enabled) setEnabled(true) else persist()
    }

    fun setBass(value: Int) {
        bass = value.coerceIn(0, 1000)
        try {
            if (bassSupported) bassFx?.setStrength(bass.toShort())
            bassFx?.setEnabled(enabled && bass > 0)
        } catch (t: Throwable) {
            AppLog.log("FX", "setBass failed", t)
        }
        if (!enabled && bass > 0) setEnabled(true) else persist()
    }

    fun setBoost(valueMb: Int) {
        boost = valueMb.coerceIn(0, 1500)
        try {
            loud?.setTargetGain(boost)
            loud?.setEnabled(enabled && boost > 0)
        } catch (t: Throwable) {
            AppLog.log("FX", "setBoost failed", t)
        }
        if (!enabled && boost > 0) setEnabled(true) else persist()
    }

    fun reset() {
        val zeros = ArrayList<Int>()
        for (i in bandLevels.indices) zeros.add(0)
        bandLevels = zeros
        preset = -1
        try {
            for (i in zeros.indices) eq?.setBandLevel(i.toShort(), 0)
        } catch (t: Throwable) {
            AppLog.log("FX", "reset failed", t)
        }
        setBass(0)
        setBoost(0)
        persist()
    }

    fun release() {
        try {
            eq?.release()
        } catch (t: Throwable) {
            // ignore
        }
        try {
            bassFx?.release()
        } catch (t: Throwable) {
            // ignore
        }
        try {
            loud?.release()
        } catch (t: Throwable) {
            // ignore
        }
        eq = null
        bassFx = null
        loud = null
        session = 0
        available = false
    }
}
