package com.risenav.rokid.reader

import android.content.Context

/**
 * 阅读设置持久化。
 *
 * 可在网页管理界面中修改，App 启动和翻页时读取最新值。
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FONT_SIZE = "font_size_index"
        private const val KEY_SCROLL_RATIO = "scroll_ratio"
        private const val KEY_BRIGHTNESS = "brightness_index"

        /** Display brightness steps (window override, 0..1). The panel floor is about 0.035.
         *  Index -1 (the default) means "leave the glasses' own brightness alone". */
        val BRIGHTNESS_LEVELS = floatArrayOf(0.036f, 0.07f, 0.15f, 0.35f, 0.7f, 1.0f)
        val BRIGHTNESS_LABELS = arrayOf("Night", "Dim", "Low", "Medium", "High", "Max")

        /** 字号档位（与 ReaderView 的 fontSizes 对应） */
        val FONT_SIZES = floatArrayOf(10f, 12f, 18f, 22f, 26f, 31f)
        val FONT_LABELS = arrayOf("Micro", "Tiny", "X-Small", "Small", "Medium", "Large")

        /** 翻页幅度档位（0.5=半页, 0.75=四分之三页, 1.0=整页） */
        val SCROLL_RATIOS = floatArrayOf(0.5f, 0.75f, 1.0f)
    }

    var fontSizeIndex: Int
        get() = prefs.getInt(KEY_FONT_SIZE, 0).coerceIn(0, FONT_SIZES.size - 1)   // 默认小
        set(value) = prefs.edit()
            .putInt(KEY_FONT_SIZE, value.coerceIn(0, FONT_SIZES.size - 1))
            .apply()

    /** Index into BRIGHTNESS_LEVELS, or -1 to follow the system brightness. */
    var brightnessIndex: Int
        get() = prefs.getInt(KEY_BRIGHTNESS, -1).coerceIn(-1, BRIGHTNESS_LEVELS.size - 1)
        set(value) = prefs.edit()
            .putInt(KEY_BRIGHTNESS, value.coerceIn(-1, BRIGHTNESS_LEVELS.size - 1))
            .apply()

    /** 翻页幅度（0~1），默认 ¾ 页 */
    var scrollRatio: Float
        get() = prefs.getFloat(KEY_SCROLL_RATIO, 0.75f)
        set(value) = prefs.edit().putFloat(KEY_SCROLL_RATIO, value).apply()

    /** 翻页幅度档位索引（网页显示用） */
    var scrollRatioIndex: Int
        get() {
            val ratio = scrollRatio
            return SCROLL_RATIOS.indexOfFirst { Math.abs(it - ratio) < 0.01f }
                .takeIf { it >= 0 } ?: 1   // 默认 ¾ 页
        }
        set(value) {
            val idx = value.coerceIn(0, SCROLL_RATIOS.size - 1)
            scrollRatio = SCROLL_RATIOS[idx]
        }
}
