package com.risenav.rokid.reader

import android.content.Context
import android.graphics.Paint
import android.net.Uri
import java.io.InputStream
import java.nio.charset.Charset

/**
 * 书籍模型：加载 txt 文件、检测编码、按屏幕尺寸折行。
 *
 * 分页模型改为「行偏移」：全文折成固定行后，阅读位置 = 起始行索引，
 * 翻页 = 起始行前进/后退 N 行（N = 每页行数 × 翻页幅度）。
 * 这样半页/¾页翻页时有内容重叠，更接近真实阅读器手感。
 */
class Book(
    val uri: Uri,
    val title: String,
    private val context: Context
) {
    /** 全文按原始换行符切分的段落 */
    var paragraphs: List<String> = emptyList()
        private set

    /** 折行后的所有屏幕行 */
    var lines: List<String> = emptyList()
        private set

    /** 每页行数（由 paginate 根据屏幕高度计算） */
    var linesPerPage: Int = 1
        private set

    /** 当前阅读位置（起始行索引） */
    var position: Int = 0
        private set

    val lineCount: Int get() = lines.size

    /** 单文件大小上限：txt/epub 全量加载到内存，超限会 OOM。
     *  30MB 对纯文字书籍足够（红楼梦 txt ~2MB，起点长篇 epub ~10MB）。 */
    private val maxFileBytes = 30 * 1024 * 1024L

    /** 加载文件内容：按扩展名路由到 epub/md/txt 解析器 */
    fun load(): Boolean {
        // 先查文件大小，超限直接失败（上层 toast 提示），避免全量读入 OOM
        val size = queryFileSize()
        if (size < 0) return false   // 文件不存在或不可读
        if (size > maxFileBytes) return false

        val name = (uri.lastPathSegment ?: "").lowercase()
        return when {
            name.endsWith(".epub") -> loadEpub()
            name.endsWith(".md") || name.endsWith(".markdown") -> loadPlainText(stripMarkdown = true)
            else -> loadPlainText(stripMarkdown = false)   // txt 及其他按纯文本
        }
    }

    /** 查询文件大小（字节）；失败返回 -1 */
    private fun queryFileSize(): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    private fun loadEpub(): Boolean {
        val chapters = EpubParser.parse(context, uri) ?: return false
        // 章节合并为段落流，章节标题作为独立段落（用「」包裹便于识别）
        val all = mutableListOf<String>()
        for (ch in chapters) {
            all.add("「${ch.title}」")
            all.addAll(ch.paragraphs)
        }
        paragraphs = all
        return true
    }

    /** 加载纯文本（txt/md），检测编码（GBK/UTF-8） */
    private fun loadPlainText(stripMarkdown: Boolean): Boolean {
        return try {
            val input: InputStream = context.contentResolver.openInputStream(uri) ?: return false
            val bytes = input.readBytes()
            input.close()
            var text = decodeText(bytes)
            if (stripMarkdown) text = markdownToPlain(text)
            paragraphs = text.split(Regex("\\r?\\n"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Markdown → 纯文本：去标记符号保留文字 */
    private fun markdownToPlain(md: String): String {
        var t = md
        t = Regex("^#{1,6}\\s*", RegexOption.MULTILINE).replace(t, "")      // # 标题
        t = Regex("\\*\\*([^*]+)\\*\\*").replace(t, "$1")                    // **粗体**
        t = Regex("\\*([^*]+)\\*").replace(t, "$1")                          // *斜体*
        t = Regex("__([^_]+)__").replace(t, "$1")
        t = Regex("`([^`]+)`").replace(t, "$1")                              // `代码`
        t = Regex("!\\[([^]]*)]\\([^)]*\\)").replace(t, "")                  // 图片
        t = Regex("\\[([^]]+)]\\([^)]*\\)").replace(t, "$1")                 // 链接
        t = Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE).replace(t, "· ")  // 无序列表
        t = Regex("^\\s*>\\s?", RegexOption.MULTILINE).replace(t, "")        // 引用
        return t
    }

    private fun decodeText(bytes: ByteArray): String {
        return try {
            val decoder = Charset.forName("UTF-8").newDecoder()
            decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            String(bytes, Charset.forName("GBK"))
        }
    }

    /**
     * 按屏幕尺寸和字号折行。
     * @param paint 当前正文 Paint
     * @param lineHeight 行高（像素）
     * @param usableWidth 可用宽度（像素）
     * @param usableHeight 可用高度（像素）
     */
    fun paginate(paint: Paint, lineHeight: Int, usableWidth: Float, usableHeight: Int) {
        if (paragraphs.isEmpty()) {
            lines = emptyList()
            return
        }
        val oldRatio = progressRatio
        // Re-wrap anchor: the text offset of the current top line, so a font-size change
        // keeps the same words at the top instead of drifting via the rounded ratio.
        // It is fixed at the last page turn (not recomputed per re-wrap), so cycling through
        // all sizes returns to exactly the same page.
        val anchor = if (pendingRatio == null) anchorOffset else null

        val newLines = mutableListOf<String>()
        val newStarts = ArrayList<Int>()
        var offset = 0   // characters consumed so far across all paragraphs
        for (para in paragraphs) {
            var rest = para
            while (rest.isNotEmpty()) {
                newStarts.add(offset)
                var n = paint.breakText(rest, true, usableWidth, null)
                // Word wrap for space-separated scripts: if the break lands inside a word,
                // back up to the last space. CJK text (no spaces) keeps per-character breaks.
                if (n in 1 until rest.length && rest[n].code < 0x2E80 && !rest[n].isWhitespace() &&
                    rest[n - 1].code < 0x2E80 && !rest[n - 1].isWhitespace()) {
                    val sp = rest.lastIndexOf(' ', n - 1)
                    if (sp > 0) n = sp + 1
                }
                newLines.add(rest.substring(0, n).trimEnd())
                val next = rest.substring(n).trimStart(' ')
                offset += rest.length - next.length
                rest = next
            }
            newStarts.add(offset)
            newLines.add("")   // 段落间距
        }
        lines = newLines
        lineStarts = newStarts
        linesPerPage = maxOf(1, usableHeight / lineHeight)
        // 重新折行后恢复阅读位置：优先用外部暂存的进度（load 后首次 paginate），
        // 否则用折行前的旧位置比例（翻页/字号变化时的内部 repaginate）
        if (anchor != null) {
            // last line starting at or before the anchor (lineStarts is non-decreasing)
            var idx = newStarts.binarySearch(anchor).let { if (it >= 0) it else -it - 2 }
            // a paragraph-gap line shares its offset with the line after it; prefer the text line
            while (idx + 1 < newStarts.size && newStarts[idx + 1] == anchor) idx++
            position = idx.coerceIn(0, maxOf(0, lineCount - linesPerPage))
        } else {
            val restore = pendingRatio ?: oldRatio
            pendingRatio = null
            seekToRatio(restore)
        }
    }

    /**
     * 前进/后退指定行数。
     * @param delta 正数前进，负数后退
     *
     * 上限是 lineCount - linesPerPage（而不是 lineCount - 1）：
     * 保证最后一页总是满页，不会滚到只剩 1 行的"死页"。
     */
    fun scrollBy(delta: Int): Boolean {
        val old = position
        position = (position + delta).coerceIn(0, maxOf(0, lineCount - linesPerPage))
        if (position != old) anchorOffset = lineStarts.getOrNull(position)
        return position != old
    }

    /** 当前页的可见行（从 position 开始的 linesPerPage 行） */
    fun visibleLines(): List<String> {
        if (lines.isEmpty()) return emptyList()
        val end = minOf(position + linesPerPage, lines.size)
        return lines.subList(position.coerceIn(0, lines.size - 1), end)
    }

    /** Text offset at which each wrapped line starts (parallel to [lines]). */
    private var lineStarts: List<Int> = emptyList()

    /** Text offset of the top line as of the last page turn / seek; null before first layout. */
    private var anchorOffset: Int? = null

    /** 待恢复的进度（折行前暂存，paginate 完成后恢复） */
    private var pendingRatio: Float? = null

    /** 当前阅读进度（0~1），用于持久化恢复 */
    val progressRatio: Float
        get() = if (lineCount == 0) pendingRatio ?: 0f else position.toFloat() / maxOf(1, lineCount - linesPerPage)

    fun seekToRatio(ratio: Float) {
        if (lineCount == 0) {
            // 还没折行（load 后、paginate 前），暂存待恢复
            pendingRatio = ratio
            return
        }
        val maxPos = maxOf(0, lineCount - linesPerPage)
        position = (ratio * maxPos).toInt().coerceIn(0, maxPos)
        anchorOffset = lineStarts.getOrNull(position)
        pendingRatio = null
    }
}
