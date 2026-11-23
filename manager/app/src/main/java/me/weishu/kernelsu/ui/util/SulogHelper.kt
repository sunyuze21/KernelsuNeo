package me.weishu.kernelsu.ui.util

import android.os.SystemClock
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale

// ksud 的日志目录，只有 root 可读，因此所有访问都走 SuFile。
private const val SULOG_DIR = "/data/adb/ksu/log"
// 单次最多读取的行数，防止超大日志拖垮 UI。
private const val SULOG_LINE_LIMIT = 1000
// 日志文件名前缀。
private const val SULOG_FILE_PREFIX = "sulog-"
// 日志文件名后缀。
private const val SULOG_FILE_SUFFIX = ".log"
// 文件名格式：sulog-YYYY-MM-DD[-rotation].log。
private val SULOG_FILE_NAME_REGEX = Regex("""$SULOG_FILE_PREFIX(\d{4}-\d{2}-\d{2})(?:-(\d+))?$SULOG_FILE_SUFFIX""")
// 内核时间戳单位是纳秒，转换为毫秒用于展示。
private const val NS_PER_MILLISECOND = 1_000_000L
// 日志时间统一按 UTC+本地时区格式化，列对齐更稳定。
private val SULOG_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

/** 一个 sulog 日志文件的展示信息。 */
data class SulogFile(
    val name: String,
    val path: String,
)

/** 清理动作：最新文件只能清空（继续写入），历史文件可以删除。 */
enum class SulogFileCleanAction {
    Clear,
    Delete,
}

/** sulog 记录的事件类型，对应日志中的 `type` 字段。 */
enum class SulogEventType {
    RootExecve,
    SuCompat,
    IoctlGrantRoot,
    DaemonEvent,
    Dropped,
    Unknown,
}

/** UI 过滤器；[eventType] 为 null 表示不绑定具体类型（当前未使用）。 */
enum class SulogEventFilter(val eventType: SulogEventType?) {
    RootExecve(SulogEventType.RootExecve),
    SuCompat(SulogEventType.SuCompat),
    IoctlGrantRoot(SulogEventType.IoctlGrantRoot),
    DaemonEvent(SulogEventType.DaemonEvent),
}

/** 默认开启全部事件类型过滤。 */
fun defaultSulogEventFilters(): Set<SulogEventFilter> = SulogEventFilter.entries.toSet()

/**
 * 解析后的一条日志。
 *
 * [key] 用于列表稳定标识；[fields] 保存 `k=v` 键值对；[searchableText]
 * 是懒计算的小写全文，供搜索过滤使用。
 */
data class SulogEntry(
    val key: String,
    val eventType: SulogEventType,
    val rawLine: String,
    val timestampText: String?,
    val fields: Map<String, String>,
) {
    // 懒计算：只有实际搜索时才拼接，避免滚动时反复分配字符串。
    val searchableText: String by lazy {
        buildString {
            append(rawLine)
            append('\n')
            timestampText?.let {
                append(it)
                append('\n')
            }
            fields.values.forEach {
                append(it)
                append(' ')
            }
        }.lowercase()
    }
}

/**
 * 过滤并排序日志文件名。
 *
 * 只保留符合 sulog-YYYY-MM-DD[-N].log 的文件，按日期、轮转号降序排列，
 * 使最新文件排在最前。
 */
internal fun parseSulogFileNames(fileNames: List<String>): List<String> {
    return fileNames
        .mapNotNull { name ->
            val match = SULOG_FILE_NAME_REGEX.matchEntire(name) ?: return@mapNotNull null
            val date = LocalDate.parse(match.groupValues[1])
            val rotation = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            Triple(name, date, rotation)
        }
        .sortedWith(compareByDescending<Triple<String, LocalDate, Int>> { it.second }.thenByDescending { it.third })
        .map { it.first }
}

/**
 * 决定“清理”按钮的行为。
 *
 * 最新日志文件可能正被 ksud 写入，只能清空内容；历史文件可以整个删除。
 */
internal fun resolveSulogFileCleanAction(
    files: List<SulogFile>,
    selectedFilePath: String,
): SulogFileCleanAction {
    // files 已按最新优先排序，首个即当前活跃文件。
    val latestFilePath = files.firstOrNull()?.path
    return if (selectedFilePath == latestFilePath) {
        SulogFileCleanAction.Clear
    } else {
        SulogFileCleanAction.Delete
    }
}

/** 列出 /data/adb/ksu/log 下的日志文件；目录不存在时返回空列表。 */
fun listSulogFiles(): List<SulogFile> {
    val directory = SuFile(SULOG_DIR)
    val fileNames = directory.list().orEmpty().toList()
    return parseSulogFileNames(fileNames).map { name ->
        SulogFile(name = name, path = "$SULOG_DIR/$name")
    }
}

/**
 * 通过 root 读取日志文件。
 *
 * 用固定上限的环形队列只保留最后 [SULOG_LINE_LIMIT] 行，避免大文件占满内存；
 * 文件不存在或读取失败时返回空列表。
 */
fun readSulogFile(path: String): List<String> {
    val suFile = SuFile(path)
    if (!suFile.isFile) {
        return emptyList()
    }

    // 超出上限时从头部丢弃最旧的行。
    val lines = ArrayDeque<String>(SULOG_LINE_LIMIT)
    SuFileInputStream.open(suFile).use { input ->
        InputStreamReader(input).buffered().useLines { sequence ->
            sequence.forEach { line ->
                if (lines.size == SULOG_LINE_LIMIT) {
                    lines.removeFirst()
                }
                lines.addLast(line)
            }
        }
    }
    return lines.toList()
}

/** 批量解析日志行，并复用同一组时间基准以减小逐行换算误差。 */
fun parseSulogLines(lines: List<String>): List<SulogEntry> {
    // 日志时间戳是开机后的纳秒数，需要结合当前墙钟与 uptime 还原。
    val currentTimeMillis = System.currentTimeMillis()
    val uptimeMillis = SystemClock.uptimeMillis()
    val zoneId = ZoneId.systemDefault()
    return lines.mapNotNull { line ->
        line.takeIf { it.isNotBlank() }?.let {
            parseSulogLine(
                line = it,
                currentTimeMillis = currentTimeMillis,
                uptimeMillis = uptimeMillis,
                zoneId = zoneId,
            )
        }
    }
}

/**
 * 按搜索词和事件类型过滤日志，并倒序返回（最新在前）。
 *
 * Dropped/Unknown 属于诊断信息，始终显示，避免过滤后看不到丢包提示。
 */
fun buildVisibleSulogEntries(
    entries: List<SulogEntry>,
    searchText: String,
    selectedFilters: Set<SulogEventFilter>,
): List<SulogEntry> {
    // 统一小写后做子串匹配，兼容中英文与大小写。
    val normalizedQuery = searchText.trim().lowercase()
    val activeEventTypes = selectedFilters.mapNotNull { it.eventType }.toSet()
    return entries.asReversed().filter { entry ->
        val alwaysVisible = entry.eventType == SulogEventType.Dropped || entry.eventType == SulogEventType.Unknown
        val filterMatches = alwaysVisible || entry.eventType in activeEventTypes
        val queryMatches = normalizedQuery.isEmpty() || entry.searchableText.contains(normalizedQuery)
        filterMatches && queryMatches
    }
}

/**
 * 解析单行日志为 [SulogEntry]。
 *
 * key 优先用内核递增的 `seq`；守护进程重启事件没有 seq，则用 boot_id 组合，
 * 保证列表项唯一且稳定。
 */
fun parseSulogLine(
    line: String,
    currentTimeMillis: Long = System.currentTimeMillis(),
    uptimeMillis: Long = -1L,
    zoneId: ZoneId = ZoneId.systemDefault(),
): SulogEntry {
    val fields = parseKeyValueLine(line)
    val eventType = when (fields["type"]) {
        "root_execve" -> SulogEventType.RootExecve
        "sucompat" -> SulogEventType.SuCompat
        "ioctl_grant_root" -> SulogEventType.IoctlGrantRoot
        "daemon_restart", "daemon_start" -> SulogEventType.DaemonEvent
        "dropped" -> SulogEventType.Dropped
        else -> SulogEventType.Unknown
    }
    val key = when (eventType) {
        SulogEventType.DaemonEvent -> "daemon_restart_${fields["restart"]}_${fields["boot_id"]}"
        else -> fields["seq"] ?: line
    }
    val timestampText = parseSulogTimestampText(
        timestampNs = fields["ts_ns"],
        currentTimeMillis = currentTimeMillis,
        uptimeMillis = uptimeMillis,
        zoneId = zoneId,
    )
    return SulogEntry(
        key = key,
        eventType = eventType,
        rawLine = line,
        timestampText = timestampText,
        fields = fields,
    )
}

// 解析 `key=value key="quoted value"` 形式的日志行，忽略无法识别的片段。
private fun parseKeyValueLine(line: String): Map<String, String> {
    return buildMap {
        var index = 0
        while (index < line.length) {
            while (index < line.length && line[index].isWhitespace()) {
                index++
            }
            if (index >= line.length) break

            val keyStart = index
            while (index < line.length && line[index] != '=' && !line[index].isWhitespace()) {
                index++
            }
            if (index >= line.length || line[index] != '=') {
                while (index < line.length && !line[index].isWhitespace()) {
                    index++
                }
                continue
            }

            val key = line.substring(keyStart, index)
            index++
            val (value, nextIndex) = if (index < line.length && line[index] == '"') {
                parseQuotedSulogValue(line, index + 1)
            } else {
                parseUnquotedSulogValue(line, index)
            }
            if (key.isNotEmpty()) {
                put(key, value)
            }
            index = nextIndex
        }
    }
}

// 解析双引号包裹的值，支持反斜杠转义；返回值和下一个读取位置。
private fun parseQuotedSulogValue(line: String, startIndex: Int): Pair<String, Int> {
    val value = StringBuilder()
    var index = startIndex
    while (index < line.length) {
        when (val ch = line[index]) {
            '"' -> return value.toString() to (index + 1)
            '\\' -> index = appendEscapedSulogChar(line, index, value)
            else -> {
                value.append(ch)
                index++
            }
        }
    }
    return value.toString() to index
}

// 解析不带引号的值，遇到空白即结束。
private fun parseUnquotedSulogValue(line: String, startIndex: Int): Pair<String, Int> {
    var index = startIndex
    while (index < line.length && !line[index].isWhitespace()) {
        index++
    }
    return line.substring(startIndex, index) to index
}

// 处理反斜杠转义字符，返回继续解析的位置。
private fun appendEscapedSulogChar(line: String, slashIndex: Int, value: StringBuilder): Int {
    val nextIndex = slashIndex + 1
    if (nextIndex >= line.length) {
        value.append('\\')
        return nextIndex
    }

    return when (val escaped = line[nextIndex]) {
        '\\' -> {
            value.append('\\')
            nextIndex + 1
        }
        '"' -> {
            value.append('"')
            nextIndex + 1
        }
        'n' -> {
            value.append('\n')
            nextIndex + 1
        }
        'r' -> {
            value.append('\r')
            nextIndex + 1
        }
        't' -> {
            value.append('\t')
            nextIndex + 1
        }
        'x' -> appendHexEscapedSulogChar(line, nextIndex, value)
        else -> {
            value.append(escaped)
            nextIndex + 1
        }
    }
}

// 解析 `\xHH` 形式的十六进制转义；格式不合法时保留字面量。
private fun appendHexEscapedSulogChar(line: String, xIndex: Int, value: StringBuilder): Int {
    val hexStart = xIndex + 1
    val hexEnd = hexStart + 2
    if (hexEnd <= line.length) {
        val code = line.substring(hexStart, hexEnd).toIntOrNull(16)
        if (code != null) {
            value.append(code.toChar())
            return hexEnd
        }
    }

    value.append('x')
    return xIndex + 1
}

/**
 * 把开机后的纳秒时间戳转换为本地时间文本。
 *
 * 需要当前墙钟与 uptime 推算开机时刻；任一时间基准无效时返回 null，
 * 宁可不显示也不要显示错误时间。
 */
internal fun parseSulogTimestampText(
    timestampNs: String?,
    currentTimeMillis: Long = System.currentTimeMillis(),
    uptimeMillis: Long = -1L,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String? {
    val timestampNanos = timestampNs?.toLongOrNull() ?: return null
    if (timestampNanos < 0 || uptimeMillis < 0) return null

    // 用“当前时间 - 开机时长”估算开机时刻。
    val bootTimeMillis = currentTimeMillis - uptimeMillis
    if (bootTimeMillis < 0) return null

    val eventTimeMillis = bootTimeMillis + (timestampNanos / NS_PER_MILLISECOND)
    if (eventTimeMillis < 0) return null

    return Instant.ofEpochMilli(eventTimeMillis)
        .atZone(zoneId)
        .format(SULOG_TIMESTAMP_FORMATTER)
}

/** 去掉日志文件名前缀/后缀，只保留日期部分用于展示。 */
internal fun String.toSulogDisplayName(): String {
    return if (startsWith(SULOG_FILE_PREFIX) && endsWith(SULOG_FILE_SUFFIX)) {
        removePrefix(SULOG_FILE_PREFIX).removeSuffix(SULOG_FILE_SUFFIX)
    } else {
        this
    }
}

// 清空日志内容但保留文件，供正在写入的活跃日志使用。
fun cleanSulogFile(path: String): Boolean {
    val suFile = SuFile(path)
    return suFile.clear()
}

// 删除历史日志文件；需要 root 权限，通过 SuFile 执行。
fun deleteSulogFile(path: String): Boolean {
    val suFile = SuFile(path)
    return suFile.delete()
}
