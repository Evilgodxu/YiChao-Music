package com.yichao.evilgodxu.musicpanel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object MusicMetadataWriter {

    suspend fun writeCover(context: Context, track: MusicTrack, coverBytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            write(context, track.path) { bytes ->
                writeMetadata(bytes, null, null, null, coverBytes)
            }
        }

    // 在线缓存的歌曲 path 为空、audioUri 为 MediaStore content://，无法走文件路径写入，改用 ContentResolver 就地重写
    suspend fun writeCoverToSource(context: Context, track: MusicTrack, coverBytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            if (track.path.isNotBlank()) {
                write(context, track.path) { bytes -> writeMetadata(bytes, null, null, null, coverBytes) }
            } else {
                writeCoverByUri(context, track.audioUri, coverBytes)
            }
        }

    private fun writeCoverByUri(context: Context, uriString: String, coverBytes: ByteArray): Boolean =
        rewriteByUri(context, uriString) { bytes -> writeMetadata(bytes, null, null, null, coverBytes) }

    // 就地重写 content URI 音频文件，非 content 协议不可写时返回 false
    private fun rewriteByUri(context: Context, uriString: String, block: (ByteArray) -> ByteArray?): Boolean {
        if (uriString.isBlank()) return false
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme != "content") return false
            val resolver = context.contentResolver
            val source = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
            val result = block(source) ?: return false
            resolver.openOutputStream(uri, "wt")?.use { it.write(result) } ?: return false
            true
        } catch (e: Throwable) {
            CrashLogManager.logException("MusicMetadataWriter", "经 content URI 写入音频元数据失败", e)
            false
        }
    }

    suspend fun writeTitleArtist(
        context: Context,
        track: MusicTrack,
        title: String,
        artist: String,
    ): Boolean = withContext(Dispatchers.IO) {
        write(context, track.path) { bytes ->
            writeMetadata(bytes, title, artist, null, null)
        }
    }

    // 将专辑名写回音频文件标签，保留原有标题/艺术家/封面
    suspend fun writeAlbum(
        context: Context,
        track: MusicTrack,
        album: String,
    ): Boolean = withContext(Dispatchers.IO) {
        write(context, track.path) { bytes ->
            writeMetadata(bytes, null, null, album, null)
        }
    }

    private fun write(context: Context, path: String, block: (ByteArray) -> ByteArray?): Boolean {
        if (path.isBlank()) return false
        return try {
            val file = File(path)
            val result = block(file.readBytes()) ?: return false
            val temporary = File(file.parentFile, ".${file.name}.${System.nanoTime()}.metadata.tmp")
            temporary.writeBytes(result)
            try {
                Files.move(
                    temporary.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
            true
        } catch (e: Throwable) {
            CrashLogManager.logException("MusicMetadataWriter", "写入音频文件元数据失败", e)
            false
        }
    }

    private fun writeMetadata(bytes: ByteArray, title: String?, artist: String?, album: String?, cover: ByteArray?): ByteArray? = when {
        isMp3(bytes) -> writeMp3(bytes, title, artist, album, cover)
        isMp4(bytes) -> writeMp4(bytes, title, artist, album, cover)
        isFlac(bytes) -> writeFlac(bytes, title, artist, album, cover)
        isOpus(bytes) -> writeOpus(bytes, title, artist, album, cover)
        else -> null
    }

    private fun isMp3(bytes: ByteArray) =
        bytes.startsWith("ID3") || (bytes.size >= 2 && bytes[0].toInt() and 0xff == 0xff && bytes[1].toInt() and 0xe0 == 0xe0)

    private fun isMp4(bytes: ByteArray) = bytes.size >= 12 && String(bytes, 4, 4, StandardCharsets.US_ASCII) == "ftyp"
    private fun isFlac(bytes: ByteArray) = bytes.startsWith("fLaC")
    private fun isOpus(bytes: ByteArray) = bytes.startsWith("OggS") && bytes.indexOf("OpusHead".toByteArray()) >= 0

    private fun writeMp3(source: ByteArray, title: String?, artist: String?, album: String?, cover: ByteArray?): ByteArray? {
        val hasId3 = source.startsWith("ID3") && source.size >= 10
        val version = if (hasId3) source[3].toInt() and 0xff else 4
        val flags = if (hasId3) source[5].toInt() and 0xff else 0
        if (hasId3 && (version !in 3..4 || flags and 0x1f != 0)) return null
        val tagEnd = if (hasId3) {
            val end = 10 + syncsafe(source, 6)
            if (end > source.size) return null
            end
        } else 0
        val frameStart = if (hasId3) id3FrameStart(source, version, flags, tagEnd) else 0
        val frames = ByteArrayOutputStream()
        if (hasId3 && frameStart > 10) frames.write(source, 10, frameStart - 10)
        var titleWritten = title == null
        var artistWritten = artist == null
        var albumWritten = album == null
        var coverWritten = cover == null
        var p = frameStart
        while (p + 10 <= tagEnd) {
            val id = String(source, p, 4, StandardCharsets.US_ASCII)
            if (id.all { it == '\u0000' }) break
            val length = if (version >= 4) syncsafe(source, p + 4) else int32(source, p + 4)
            if (length < 0 || p + 10 + length > tagEnd) return null
            val raw = source.copyOfRange(p, p + 10 + length)
            when (id) {
                "TIT2" -> if (!titleWritten) { textFrame(frames, "TIT2", title!!, version); titleWritten = true } else frames.write(raw)
                "TPE1" -> if (!artistWritten) { textFrame(frames, "TPE1", artist!!, version); artistWritten = true } else frames.write(raw)
                "TALB" -> if (!albumWritten) { textFrame(frames, "TALB", album!!, version); albumWritten = true } else frames.write(raw)
                "APIC" -> if (cover != null && !coverWritten) { apicFrame(frames, cover, version); coverWritten = true } else frames.write(raw)
                else -> frames.write(raw)
            }
            p += 10 + length
        }
        if (!titleWritten) textFrame(frames, "TIT2", title!!, version)
        if (!artistWritten) textFrame(frames, "TPE1", artist!!, version)
        if (!albumWritten) textFrame(frames, "TALB", album!!, version)
        if (!coverWritten) apicFrame(frames, cover!!, version)
        val outputVersion = if (hasId3 && version == 3) 3 else 4
        val tag = ByteArrayOutputStream()
        tag.write("ID3".toByteArray()); tag.write(byteArrayOf(outputVersion.toByte(), 0, flags.toByte()))
        tag.write(syncsafeBytes(frames.size())); tag.write(frames.toByteArray())
        val audioStart = if (hasId3) tagEnd else 0
        return tag.toByteArray() + source.copyOfRange(audioStart, source.size)
    }

    private fun id3FrameStart(source: ByteArray, version: Int, flags: Int, tagEnd: Int): Int {
        var p = 10
        if (flags and 0x40 != 0 && p + 4 <= tagEnd) {
            val size = if (version >= 4) syncsafe(source, p) else int32(source, p)
            p += 4 + size
        }
        return p.coerceAtMost(tagEnd)
    }

    private fun textFrame(out: ByteArrayOutputStream, id: String, value: String, version: Int) {
        val data = if (version >= 4) {
            byteArrayOf(3) + value.toByteArray(StandardCharsets.UTF_8) + 0
        } else {
            byteArrayOf(1) + byteArrayOf(0xff.toByte(), 0xfe.toByte()) + value.toByteArray(StandardCharsets.UTF_16LE) + byteArrayOf(0, 0)
        }
        frame(out, id, data, version)
    }

    private fun apicFrame(out: ByteArrayOutputStream, cover: ByteArray, version: Int) {
        val mime = sniffMimeType(cover).toByteArray(StandardCharsets.ISO_8859_1)
        val data = if (version >= 4) {
            byteArrayOf(3) + mime + byteArrayOf(0, 3, 0) + cover
        } else {
            byteArrayOf(1) + mime + byteArrayOf(0) + byteArrayOf(3) + byteArrayOf(0, 0) + cover
        }
        frame(out, "APIC", data, version)
    }

    private fun frame(out: ByteArrayOutputStream, id: String, data: ByteArray, version: Int) {
        out.write(id.toByteArray(StandardCharsets.US_ASCII))
        out.write(if (version >= 4) syncsafeBytes(data.size) else intBytes(data.size))
        out.write(byteArrayOf(0, 0)); out.write(data)
    }

    private fun writeFlac(source: ByteArray, title: String?, artist: String?, album: String?, cover: ByteArray?): ByteArray? {
        if (!source.startsWith("fLaC")) return null
        val blocks = mutableListOf<FlacBlock>()
        var p = 4
        var hasLastBlock = false
        while (p + 4 <= source.size) {
            val header = source[p].toInt() and 0xff
            val type = header and 0x7f
            val length = (source[p + 1].toInt() and 0xff shl 16) or
                (source[p + 2].toInt() and 0xff shl 8) or (source[p + 3].toInt() and 0xff)
            if (p + 4 + length > source.size) return null
            blocks += FlacBlock(type, source.copyOfRange(p + 4, p + 4 + length))
            p += 4 + length
            if (header and 0x80 != 0) {
                hasLastBlock = true
                break
            }
        }
        if (!hasLastBlock || p > source.size) return null
        if (title != null || artist != null || album != null) {
            val commentIndex = blocks.indexOfFirst { it.type == 4 }
            val comments = buildComments(
                if (commentIndex >= 0) blocks[commentIndex].data else null,
                title, artist, album,
            )
            if (commentIndex >= 0) blocks[commentIndex] = FlacBlock(4, comments) else blocks.add(FlacBlock(4, comments))
        }
        if (cover != null) {
            blocks.removeAll { block ->
                block.type == 6 && block.data.size >= 4 && int32(block.data, 0) == 3
            }
            blocks.add(FlacBlock(6, pictureBlock(cover)))
        }
        return buildFlac(blocks) + source.copyOfRange(p, source.size)
    }

    private data class FlacBlock(val type: Int, val data: ByteArray)

    private fun buildComments(original: ByteArray?, title: String?, artist: String?, album: String?): ByteArray {
        val vendor: ByteArray
        val fields = mutableListOf<String>()
        if (original != null && original.size >= 8) {
            val vendorLength = intLE(original, 0)
            if (vendorLength >= 0 && 8 + vendorLength <= original.size) {
                vendor = original.copyOfRange(4, 4 + vendorLength)
                var p = 8 + vendorLength
                val count = intLE(original, 4 + vendorLength)
                repeat(count.coerceAtLeast(0)) {
                    if (p + 4 > original.size) return@repeat
                    val length = intLE(original, p); p += 4
                    if (length >= 0 && p + length <= original.size) {
                        val value = String(original, p, length, StandardCharsets.UTF_8)
                        val key = value.substringBefore('=').uppercase()
                        if ((title == null || key != "TITLE") &&
                            (artist == null || key != "ARTIST") &&
                            (album == null || key != "ALBUM")
                        ) fields += value
                        p += length
                    }
                }
            } else return buildComments(null, title, artist, album)
        } else vendor = "EdgeGesture".toByteArray()
        if (title != null) fields.add("TITLE=$title")
        if (artist != null) fields.add("ARTIST=$artist")
        if (album != null) fields.add("ALBUM=$album")
        val out = ByteArrayOutputStream(); out.write(intBytesLE(vendor.size)); out.write(vendor); out.write(intBytesLE(fields.size))
        fields.forEach { val value = it.toByteArray(StandardCharsets.UTF_8); out.write(intBytesLE(value.size)); out.write(value) }
        return out.toByteArray()
    }

    private fun buildFlac(blocks: List<FlacBlock>): ByteArray {
        val out = ByteArrayOutputStream(); out.write("fLaC".toByteArray())
        blocks.forEachIndexed { index, block ->
            out.write(byteArrayOf((block.type or if (index == blocks.lastIndex) 0x80 else 0).toByte()))
            out.write(byteArrayOf((block.data.size shr 16).toByte(), (block.data.size shr 8).toByte(), block.data.size.toByte()))
            out.write(block.data)
        }
        return out.toByteArray()
    }

    private fun writeMp4(source: ByteArray, title: String?, artist: String?, album: String?, cover: ByteArray?): ByteArray? {
        val atoms = Mp4Atom.parseAll(source)?.toMutableList() ?: return null
        val moovIndex = atoms.indexOfFirst { it.type == "moov" }
        if (moovIndex < 0) return null
        val moov = atoms[moovIndex]
        val originalMoovSize = moov.build().size
        moov.replaceMetadata(title, artist, album, cover)
        val sizeDelta = moov.build().size - originalMoovSize
        if (sizeDelta != 0 && atoms.indexOfFirst { it.type == "mdat" } > moovIndex) {
            moov.adjustChunkOffsets(sizeDelta)
        }
        return atoms.joinToByteArray()
    }

    private fun writeOpus(source: ByteArray, title: String?, artist: String?, album: String?, cover: ByteArray?): ByteArray? {
        val parsed = OggFile.parse(source) ?: return null
        val tagsIndex = parsed.packets.indexOfFirst { it.data.startsWith("OpusTags") }
        if (tagsIndex < 0) return null
        parsed.packets[tagsIndex] = parsed.packets[tagsIndex].copy(
            data = updateOpusTags(parsed.packets[tagsIndex].data, title, artist, album, cover),
        )
        return OggFile.build(parsed)
    }

    private fun updateOpusTags(original: ByteArray, title: String?, artist: String?, album: String?, cover: ByteArray?): ByteArray {
        val fields = mutableListOf<String>(); var p = 8
        var vendor = "EdgeGesture".toByteArray()
        if (p + 4 <= original.size) {
            val vendorLength = intLE(original, p)
            if (vendorLength < 0 || p + 4 + vendorLength > original.size) return original
            vendor = original.copyOfRange(p + 4, p + 4 + vendorLength)
            p += 4 + vendorLength
            if (p + 4 <= original.size) {
                val count = intLE(original, p); p += 4
                repeat(count.coerceAtLeast(0)) {
                    if (p + 4 > original.size) return@repeat
                    val length = intLE(original, p); p += 4
                    if (length >= 0 && p + length <= original.size) {
                        val value = String(original, p, length, StandardCharsets.UTF_8)
                        val key = value.substringBefore('=').uppercase()
                        if ((title == null || key != "TITLE") &&
                            (artist == null || key != "ARTIST") &&
                            (album == null || key != "ALBUM") &&
                            (cover == null || key != "METADATA_BLOCK_PICTURE")
                        ) fields += value
                        p += length
                    }
                }
            }
        }
        if (title != null) fields += "TITLE=$title"
        if (artist != null) fields += "ARTIST=$artist"
        if (album != null) fields += "ALBUM=$album"
        if (cover != null) fields += "METADATA_BLOCK_PICTURE=" + android.util.Base64.encodeToString(pictureBlock(cover), android.util.Base64.NO_WRAP)
        val out = ByteArrayOutputStream(); out.write("OpusTags".toByteArray()); out.write(intBytesLE(vendor.size)); out.write(vendor); out.write(intBytesLE(fields.size))
        fields.forEach { val bytes = it.toByteArray(); out.write(intBytesLE(bytes.size)); out.write(bytes) }
        return out.toByteArray()
    }

    private fun pictureBlock(cover: ByteArray): ByteArray {
        val mime = sniffMimeType(cover).toByteArray(); val out = ByteArrayOutputStream()
        out.write(intBytes(3)); out.write(intBytes(mime.size)); out.write(mime); out.write(intBytes(0)); repeat(4) { out.write(intBytes(0)) }; out.write(intBytes(cover.size)); out.write(cover)
        return out.toByteArray()
    }

    private data class Mp4Atom(val type: String, var data: ByteArray, val children: MutableList<Mp4Atom>?) {
        fun build(): ByteArray {
            val body = if (children != null) data + children.joinToByteArray() else data
            return intBytes(body.size + 8) + type.toByteArray(StandardCharsets.ISO_8859_1) + body
        }

        fun replaceMetadata(title: String?, artist: String?, album: String?, cover: ByteArray?): Mp4Atom {
            if (type == "moov") {
                val udta = children?.firstOrNull { it.type == "udta" }
                if (udta != null) udta.replaceMetadata(title, artist, album, cover) else children?.add(Mp4Atom("udta", ByteArray(0), mutableListOf(metaAtom(title, artist, album, cover))))
            } else if (type == "udta") {
                val meta = children?.firstOrNull { it.type == "meta" }
                if (meta != null) meta.replaceMetadata(title, artist, album, cover) else children?.add(metaAtom(title, artist, album, cover))
            } else if (type == "meta") {
                if (children?.none { it.type == "hdlr" } == true) {
                    children.add(0, hdlrAtom())
                }
                val ilst = children?.firstOrNull { it.type == "ilst" }
                if (ilst != null) {
                    ilst.replaceItems(title, artist, album, cover)
                } else {
                    children?.add(ilstAtom(title, artist, album, cover))
                }
            }
            return this
        }

        fun adjustChunkOffsets(delta: Int) {
            if (type == "stco" && data.size >= 8) {
                val count = int32(data, 4)
                if (count >= 0 && 8 + count * 4 <= data.size) {
                    for (index in 0 until count) {
                        val position = 8 + index * 4
                        val offset = int32(data, position).toLong() + delta
                        if (offset !in 0..0xffffffffL) return
                        writeInt32(data, position, offset.toInt())
                    }
                }
            } else if (type == "co64" && data.size >= 8) {
                val count = int32(data, 4)
                if (count >= 0 && 8L + count * 8L <= data.size) {
                    for (index in 0 until count) {
                        val position = 8 + index * 8
                        val offset = long64(data, position) + delta
                        if (offset < 0) return
                        writeLong64(data, position, offset)
                    }
                }
            }
            children?.forEach { it.adjustChunkOffsets(delta) }
        }

        private fun replaceItems(title: String?, artist: String?, album: String?, cover: ByteArray?) {
            val mp4Cover = cover?.let { toMp4Cover(it) }
            val kept = children.orEmpty().filterNot {
                (title != null && it.type == "©nam") ||
                    (artist != null && it.type == "©ART") ||
                    (album != null && it.type == "©alb") ||
                    (mp4Cover != null && it.type == "covr")
            }.toMutableList()
            if (title != null) kept.add(dataAtom("©nam", title.toByteArray()))
            if (artist != null) kept.add(dataAtom("©ART", artist.toByteArray()))
            if (album != null) kept.add(dataAtom("©alb", album.toByteArray()))
            if (mp4Cover != null) kept.add(dataAtom("covr", mp4Cover.first, mp4Cover.second))
            children?.clear(); children?.addAll(kept)
        }

        companion object {
            fun parseAll(bytes: ByteArray): MutableList<Mp4Atom>? {
                val result = mutableListOf<Mp4Atom>(); var p = 0
                while (p + 8 <= bytes.size) { val atom = parse(bytes, p, bytes.size) ?: return null; result += atom; val size = int32(bytes, p); if (size < 8) return null; p += size }
                return if (p == bytes.size) result else null
            }
            private fun parse(bytes: ByteArray, start: Int, end: Int): Mp4Atom? {
                if (start + 8 > end) return null
                val size = int32(bytes, start); if (size < 8 || start + size > end) return null
                val type = String(bytes, start + 4, 4, StandardCharsets.ISO_8859_1); val payloadStart = start + 8; val payloadEnd = start + size
                // trak/mdia/minf/stbl 按容器解析，调整 stco/co64 时才能遍历到内部的 chunk 偏移
                val container = type == "moov" || type == "trak" || type == "mdia" || type == "minf" || type == "stbl" ||
                    type == "udta" || type == "meta" || type == "ilst" || type == "©nam" || type == "©ART" || type == "©alb" || type == "covr"
                if (!container) return Mp4Atom(type, bytes.copyOfRange(payloadStart, payloadEnd), null)
                val head = if (type == "meta") 4 else 0; val children = mutableListOf<Mp4Atom>(); var p = payloadStart + head
                while (p + 8 <= payloadEnd) { val child = parse(bytes, p, payloadEnd) ?: return null; children += child; val childSize = int32(bytes, p); if (childSize < 8) return null; p += childSize }
                if (p != payloadEnd) return null
                return Mp4Atom(type, bytes.copyOfRange(payloadStart, payloadStart + head), children)
            }
            private fun metaAtom(title: String?, artist: String?, album: String?, cover: ByteArray?) =
                Mp4Atom("meta", byteArrayOf(0, 0, 0, 0), mutableListOf(hdlrAtom(), ilstAtom(title, artist, album, cover)))

            // meta 需要 hdlr（handler_type=mdir）才被识别为 iTunes 风格元数据
            private fun hdlrAtom(): Mp4Atom {
                val data = ByteArrayOutputStream()
                data.write(byteArrayOf(0, 0, 0, 0))
                data.write(byteArrayOf(0, 0, 0, 0))
                data.write("mdir".toByteArray(StandardCharsets.ISO_8859_1))
                data.write(ByteArray(12))
                data.write(0)
                return Mp4Atom("hdlr", data.toByteArray(), null)
            }
            private fun ilstAtom(title: String?, artist: String?, album: String?, cover: ByteArray?) = Mp4Atom("ilst", ByteArray(0), buildList {
                if (title != null) add(dataAtom("©nam", title.toByteArray()))
                if (artist != null) add(dataAtom("©ART", artist.toByteArray()))
                if (album != null) add(dataAtom("©alb", album.toByteArray()))
                cover?.let { toMp4Cover(it) }?.let { add(dataAtom("covr", it.first, it.second)) }
            }.toMutableList())
            // data atom 布局：type(4字节，1=文本/13=JPEG/14=PNG) + locale(4字节全0) + 数据
            private fun dataAtom(type: String, value: ByteArray, kind: Int = 1) =
                Mp4Atom(type, ByteArray(0), mutableListOf(Mp4Atom("data", intBytes(kind) + byteArrayOf(0, 0, 0, 0) + value, null)))
        }
    }

    private data class OggPacket(var data: ByteArray, val granulePosition: Long)

    private data class OggPage(
        val headerType: Int,
        val granulePosition: Long,
        val serial: Int,
        val sequence: Int,
        val segmentCount: Int,
    )

    private data class OggFile(
        val packets: MutableList<OggPacket>,
        val pages: List<OggPage>,
        val serial: Int,
        val firstSequence: Int,
    ) {
        companion object {
            fun parse(bytes: ByteArray): OggFile? {
                val pages = mutableListOf<OggPage>()
                val packets = mutableListOf<OggPacket>()
                val packet = ByteArrayOutputStream()
                var packetGranule = 0L
                var p = 0
                var serial: Int? = null
                var expectedSequence: Long? = null
                while (p + 27 <= bytes.size) {
                    if (String(bytes, p, 4, StandardCharsets.US_ASCII) != "OggS" || bytes[p + 4].toInt() != 0) return null
                    val headerType = bytes[p + 5].toInt() and 0xff
                    val granule = longLE(bytes, p + 6)
                    val pageSerial = intLE(bytes, p + 14)
                    val sequence = intLE(bytes, p + 18)
                    val segmentCount = bytes[p + 26].toInt() and 0xff
                    val lacingEnd = p + 27 + segmentCount
                    if (lacingEnd > bytes.size) return null
                    val bodyLength = (0 until segmentCount).sumOf { bytes[p + 27 + it].toInt() and 0xff }
                    val pageEnd = lacingEnd + bodyLength
                    if (pageEnd > bytes.size || (headerType and 1 != 0) != (packet.size() != 0)) return null
                    if (serial == null) serial = pageSerial
                    if (serial != pageSerial || (expectedSequence != null && expectedSequence != sequence.toLong())) return null
                    expectedSequence = sequence.toLong() + 1
                    pages += OggPage(headerType, granule, pageSerial, sequence, segmentCount)
                    var bodyOffset = lacingEnd
                    for (index in 0 until segmentCount) {
                        val length = bytes[p + 27 + index].toInt() and 0xff
                        packet.write(bytes, bodyOffset, length)
                        bodyOffset += length
                        if (length < 255) {
                            packetGranule = granule
                            packets += OggPacket(packet.toByteArray(), packetGranule)
                            packet.reset()
                        }
                    }
                    p = pageEnd
                }
                if (p != bytes.size || packet.size() != 0 || pages.isEmpty()) return null
                return OggFile(packets, pages, serial!!, pages.first().sequence)
            }

            fun build(file: OggFile): ByteArray {
                val units = mutableListOf<Pair<ByteArray, Boolean>>()
                file.packets.forEach { packet ->
                    var offset = 0
                    while (offset < packet.data.size) {
                        val length = minOf(255, packet.data.size - offset)
                        units += packet.data.copyOfRange(offset, offset + length) to (offset + length == packet.data.size && length < 255)
                        offset += length
                    }
                    if (packet.data.isEmpty() || packet.data.size % 255 == 0) units += ByteArray(0) to true
                }
                val pages = mutableListOf<ByteArray>()
                var unitIndex = 0
                var pageIndex = 0
                var packetContinues = false
                while (unitIndex < units.size) {
                    val capacity = if (pageIndex < file.pages.size) file.pages[pageIndex].segmentCount else 255
                    val count = minOf(capacity, units.size - unitIndex)
                    val metadata = file.pages.getOrNull(pageIndex) ?: file.pages.last()
                    val lacing = units.subList(unitIndex, unitIndex + count)
                    val body = ByteArrayOutputStream()
                    lacing.forEach { body.write(it.first) }
                    val headerType = (metadata.headerType and 0xf8) or
                        (if (packetContinues) 1 else 0) or
                        (if (pageIndex == 0 && metadata.headerType and 2 != 0) 2 else 0)
                    var granule = -1L
                    lacing.forEachIndexed { index, unit ->
                        if (unit.second) granule = file.packetsGranuleAt(unitIndex + index)
                    }
                    if (granule == -1L && pageIndex < file.pages.size && !packetContinues) granule = metadata.granulePosition
                    val page = ByteArrayOutputStream()
                    page.write("OggS".toByteArray()); page.write(0); page.write(headerType)
                    page.write(longBytesLE(granule)); page.write(intBytesLE(file.serial)); page.write(intBytesLE(file.firstSequence + pageIndex)); page.write(intBytesLE(0)); page.write(count)
                    lacing.forEach { page.write(it.first.size) }; page.write(body.toByteArray())
                    val result = page.toByteArray(); val crc = oggCrc(result)
                    result[22] = crc.toByte(); result[23] = (crc shr 8).toByte(); result[24] = (crc shr 16).toByte(); result[25] = (crc shr 24).toByte()
                    pages += result
                    packetContinues = !lacing.last().second
                    unitIndex += count; pageIndex++
                }
                if (pages.isNotEmpty()) {
                    val last = pages.last(); last[5] = (last[5].toInt() and 0xff or 4).toByte()
                    last[22] = 0; last[23] = 0; last[24] = 0; last[25] = 0
                    val crc = oggCrc(last); last[22] = crc.toByte(); last[23] = (crc shr 8).toByte(); last[24] = (crc shr 16).toByte(); last[25] = (crc shr 24).toByte()
                }
                return pages.fold(ByteArrayOutputStream()) { out, page -> out.apply { write(page) } }.toByteArray()
            }

            private fun OggFile.packetsGranuleAt(unitIndex: Int): Long {
                var index = 0
                packets.forEach { packet ->
                    val units = (packet.data.size + 254) / 255 + if (packet.data.isEmpty() || packet.data.size % 255 == 0) 1 else 0
                    if (unitIndex < index + units) return packet.granulePosition
                    index += units
                }
                return -1L
            }
        }
    }

    private fun sniffMimeType(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
        bytes.size >= 12 && String(bytes, 0, 4, StandardCharsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, StandardCharsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> "image/jpeg"
    }

    // M4A 的 covr 只支持 JPEG(13)/PNG(14)：其余格式（WEBP/HEIC/BMP 等）解码后转成 JPEG 再内嵌，
    // 否则播放器按声明的 JPEG 解码真实数据会失败导致封面空白
    private fun toMp4Cover(cover: ByteArray): Pair<ByteArray, Int>? {
        if (cover.size >= 3 && cover[0] == 0xff.toByte() && cover[1] == 0xd8.toByte() && cover[2] == 0xff.toByte()) return cover to 13
        if (cover.size >= 8 && cover.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) return cover to 14
        return try {
            val bitmap = BitmapFactory.decodeByteArray(cover, 0, cover.size) ?: return null
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) return null
            bitmap.recycle()
            out.toByteArray() to 13
        } catch (e: Exception) {
            CrashLogManager.logException("MusicMetadataWriter", "封面转 JPEG 失败", e)
            null
        }
    }

    private fun oggCrc(bytes: ByteArray): Int { var crc = 0; bytes.forEachIndexed { index, value -> if (index in 22..25) return@forEachIndexed; crc = crc xor ((value.toInt() and 0xff) shl 24); repeat(8) { crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 } }; return crc }
    private fun syncsafe(b: ByteArray, p: Int) = (b[p].toInt() and 0x7f shl 21) or (b[p + 1].toInt() and 0x7f shl 14) or (b[p + 2].toInt() and 0x7f shl 7) or (b[p + 3].toInt() and 0x7f)
    private fun syncsafeBytes(v: Int) = byteArrayOf((v shr 21 and 0x7f).toByte(), (v shr 14 and 0x7f).toByte(), (v shr 7 and 0x7f).toByte(), (v and 0x7f).toByte())
    private fun int32(b: ByteArray, p: Int) = ByteBuffer.wrap(b, p, 4).order(ByteOrder.BIG_ENDIAN).int
    private fun long64(b: ByteArray, p: Int) = ByteBuffer.wrap(b, p, 8).order(ByteOrder.BIG_ENDIAN).long
    private fun writeInt32(b: ByteArray, p: Int, value: Int) { ByteBuffer.wrap(b, p, 4).order(ByteOrder.BIG_ENDIAN).putInt(value) }
    private fun writeLong64(b: ByteArray, p: Int, value: Long) { ByteBuffer.wrap(b, p, 8).order(ByteOrder.BIG_ENDIAN).putLong(value) }
    private fun intLE(b: ByteArray, p: Int) = ByteBuffer.wrap(b, p, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun longLE(b: ByteArray, p: Int) = ByteBuffer.wrap(b, p, 8).order(ByteOrder.LITTLE_ENDIAN).long
    private fun intBytes(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun intBytesLE(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun longBytesLE(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
    private fun ByteArray.startsWith(value: String) = size >= value.length && String(this, 0, value.length, StandardCharsets.US_ASCII) == value
    private fun ByteArray.indexOf(value: ByteArray): Int = (0..(size - value.size)).firstOrNull { copyOfRange(it, it + value.size).contentEquals(value) } ?: -1
    private fun List<Mp4Atom>.joinToByteArray(): ByteArray { val out = ByteArrayOutputStream(); forEach { out.write(it.build()) }; return out.toByteArray() }
}
