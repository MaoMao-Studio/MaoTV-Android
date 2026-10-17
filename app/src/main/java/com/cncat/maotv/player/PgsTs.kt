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
        if (size + n > MAX_BYTES) { size = 0; sampleTimeUs = pesTimeUs; return }   // 异常数据：丢弃重来
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
            size = 0
            sampleTimeUs = C.TIME_UNSET
        }
    }

    private companion object {
        const val END_SEGMENT = 0x80
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}

/** 在默认的 TS 读取器基础上加上 PGS（流类型 0x90） */
private class PgsAwareReaderFactory(flags: Int, captions: List<Format> = emptyList()) : TsPayloadReader.Factory {
    private val base = DefaultTsPayloadReaderFactory(flags, captions)

    override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> = base.createInitialPayloadReaders()

    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? =
        if (streamType == STREAM_TYPE_PGS) PesReader(PgsTsReader(esInfo.language))
        else base.createPayloadReader(streamType, esInfo)

    private companion object {
        const val STREAM_TYPE_PGS = 0x90
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


/**
 * HLS 用的提取器工厂：分片是 TS 时换成认识 PGS 的 TS 解析器（其余情况交给默认实现）。
 * 配合关闭“无分片准备”，播放器才会从第一个分片里发现内嵌的字幕轨道。
 */
class MaoHlsExtractorFactory(private val tsFlags: Int) : HlsExtractorFactory {
    private val base = DefaultHlsExtractorFactory(tsFlags, false)

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
            TsExtractor.MODE_HLS,
            TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
            SubtitleParser.Factory.UNSUPPORTED,
            timestampAdjuster,
            PgsAwareReaderFactory(flags, muxedCaptionFormats ?: emptyList()),
            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
        )
        sniffingExtractorInput.resetPeekPosition()
        val isTs = try { ts.sniff(sniffingExtractorInput) } catch (e: Exception) { false }
        sniffingExtractorInput.resetPeekPosition()
        if (isTs) {
            return BundledHlsMediaChunkExtractor(ts, format, timestampAdjuster)
        }
        return base.createExtractor(uri, format, muxedCaptionFormats, timestampAdjuster, responseHeaders, sniffingExtractorInput, playerId)
    }
}
