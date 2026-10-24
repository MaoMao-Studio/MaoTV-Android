@file:androidx.annotation.OptIn(UnstableApi::class)

package com.cncat.maotv.player

import android.net.Uri
import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.DvbSubtitleReader
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.BundledHlsMediaChunkExtractor
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.text.SubtitleParser

/**
 * Media3 自带的 TS 解析器不认蓝光 PGS 字幕（PMT 里的流类型 0x90），所以 TS 直播流里的 PGS 轨道根本不会出现。
 * 这里补一个读取器：把 PES 里的 PGS 分段攒成完整的“显示集”（以 END 分段 0x80 结尾），
 * 作为 application/pgs 的字幕样本交给 Media3（它自带 PGS 解码，位图字幕由 SubtitleView 显示）。
 */
/** 诊断用计数：PGS 读取器一共收到多少字节、凑出多少个完整显示集 */
object PgsStats {
    @Volatile var tracks = 0
    @Volatile var bytes = 0L
    @Volatile var sets = 0
    @Volatile var lastSetUs = -1L
    @Volatile var dropped = 0
    @Volatile var path = "?"
    @Volatile var dvbPackets = 0
    @Volatile var dvbUnaligned = 0
    @Volatile var textCount = 0
    @Volatile var textFirstUs = -1L
    @Volatile var textLastUs = -1L
    @Volatile var videoLastUs = -1L
    @Volatile var audioLastUs = -1L
    private val seen = LinkedHashSet<String>()
    /** PMT 里出现过的流类型（0x1b=H264 0x24=H265 0x0f=AAC 0x06=私有数据 0x90=PGS ...） */
    fun note(s: String) { synchronized(seen) { seen.add(s) } }
    fun streams(): String = synchronized(seen) { seen.joinToString(" ") }
    fun reset() { tracks = 0; bytes = 0; sets = 0; lastSetUs = -1; dropped = 0; path = "?"; dvbPackets = 0; dvbUnaligned = 0; textCount = 0; textFirstUs = -1; textLastUs = -1; videoLastUs = -1; audioLastUs = -1; synchronized(seen) { seen.clear() } }
}

private class PgsTsReader(private val language: String?) : ElementaryStreamReader {
    private var output: TrackOutput? = null
    private var buf = ByteArray(64 * 1024)
    private var size = 0
    private var sampleTimeUs = C.TIME_UNSET
    private var pesTimeUs = C.TIME_UNSET

    override fun seek() {
        size = 0
        sampleTimeUs = C.TIME_UNSET
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        idGenerator.generateNewId()
        PgsStats.tracks++
        val out = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_TEXT)
        out.format(
            Format.Builder()
                .setId(idGenerator.formatId)
                .setSampleMimeType(MimeTypes.APPLICATION_PGS)
                .setLanguage(language)
                .build()
        )
        output = out
    }

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        this.pesTimeUs = pesTimeUs
        if (size == 0) sampleTimeUs = pesTimeUs
    }

    override fun consume(data: ParsableByteArray) {
        val n = data.bytesLeft()
        if (n <= 0) return
        PgsStats.bytes += n
        if (size + n > MAX_BYTES) { PgsStats.dropped++; size = 0; sampleTimeUs = pesTimeUs; return }   // 异常数据：丢弃重来
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
        data.readBytes(buf, size, n)
        size += n
    }

    override fun packetFinished(isEndOfInput: Boolean) {
        val out = output ?: return
        if (size == 0 || sampleTimeUs == C.TIME_UNSET) return
        // 逐段扫描：每段 = 类型(1) + 长度(2) + 内容；最后一段是 END(0x80) 才算一个完整的显示集
        var pos = 0
        var lastType = -1
        while (pos + 3 <= size) {
            lastType = buf[pos].toInt() and 0xFF
            val len = ((buf[pos + 1].toInt() and 0xFF) shl 8) or (buf[pos + 2].toInt() and 0xFF)
            pos += 3 + len
        }
        if (pos == size && lastType == END_SEGMENT) {
            val sample = ParsableByteArray(buf, size)
            out.sampleData(sample, size)
            out.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
            PgsStats.sets++
            PgsStats.lastSetUs = sampleTimeUs
            size = 0
            sampleTimeUs = C.TIME_UNSET
        }
    }

    private companion object {
        const val END_SEGMENT = 0x80
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}

/**
 * Media3 的 DVB 字幕读取器只处理 PES 头里带“数据对齐标志”的包，很多 IPTV 源不设这个标志，结果一个字幕样本都不出。
 * 这里在包装里强制带上该标志（VLC 不在乎这个标志，所以 VLC 能显示）。
 */
private class ForceAlignedReader(private val d: ElementaryStreamReader) : ElementaryStreamReader by d {
    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        PgsStats.dvbPackets++
        if ((flags and TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR) == 0) PgsStats.dvbUnaligned++
        d.packetStarted(pesTimeUs, flags or TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR)
    }
}

/** 在默认的 TS 读取器基础上加上 PGS（流类型 0x90） */
private class PgsAwareReaderFactory(flags: Int, captions: List<Format> = emptyList()) : TsPayloadReader.Factory {
    private val base = DefaultTsPayloadReaderFactory(flags, captions)

    override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> = base.createInitialPayloadReaders()

    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? {
        PgsStats.note("0x%02x/%s".format(streamType, esInfo.language ?: "-"))
        return createReader(streamType, esInfo)
    }

    private fun createReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? =
        if (streamType == STREAM_TYPE_PGS) PesReader(PgsTsReader(esInfo.language))
        else if (streamType == STREAM_TYPE_DVBSUBS) PesReader(ForceAlignedReader(DvbSubtitleReader(esInfo.dvbSubtitleInfos)))
        else base.createPayloadReader(streamType, esInfo)

    private companion object {
        const val STREAM_TYPE_PGS = 0x90
        const val STREAM_TYPE_DVBSUBS = 0x59
    }
}

/** 其余格式照旧，只把 TS 解析器换成认识 PGS 的那个 */
class MaoExtractorsFactory(private val tsFlags: Int) : ExtractorsFactory {
    private val base = DefaultExtractorsFactory().setTsExtractorFlags(tsFlags)

    override fun createExtractors(): Array<Extractor> = createExtractors(Uri.EMPTY, HashMap())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        base.createExtractors(uri, responseHeaders).map { e ->
            if (e is TsExtractor) {
                TsExtractor(
                    TsExtractor.MODE_SINGLE_PMT,
                    0,
                    DefaultSubtitleParserFactory(),
                    TimestampAdjuster(0),
                    PgsAwareReaderFactory(tsFlags),
                    TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
                )
            } else e
        }.toTypedArray()
}


private class NoLengthInput(private val d: ExtractorInput) : ExtractorInput by d {
    override fun getLength(): Long = C.LENGTH_UNSET.toLong()
}

/**
 * 让 HLS 的 TS 分片里“多条音轨 / 字幕轨”都能被识别：
 * Media3 的 TsExtractor 在 HLS 模式下把“流类型”当轨道编号，同一类型（音频 / 字幕）只保留 PID 最小的一条，
 * 所以这里改用 SINGLE_PMT 模式（轨道编号 = PID，全部保留）+ 长度未知的输入（行为和连续 TS 直播流一致）。
 * 另外 HlsSampleStreamWrapper 对同一类型（音频）也只映射第一条，其余会被丢弃；
 * 这里把第 2 条起的音频以 TRACK_TYPE_UNKNOWN 上报，播放器就会为每条单独建一个采样队列，并按 MIME 当作音轨暴露。
 */
/** 诊断用：统计各类样本的最新时间戳，用来比对字幕样本和视频样本的时间是否对得上 */
private class CountingTrackOutput(private val d: TrackOutput, private val type: Int) : TrackOutput by d {
    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        when (type) {
            C.TRACK_TYPE_TEXT -> {
                PgsStats.textCount++
                if (PgsStats.textFirstUs < 0) PgsStats.textFirstUs = timeUs
                PgsStats.textLastUs = timeUs
            }
            C.TRACK_TYPE_VIDEO -> PgsStats.videoLastUs = timeUs
            C.TRACK_TYPE_AUDIO -> PgsStats.audioLastUs = timeUs
        }
        d.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }
}

class MultiAudioTsExtractor(
    private val ts: TsExtractor,
    private val mainAudioId: java.util.concurrent.atomic.AtomicInteger,
) : Extractor {
    override fun sniff(input: ExtractorInput): Boolean = ts.sniff(input)

    override fun init(output: ExtractorOutput) {
        ts.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                var t = type
                if (type == C.TRACK_TYPE_AUDIO) {
                    mainAudioId.compareAndSet(-1, id)
                    if (id != mainAudioId.get()) t = C.TRACK_TYPE_UNKNOWN
                }
                val o = output.track(id, t)
                return if (type == C.TRACK_TYPE_TEXT || type == C.TRACK_TYPE_VIDEO || t == C.TRACK_TYPE_AUDIO) CountingTrackOutput(o, type) else o
            }
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: androidx.media3.extractor.SeekMap) = output.seekMap(seekMap)
        })
    }

    // 把输入伪装成“长度未知”的连续流：这样 TsExtractor 不会去跳到文件尾读时长 / 不会读完 PMT 后回到开头重读
    override fun read(input: ExtractorInput, seekPosition: androidx.media3.extractor.PositionHolder): Int =
        ts.read(NoLengthInput(input), seekPosition)
    override fun seek(position: Long, timeUs: Long) = ts.seek(position, timeUs)
    override fun release() = ts.release()
    override fun getUnderlyingImplementation(): Extractor = ts
}

/**
 * HLS 用的提取器工厂：分片是 TS 时换成认识 PGS 的 TS 解析器（其余情况交给默认实现）。
 * 配合关闭“无分片准备”，播放器才会从第一个分片里发现内嵌的字幕轨道。
 */
class MaoHlsExtractorFactory(private val tsFlags: Int) : HlsExtractorFactory {
    private val base = DefaultHlsExtractorFactory(tsFlags, false)
    private val mainAudioId = java.util.concurrent.atomic.AtomicInteger(-1)

    // 字幕相关设置交给默认实现：WebVTT 等必须在提取阶段解析成 cues，否则渲染器会报错 → “网络错误，正在重试”
    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): HlsExtractorFactory {
        base.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction: Boolean): HlsExtractorFactory {
        base.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction)
        return this
    }

    override fun getOutputTextFormat(sourceFormat: Format): Format = base.getOutputTextFormat(sourceFormat)

    override fun createExtractor(
        uri: Uri,
        format: Format,
        muxedCaptionFormats: List<Format>?,
        timestampAdjuster: TimestampAdjuster,
        responseHeaders: Map<String, List<String>>,
        sniffingExtractorInput: ExtractorInput,
        playerId: PlayerId,
    ): HlsMediaChunkExtractor {
        var flags = DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM or tsFlags
        if (muxedCaptionFormats != null) flags = flags or DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS
        val ts = TsExtractor(
            TsExtractor.MODE_SINGLE_PMT,
            0,   // 提取阶段就把字幕解析成 Media3 的 cues（1.5 起渲染阶段不再解析原始 PGS，否则会报错）
            DefaultSubtitleParserFactory(),
            timestampAdjuster,
            PgsAwareReaderFactory(flags, muxedCaptionFormats ?: emptyList()),
            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
        )
        sniffingExtractorInput.resetPeekPosition()
        val isTs = try { ts.sniff(sniffingExtractorInput) } catch (e: Exception) { false }
        sniffingExtractorInput.resetPeekPosition()
        PgsStats.path = if (isTs) "HLS-自有TS" else "HLS-默认"
        if (isTs) {
            return BundledHlsMediaChunkExtractor(MultiAudioTsExtractor(ts, mainAudioId), format, timestampAdjuster)
        }
        return base.createExtractor(uri, format, muxedCaptionFormats, timestampAdjuster, responseHeaders, sniffingExtractorInput, playerId)
    }
}
