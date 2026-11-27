package com.risenav.rokid.reader

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 主活动：书架 + 阅读器。
 *
 * 交互模型（适配眼镜滚轮 + 触摸板）:
 *   滚轮 / 方向键上下 —— 书架移动选中项；阅读时翻页（上滚=上一页，下滚=下一页）
 *   ENTER / 滚轮按压 —— 书架打开书籍；阅读时向下翻一页（与滚轮下滚一致）
 *   BACK / 滚轮按压 —— 阅读时回书架；书架页退出程序
 *   触摸板滚轮 —— 阅读时平滑滚动（映射为翻页）
 *
 * 阅读进度自动保存，重新打开同一本书时恢复。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private lateinit var readerView: ReaderView
    private lateinit var libraryStore: LibraryStore
    private lateinit var settingsStore: SettingsStore
    private var libraryView: LibraryView? = null
    private var currentBook: Book? = null
    private var transferServer: TransferServer? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        libraryStore = LibraryStore(this)
        settingsStore = SettingsStore(this)

        container = FrameLayout(this)
        readerView = ReaderView(this)
        readerView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        readerView.initSettings(settingsStore)
        applyBrightness()
        readerView.onProgressChanged = { ratio ->
            currentBook?.let { libraryStore.saveProgress(it.uri.toString(), ratio) }
        }
        container.addView(readerView)
        setContentView(container)

        // 启动显示书架
        showLibrary()
    }

    // ---------- 书架 ----------

    private fun showLibrary() {
        if (libraryView != null) return
        // 保存当前书进度后再回书架
        currentBook?.let { libraryStore.saveProgress(it.uri.toString(), it.progressRatio) }

        val view = LibraryView(
            this,
            libraryStore.loadBooks(),
            onOpenBook = { entry ->
                hideLibrary()
                openBookFromUri(Uri.parse(entry.uri), entry.title)
            },
            onToggleServer = { toggleServer() }
        )
        // 恢复服务器状态显示（书架重建时服务器可能还在运行）
        transferServer?.let {
            view.serverRunning = true
            view.serverAddress = it.localAddress()
        }
        libraryView = view
        container.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun hideLibrary() {
        libraryView?.let { container.removeView(it) }
        libraryView = null
    }

    private val isLibraryVisible: Boolean
        get() = libraryView != null

    // ---------- 书籍打开 ----------

    private fun openBookFromUri(uri: Uri, title: String) {
        val book = Book(uri, title, this)
        if (!book.load()) {
            Toast.makeText(this, "Cannot open file (missing or over 30MB)", Toast.LENGTH_SHORT).show()
            showLibrary()
            return
        }
        currentBook = book
        libraryStore.touchBook(uri.toString())
        // 恢复上次阅读进度
        book.seekToRatio(libraryStore.loadProgress(uri.toString()))
        readerView.book = book
    }

    // ---------- 局域网传书服务器 ----------

    private fun toggleServer() {
        if (transferServer != null) {
            stopServer()
        } else {
            startServer()
        }
    }

    private fun startServer() {
        try {
            val server = TransferServer(filesDir, libraryStore, settingsStore, 8000) {
                // 书库或设置变更（上传/删除/改设置）后回主线程刷新
                runOnUiThread {
                    readerView.applySettings()   // 网页可能改了字号/翻页幅度
                    refreshLibrary()
                }
            }
            server.start()
            transferServer = server
            libraryView?.apply {
                serverRunning = true
                serverAddress = server.localAddress()
                invalidate()
            }
            Toast.makeText(this, "Book transfer server started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopServer() {
        transferServer?.stop()
        transferServer = null
        libraryView?.apply {
            serverRunning = false
            serverAddress = ""
            invalidate()
        }
    }

    /** 重建书架列表（新书入库后刷新显示） */
    private fun refreshLibrary() {
        if (!isLibraryVisible) return
        hideLibrary()
        showLibrary()
    }

    // ---------- 输入处理 ----------

    private companion object { const val ADJUST_TIMEOUT_MS = 5000L }

    private fun logEvent(tag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("RokidInput", "[$tag] $msg")
        }
    }

    /** 触摸板滚轮：书架移动选中；阅读时翻页 */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            logEvent("SCROLL", "v=$v source=${event.source}")
            if (v != 0f) {
                val dir = if (v > 0) 1 else -1
                if (isLibraryVisible) {
                    if (dir > 0) libraryView?.moveDown() else libraryView?.moveUp()
                } else if (!adjust(dir)) {
                    readerView.scroll(dir)
                }
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /** 眼镜滚轮按键映射：NOTIFICATION 噪声丢弃，UP/DOWN 驱动，RIGHT/LEFT 噪声丢弃 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        logEvent("KEY", "action=${event.action} code=${event.keyCode} " +
                "name=${KeyEvent.keyCodeToString(event.keyCode)} repeat=${event.repeatCount}")
        return dispatchKeyEventInternal(event)
    }

    private fun dispatchKeyEventInternal(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_NOTIFICATION) return true
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                onBackKey()
            }
            return true
        }

        val isNavKey = keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_SPACE ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

        if (!isNavKey) return super.dispatchKeyEvent(event)

        if (isConfirmKey(keyCode) && !isLibraryVisible) return onReaderConfirmKey(event)

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount > 0) return true
            return onNavKeyDown(keyCode)
        }
        return true
    }

    private fun isConfirmKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_SPACE ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    /** True only when this press started in the reader (not the tap that opened the book). */
    private var confirmArmed = false

    /**
     * What a swipe does while reading. The glasses only give an app two gestures: tap and
     * swipe (long press = AI assistant, double tap = system Back). So a tap steps through
     * READ -> FONT -> BRIGHTNESS -> READ and, outside READ, swipes adjust the value instead
     * of turning pages. Adjust modes drop back to READ after ADJUST_TIMEOUT_MS idle or on Back.
     */
    private enum class Mode { READ, FONT, BRIGHTNESS }
    private var mode = Mode.READ
    private val modeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val exitAdjust = Runnable { setMode(Mode.READ) }

    private fun onReaderConfirmKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) confirmArmed = true
        } else if (event.action == KeyEvent.ACTION_UP && confirmArmed) {
            confirmArmed = false
            setMode(when (mode) {
                Mode.READ -> Mode.FONT
                Mode.FONT -> Mode.BRIGHTNESS
                Mode.BRIGHTNESS -> Mode.READ
            })
        }
        return true
    }

    private fun setMode(m: Mode) {
        mode = m
        modeHandler.removeCallbacks(exitAdjust)
        if (m != Mode.READ) modeHandler.postDelayed(exitAdjust, ADJUST_TIMEOUT_MS)
        refreshOverlay()
    }

    private fun refreshOverlay() {
        readerView.overlay = when (mode) {
            Mode.READ -> null
            Mode.FONT -> "Font: " + SettingsStore.FONT_LABELS[settingsStore.fontSizeIndex]
            Mode.BRIGHTNESS -> "Brightness: " + brightnessLabel()
        }
    }

    /** Swipe while reading. dir > 0 is the "next page" direction. Returns true if handled here. */
    private fun adjust(dir: Int): Boolean {
        when (mode) {
            Mode.READ -> return false
            Mode.FONT -> readerView.stepFontSize(dir)
            Mode.BRIGHTNESS -> stepBrightness(dir)
        }
        setMode(mode)   // restart the idle timer and redraw the label
        return true
    }

    // Brightness steps run Night .. Max, with "System" (follow the glasses' own setting,
    // stored as index -1) as one more step past Max.
    private fun brightnessLabel(): String {
        val i = settingsStore.brightnessIndex
        return if (i < 0) "System" else SettingsStore.BRIGHTNESS_LABELS[i]
    }

    private fun stepBrightness(dir: Int) {
        val n = SettingsStore.BRIGHTNESS_LEVELS.size
        val cur = settingsStore.brightnessIndex.let { if (it < 0) n else it }
        val next = (cur + dir).coerceIn(0, n)
        settingsStore.brightnessIndex = if (next == n) -1 else next
        applyBrightness()
    }

    /** Per-window override: applies while this app is in front, leaves the system setting alone. */
    private fun applyBrightness() {
        val i = settingsStore.brightnessIndex
        val lp = window.attributes
        lp.screenBrightness = if (i < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                              else SettingsStore.BRIGHTNESS_LEVELS[i]
        window.attributes = lp
    }

    private fun onNavKeyDown(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isLibraryVisible) libraryView?.moveUp() else if (!adjust(-1)) readerView.prevPage()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isLibraryVisible) libraryView?.moveDown() else if (!adjust(1)) readerView.nextPage()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> true   // 滚轮前导噪声，消费不响应
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                handleConfirmKey()
                true
            }
            else -> false
        }
    }

    /** ENTER 确认：书架打开书籍 / 切换传书；阅读时向下翻一页（与滚轮下滚一致） */
    private fun handleConfirmKey() {
        if (isLibraryVisible) {
            libraryView?.confirm()
        }
        // In the reader, confirm keys are handled by onReaderConfirmKey (tap = step through adjust modes).
    }

    /** BACK 键（滚轮按压映射）：阅读时回书架，书架页退出程序 */
    private fun onBackKey() {
        if (mode != Mode.READ) {   // Back (double tap) first leaves the adjust mode
            setMode(Mode.READ)
            return
        }
        if (isLibraryVisible) {
            finish()
        } else {
            showLibrary()
        }
    }

    /** 触摸：书架点选由 LibraryView 处理；阅读时触摸事件不响应——
     *  按压滚轮会同时发触摸 UP 和 ENTER，两者都翻页会翻两页，ENTER 已覆盖 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        logEvent("TOUCH", "action=${event.action} x=${event.x} y=${event.y}")
        return super.onTouchEvent(event)
    }

    override fun onDestroy() {
        stopServer()
        // 进度无需在此保存：onProgressChanged 翻页时已存，showLibrary 回书架时也存了
        super.onDestroy()
    }
}
