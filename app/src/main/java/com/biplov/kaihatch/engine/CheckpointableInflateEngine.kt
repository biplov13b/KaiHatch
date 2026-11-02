package com.biplov.kaihatch.engine

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.CheckedOutputStream
import java.util.zip.DataFormatException

/**
 * Standalone RFC 1951 (raw DEFLATE) decompressor capable of saving and restoring its complete
 * internal state at safe compressed-stream chunk boundaries.
 *
 * Designed for KaiHatch's sub-entry progressive in-place extraction on large DEFLATE entries:
 * - Supports BTYPE=00 (Stored/uncompressed), BTYPE=01 (Fixed Huffman), and BTYPE=10 (Dynamic Huffman).
 * - Pauses decoding before the end of any non-final chunk (`SAFE_HEADER_MARGIN_BYTES` at block
 *   boundaries, `SAFE_SYMBOL_MARGIN_BYTES` inside Huffman blocks) so checkpoints always occur at
 *   clean between-symbol or stored-byte boundaries.
 * - Exports/imports a self-verifying binary checkpoint (`CheckpointData` / file with CRC32 footer)
 *   containing the 32 KB LZ77 sliding window, active canonical Huffman code lengths (320 B),
 *   bit accumulator, unconsumed compressed tail bytes, output byte count, and resumable IEEE 802.3 CRC32.
 */
class CheckpointableInflateEngine {

    class InvalidCheckpointException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    /**
     * Immutable, serializable snapshot of the complete decoder state at a chunk boundary.
     */
    data class CheckpointData(
        val compressedBytesConsumed: Long,
        val outputBytesWritten: Long,
        val runningCrc32: Long,
        val blockMode: Int,
        val isFinalBlock: Boolean,
        val activeBlockType: Int,
        val storedBytesRemaining: Int,
        val bitBuffer: Long,
        val bitCount: Int,
        val litLenCodeLengths: ByteArray,
        val distCodeLengths: ByteArray,
        val pendingInput: ByteArray,
        val windowPos: Int,
        val windowFilled: Int,
        val window: ByteArray
    )

    /**
     * Pure-Kotlin table-driven IEEE 802.3 CRC32 matching `java.util.zip.CRC32` bit-for-bit,
     * with O(1) state export and import across process restarts.
     */
    class ResumableCrc32 {
        private var crc: Int = -1 // 0xFFFFFFFF

        fun update(buf: ByteArray, offset: Int, len: Int) {
            var c = crc
            val end = offset + len
            var i = offset
            while (i < end) {
                c = (c ushr 8) xor CRC_TABLE[(c xor buf[i].toInt()) and 0xFF]
                i++
            }
            crc = c
        }

        val value: Long
            get() = (crc.inv().toLong() and 0xFFFFFFFFL)

        fun restoreValue(savedValue: Long) {
            crc = savedValue.toInt().inv()
        }

        fun reset() {
            crc = -1
        }

        companion object {
            private val CRC_TABLE = IntArray(256).apply {
                for (n in 0 until 256) {
                    var c = n
                    for (k in 0 until 8) {
                        c = if ((c and 1) != 0) {
                            (c ushr 1) xor 0xEDB88320.toInt()
                        } else {
                            c ushr 1
                        }
                    }
                    this[n] = c
                }
            }
        }
    }

    // Decoder state machine modes
    private var blockMode: Int = MODE_BLOCK_HEADER
    private var isFinalBlock: Boolean = false
    private var activeBlockType: Int = 0
    private var storedBytesRemaining: Int = 0

    // 64-bit LSB-first bit accumulator
    private var bitBuffer: Long = 0L
    private var bitCount: Int = 0

    // Canonical Huffman code-length arrays (serialized in checkpoints when activeBlockType == 2)
    private val litLenCodeLengths = ByteArray(MAX_LIT_LEN_CODES)
    private val distCodeLengths = ByteArray(MAX_DIST_CODES)

    // Fast 1-step lookup tables: entry = ((symbol + 1) shl 8) or bitLength
    private val litLenTable = IntArray(1 shl MAX_HUFFMAN_BITS)
    private var litLenMask: Int = 0
    private val distTable = IntArray(1 shl MAX_HUFFMAN_BITS)
    private var distMask: Int = 0
    private var distTableHasCodes: Boolean = false

    // Temporary table for decoding dynamic header code-length alphabet (19 symbols, max 7 bits)
    private val headerCodeLengths = ByteArray(19)
    private val headerTable = IntArray(1 shl 7)
    private var headerMask: Int = 0

    // 32 KB circular LZ77 sliding dictionary
    private val window = ByteArray(WINDOW_SIZE)
    private var windowPos: Int = 0
    private var windowFilled: Int = 0

    // Unconsumed compressed bytes carried across non-final chunk boundaries
    private var pendingInput = ByteArray(0)

    // Stream accounting & integrity
    var compressedBytesConsumed: Long = 0L
        private set
    var outputBytesWritten: Long = 0L
        private set
    private val runningCrc = ResumableCrc32()

    // 64 KB batch output buffer to minimize OutputStream write calls
    private val outScratch = ByteArray(OUT_SCRATCH_SIZE)

    // Reusable combined input buffer when pendingInput is prepended to a chunk
    private var combinedInputBuf = ByteArray(0)

    val isFinished: Boolean
        get() = blockMode == MODE_FINISHED

    val currentCrc32: Long
        get() = runningCrc.value

    fun reset() {
        blockMode = MODE_BLOCK_HEADER
        isFinalBlock = false
        activeBlockType = 0
        storedBytesRemaining = 0
        bitBuffer = 0L
        bitCount = 0
        litLenCodeLengths.fill(0)
        distCodeLengths.fill(0)
        litLenMask = 0
        distMask = 0
        distTableHasCodes = false
        window.fill(0)
        windowPos = 0
        windowFilled = 0
        pendingInput = ByteArray(0)
        compressedBytesConsumed = 0L
        outputBytesWritten = 0L
        runningCrc.reset()
    }

    /**
     * Feeds a chunk of compressed DEFLATE bytes (`chunk[offset .. offset + length)`) and writes
     * all decoded uncompressed bytes to [output].
     *
     * When [isLastChunk] is `false`, decoding pauses at a clean boundary once fewer than
     * [SAFE_HEADER_MARGIN_BYTES] (at a block boundary) or [SAFE_SYMBOL_MARGIN_BYTES] (inside a
     * Huffman block) remain in the input buffer, storing the unconsumed tail bytes in [pendingInput]
     * so [saveCheckpoint] can be called immediately afterward.
     *
     * @return Number of uncompressed bytes written to [output] during this call.
     * @throws DataFormatException if the DEFLATE stream is corrupted or truncated.
     */
    fun processChunk(
        chunk: ByteArray,
        offset: Int = 0,
        length: Int = chunk.size - offset,
        isLastChunk: Boolean,
        output: OutputStream
    ): Long {
        if (offset < 0 || length < 0 || offset + length > chunk.size) {
            throw IllegalArgumentException("Invalid chunk bounds: offset=$offset, length=$length, size=${chunk.size}")
        }
        if (blockMode == MODE_FINISHED) {
            return 0L
        }

        val inBuf: ByteArray
        var inPos: Int
        val inLimit: Int
        val pendingLen = pendingInput.size

        if (pendingLen > 0) {
            val totalLen = pendingLen + length
            if (combinedInputBuf.size < totalLen) {
                combinedInputBuf = ByteArray(totalLen.coerceAtLeast(65536))
            }
            System.arraycopy(pendingInput, 0, combinedInputBuf, 0, pendingLen)
            if (length > 0) {
                System.arraycopy(chunk, offset, combinedInputBuf, pendingLen, length)
            }
            pendingInput = ByteArray(0)
            inBuf = combinedInputBuf
            inPos = 0
            inLimit = totalLen
        } else {
            inBuf = chunk
            inPos = offset
            inLimit = offset + length
        }

        val initialInPos = inPos
        var producedThisCall = 0L
        var outPos = 0

        var bBuf = bitBuffer
        var bCnt = bitCount
        var winPos = windowPos
        var winFilled = windowFilled

        fun flushScratch() {
            if (outPos > 0) {
                output.write(outScratch, 0, outPos)
                runningCrc.update(outScratch, 0, outPos)
                outputBytesWritten += outPos.toLong()
                producedThisCall += outPos.toLong()
                outPos = 0
            }
        }

        fun refillBits(minBits: Int) {
            while (bCnt < minBits && inPos < inLimit) {
                bBuf = bBuf or ((inBuf[inPos++].toLong() and 0xFFL) shl bCnt)
                bCnt += 8
            }
            if (bCnt < minBits) {
                throw DataFormatException("Unexpected EOF in DEFLATE stream (needed $minBits bits, had $bCnt)")
            }
        }

        fun readBits(count: Int): Int {
            if (count == 0) return 0
            if (bCnt < count) {
                refillBits(count)
            }
            val v = (bBuf and ((1L shl count) - 1L)).toInt()
            bBuf = bBuf ushr count
            bCnt -= count
            return v
        }

        try {
            mainLoop@ while (blockMode != MODE_FINISHED) {
                when (blockMode) {
                    MODE_BLOCK_HEADER -> {
                        val availableBytes = (inLimit - inPos) + (bCnt ushr 3)
                        if (!isLastChunk && availableBytes < SAFE_HEADER_MARGIN_BYTES) {
                            break@mainLoop
                        }

                        val bFinal = readBits(1)
                        val bType = readBits(2)
                        isFinalBlock = (bFinal == 1)
                        activeBlockType = bType

                        when (bType) {
                            0 -> {
                                // BTYPE=00: Non-compressed (stored) block
                                // Discard remaining bits up to the next byte boundary
                                val dropBits = bCnt and 7
                                if (dropBits > 0) {
                                    bBuf = bBuf ushr dropBits
                                    bCnt -= dropBits
                                }
                                val len = readBits(16)
                                val nlen = readBits(16)
                                if (len != (nlen xor 0xFFFF)) {
                                    throw DataFormatException(
                                        "Corrupted stored block length: len=$len, nlen=$nlen"
                                    )
                                }
                                storedBytesRemaining = len
                                blockMode = if (len == 0) {
                                    if (isFinalBlock) MODE_FINISHED else MODE_BLOCK_HEADER
                                } else {
                                    MODE_STORED_BODY
                                }
                            }
                            1 -> {
                                // BTYPE=01: Fixed Huffman tables
                                setupFixedHuffmanTables()
                                blockMode = MODE_HUFFMAN_BODY
                            }
                            2 -> {
                                // BTYPE=10: Dynamic Huffman tables
                                val hlit = readBits(5) + 257
                                val hdist = readBits(5) + 1
                                val hclen = readBits(4) + 4
                                if (hlit > MAX_LIT_LEN_CODES || hdist > MAX_DIST_CODES) {
                                    throw DataFormatException("Invalid HLIT ($hlit) or HDIST ($hdist)")
                                }

                                headerCodeLengths.fill(0)
                                for (i in 0 until hclen) {
                                    headerCodeLengths[CODE_LENGTH_ORDER[i]] = readBits(3).toByte()
                                }
                                headerMask = buildHuffmanTable(
                                    codeLengths = headerCodeLengths,
                                    numSymbols = 19,
                                    maxAllowedBits = 7,
                                    table = headerTable,
                                    allowEmpty = false
                                )

                                // Decode HLIT + HDIST code lengths (RLE can cross HLIT/HDIST boundary)
                                val totalCodes = hlit + hdist
                                val unpackedLengths = ByteArray(totalCodes)
                                var idx = 0
                                while (idx < totalCodes) {
                                    if (bCnt < 15 && inPos < inLimit) {
                                        while (bCnt <= 56 && inPos < inLimit) {
                                            bBuf = bBuf or ((inBuf[inPos++].toLong() and 0xFFL) shl bCnt)
                                            bCnt += 8
                                        }
                                    }
                                    val entry = headerTable[bBuf.toInt() and headerMask]
                                    if (entry == 0) {
                                        throw DataFormatException("Invalid code-length Huffman symbol in dynamic header")
                                    }
                                    val codeLenBits = entry and 0xFF
                                    if (bCnt < codeLenBits) {
                                        refillBits(codeLenBits)
                                    }
                                    bBuf = bBuf ushr codeLenBits
                                    bCnt -= codeLenBits
                                    val sym = (entry ushr 8) - 1

                                    when {
                                        sym in 0..15 -> {
                                            unpackedLengths[idx++] = sym.toByte()
                                        }
                                        sym == 16 -> {
                                            if (idx == 0) {
                                                throw DataFormatException("Repeat code 16 at start of code-length table")
                                            }
                                            val prev = unpackedLengths[idx - 1]
                                            val repeat = readBits(2) + 3
                                            if (idx + repeat > totalCodes) {
                                                throw DataFormatException("Code-length repeat 16 overflows table ($idx + $repeat > $totalCodes)")
                                            }
                                            for (r in 0 until repeat) {
                                                unpackedLengths[idx++] = prev
                                            }
                                        }
                                        sym == 17 -> {
                                            val repeat = readBits(3) + 3
                                            if (idx + repeat > totalCodes) {
                                                throw DataFormatException("Code-length repeat 17 overflows table ($idx + $repeat > $totalCodes)")
                                            }
                                            for (r in 0 until repeat) {
                                                unpackedLengths[idx++] = 0
                                            }
                                        }
                                        sym == 18 -> {
                                            val repeat = readBits(7) + 11
                                            if (idx + repeat > totalCodes) {
                                                throw DataFormatException("Code-length repeat 18 overflows table ($idx + $repeat > $totalCodes)")
                                            }
                                            for (r in 0 until repeat) {
                                                unpackedLengths[idx++] = 0
                                            }
                                        }
                                        else -> throw DataFormatException("Illegal code-length symbol: $sym")
                                    }
                                }

                                litLenCodeLengths.fill(0)
                                distCodeLengths.fill(0)
                                System.arraycopy(unpackedLengths, 0, litLenCodeLengths, 0, hlit)
                                System.arraycopy(unpackedLengths, hlit, distCodeLengths, 0, hdist)

                                if (litLenCodeLengths[256].toInt() == 0) {
                                    throw DataFormatException("Dynamic Huffman table missing End-Of-Block symbol (256)")
                                }

                                litLenMask = buildHuffmanTable(
                                    codeLengths = litLenCodeLengths,
                                    numSymbols = MAX_LIT_LEN_CODES,
                                    maxAllowedBits = MAX_HUFFMAN_BITS,
                                    table = litLenTable,
                                    allowEmpty = false
                                )
                                distMask = buildHuffmanTable(
                                    codeLengths = distCodeLengths,
                                    numSymbols = MAX_DIST_CODES,
                                    maxAllowedBits = MAX_HUFFMAN_BITS,
                                    table = distTable,
                                    allowEmpty = true
                                )
                                distTableHasCodes = distCodeLengths.any { it.toInt() != 0 }
                                blockMode = MODE_HUFFMAN_BODY
                            }
                            else -> {
                                throw DataFormatException("Invalid DEFLATE block type BTYPE=3 (reserved)")
                            }
                        }
                    }

                    MODE_STORED_BODY -> {
                        // First drain any whole bytes still sitting in the bit accumulator
                        while (bCnt >= 8 && storedBytesRemaining > 0) {
                            val b = (bBuf and 0xFFL).toByte()
                            bBuf = bBuf ushr 8
                            bCnt -= 8
                            window[winPos] = b
                            winPos = (winPos + 1) and WINDOW_MASK
                            if (winFilled < WINDOW_SIZE) winFilled++
                            outScratch[outPos++] = b
                            if (outPos == OUT_SCRATCH_SIZE) flushScratch()
                            storedBytesRemaining--
                        }

                        // Bulk-copy remaining stored block bytes directly from inBuf
                        while (storedBytesRemaining > 0 && inPos < inLimit) {
                            val availIn = inLimit - inPos
                            val availOut = OUT_SCRATCH_SIZE - outPos
                            val step = minOf(storedBytesRemaining, availIn, availOut)

                            System.arraycopy(inBuf, inPos, outScratch, outPos, step)

                            // Update 32 KB sliding window
                            var remWin = step
                            var srcOff = inPos
                            while (remWin > 0) {
                                val winSpace = (WINDOW_SIZE - winPos).coerceAtMost(remWin)
                                System.arraycopy(inBuf, srcOff, window, winPos, winSpace)
                                winPos = (winPos + winSpace) and WINDOW_MASK
                                winFilled = (winFilled + winSpace).coerceAtMost(WINDOW_SIZE)
                                srcOff += winSpace
                                remWin -= winSpace
                            }

                            inPos += step
                            outPos += step
                            storedBytesRemaining -= step
                            if (outPos == OUT_SCRATCH_SIZE) flushScratch()
                        }

                        if (storedBytesRemaining == 0) {
                            blockMode = if (isFinalBlock) MODE_FINISHED else MODE_BLOCK_HEADER
                        } else {
                            // Need more input bytes in the next chunk
                            if (isLastChunk) {
                                throw DataFormatException(
                                    "Truncated stored block: still waiting for $storedBytesRemaining bytes at EOF"
                                )
                            }
                            break@mainLoop
                        }
                    }

                    MODE_HUFFMAN_BODY -> {
                        val lTable = litLenTable
                        val lMask = litLenMask
                        val dTable = distTable
                        val dMask = distMask

                        huffmanLoop@ while (true) {
                            if (!isLastChunk && (inLimit - inPos) + (bCnt ushr 3) < SAFE_SYMBOL_MARGIN_BYTES) {
                                break@mainLoop
                            }

                            while (bCnt <= 56 && inPos < inLimit) {
                                bBuf = bBuf or ((inBuf[inPos++].toLong() and 0xFFL) shl bCnt)
                                bCnt += 8
                            }

                            val entry = lTable[bBuf.toInt() and lMask]
                            if (entry == 0) {
                                throw DataFormatException("Invalid literal/length Huffman codeword")
                            }
                            val codeBits = entry and 0xFF
                            if (bCnt < codeBits) {
                                refillBits(codeBits)
                            }
                            bBuf = bBuf ushr codeBits
                            bCnt -= codeBits
                            val sym = (entry ushr 8) - 1

                            if (sym < 256) {
                                // Literal byte
                                val b = sym.toByte()
                                window[winPos] = b
                                winPos = (winPos + 1) and WINDOW_MASK
                                if (winFilled < WINDOW_SIZE) winFilled++
                                outScratch[outPos++] = b
                                if (outPos == OUT_SCRATCH_SIZE) flushScratch()
                            } else if (sym == 256) {
                                // End of block (EOB)
                                blockMode = if (isFinalBlock) MODE_FINISHED else MODE_BLOCK_HEADER
                                break@huffmanLoop
                            } else if (sym <= 285) {
                                // Length-distance LZ77 match
                                val lenIdx = sym - 257
                                val extraLenBits = LENGTH_EXTRA_BITS[lenIdx]
                                if (bCnt < extraLenBits + 28 && inPos < inLimit) {
                                    while (bCnt <= 56 && inPos < inLimit) {
                                        bBuf = bBuf or ((inBuf[inPos++].toLong() and 0xFFL) shl bCnt)
                                        bCnt += 8
                                    }
                                }
                                var matchLen = LENGTH_BASE[lenIdx]
                                if (extraLenBits > 0) {
                                    if (bCnt < extraLenBits) refillBits(extraLenBits)
                                    matchLen += (bBuf and ((1L shl extraLenBits) - 1L)).toInt()
                                    bBuf = bBuf ushr extraLenBits
                                    bCnt -= extraLenBits
                                }

                                if (!distTableHasCodes) {
                                    throw DataFormatException("Distance symbol encountered in block with empty distance tree")
                                }
                                val dEntry = dTable[bBuf.toInt() and dMask]
                                if (dEntry == 0) {
                                    throw DataFormatException("Invalid distance Huffman codeword")
                                }
                                val dBits = dEntry and 0xFF
                                if (bCnt < dBits) refillBits(dBits)
                                bBuf = bBuf ushr dBits
                                bCnt -= dBits
                                val distSym = (dEntry ushr 8) - 1
                                if (distSym >= 30) {
                                    throw DataFormatException("Illegal reserved distance symbol: $distSym")
                                }

                                val extraDistBits = DIST_EXTRA_BITS[distSym]
                                var distance = DIST_BASE[distSym]
                                if (extraDistBits > 0) {
                                    if (bCnt < extraDistBits) refillBits(extraDistBits)
                                    distance += (bBuf and ((1L shl extraDistBits) - 1L)).toInt()
                                    bBuf = bBuf ushr extraDistBits
                                    bCnt -= extraDistBits
                                }

                                if (distance > winFilled) {
                                    throw DataFormatException(
                                        "Invalid LZ77 distance ($distance) exceeds available history ($winFilled bytes)"
                                    )
                                }

                                // Copy `matchLen` bytes from `winPos - distance` in circular 32 KB window
                                if (outPos + matchLen > OUT_SCRATCH_SIZE) {
                                    flushScratch()
                                }
                                var copySrc = (winPos - distance) and WINDOW_MASK
                                if (distance >= matchLen &&
                                    copySrc + matchLen <= WINDOW_SIZE &&
                                    winPos + matchLen <= WINDOW_SIZE
                                ) {
                                    System.arraycopy(window, copySrc, window, winPos, matchLen)
                                    System.arraycopy(window, winPos, outScratch, outPos, matchLen)
                                    winPos = (winPos + matchLen) and WINDOW_MASK
                                    winFilled = (winFilled + matchLen).coerceAtMost(WINDOW_SIZE)
                                    outPos += matchLen
                                } else {
                                    for (m in 0 until matchLen) {
                                        val b = window[copySrc]
                                        copySrc = (copySrc + 1) and WINDOW_MASK
                                        window[winPos] = b
                                        winPos = (winPos + 1) and WINDOW_MASK
                                        outScratch[outPos++] = b
                                    }
                                    winFilled = (winFilled + matchLen).coerceAtMost(WINDOW_SIZE)
                                }
                                if (outPos == OUT_SCRATCH_SIZE) {
                                    flushScratch()
                                }
                            } else {
                                throw DataFormatException("Illegal literal/length symbol: $sym")
                            }
                        }
                    }
                }
            }
        } finally {
            flushScratch()
            bitBuffer = bBuf
            bitCount = bCnt
            windowPos = winPos
            windowFilled = winFilled
        }

        // Preserve any unconsumed tail bytes in `pendingInput` if stream is not finished
        val remainingInBuf = inLimit - inPos
        if (blockMode != MODE_FINISHED && remainingInBuf > 0) {
            pendingInput = ByteArray(remainingInBuf)
            System.arraycopy(inBuf, inPos, pendingInput, 0, remainingInBuf)
        } else {
            pendingInput = ByteArray(0)
        }

        // Advance total compressed stream position by the chunk length supplied in this call
        compressedBytesConsumed += length.toLong()

        if (isLastChunk && blockMode != MODE_FINISHED) {
            throw DataFormatException(
                "Truncated DEFLATE stream: reached end of final chunk in mode=$blockMode without final EOB"
            )
        }

        return producedThisCall
    }

    /**
     * Captures the complete internal state into an immutable [CheckpointData] snapshot.
     */
    fun captureCheckpoint(): CheckpointData {
        return CheckpointData(
            compressedBytesConsumed = compressedBytesConsumed,
            outputBytesWritten = outputBytesWritten,
            runningCrc32 = runningCrc.value,
            blockMode = blockMode,
            isFinalBlock = isFinalBlock,
            activeBlockType = activeBlockType,
            storedBytesRemaining = storedBytesRemaining,
            bitBuffer = bitBuffer,
            bitCount = bitCount,
            litLenCodeLengths = litLenCodeLengths.copyOf(),
            distCodeLengths = distCodeLengths.copyOf(),
            pendingInput = pendingInput.copyOf(),
            windowPos = windowPos,
            windowFilled = windowFilled,
            window = window.copyOf()
        )
    }

    /**
     * Serializes the current decoder checkpoint into a self-verifying byte array protected by a
     * trailing 8-byte CRC32 checksum.
     */
    fun saveCheckpointBytes(): ByteArray {
        val baos = ByteArrayOutputStream(36 * 1024)
        writeCheckpointToStream(baos)
        return baos.toByteArray()
    }

    /**
     * Atomically writes and `fsync`s the current decoder checkpoint to [checkpointFile].
     */
    fun saveCheckpointToFile(checkpointFile: File) {
        checkpointFile.parentFile?.mkdirs()
        val tmpFile = File(checkpointFile.parentFile, "${checkpointFile.name}.tmp")
        FileOutputStream(tmpFile).use { fos ->
            BufferedOutputStream(fos, 36 * 1024).use { bos ->
                writeCheckpointToStream(bos)
                bos.flush()
            }
            try {
                fos.fd.sync()
            } catch (_: Throwable) {
            }
        }
        if (checkpointFile.exists()) checkpointFile.delete()
        if (!tmpFile.renameTo(checkpointFile)) {
            throw InvalidCheckpointException("Failed to rename temporary checkpoint file to ${checkpointFile.absolutePath}")
        }
    }

    private fun writeCheckpointToStream(rawOut: OutputStream) {
        val crc = CRC32()
        val cos = CheckedOutputStream(rawOut, crc)
        val dos = DataOutputStream(cos)

        dos.writeInt(CHECKPOINT_MAGIC)
        dos.writeInt(CHECKPOINT_VERSION)
        dos.writeLong(compressedBytesConsumed)
        dos.writeLong(outputBytesWritten)
        dos.writeLong(runningCrc.value)
        dos.writeInt(blockMode)
        dos.writeBoolean(isFinalBlock)
        dos.writeInt(activeBlockType)
        dos.writeInt(storedBytesRemaining)
        dos.writeLong(bitBuffer)
        dos.writeInt(bitCount)

        dos.write(litLenCodeLengths)
        dos.write(distCodeLengths)

        dos.writeInt(pendingInput.size)
        if (pendingInput.isNotEmpty()) {
            dos.write(pendingInput)
        }

        dos.writeInt(windowPos)
        dos.writeInt(windowFilled)
        dos.write(window)
        dos.flush()

        // Write 8-byte CRC32 footer directly to rawOut
        val footerDos = DataOutputStream(rawOut)
        footerDos.writeLong(crc.value)
        footerDos.flush()
    }

    /**
     * Restores the complete decoder state from a serialized checkpoint byte array.
     * Validates the trailing CRC32 checksum and all structural & Huffman invariants.
     *
     * @throws InvalidCheckpointException if the checkpoint is truncated, corrupted, or invalid.
     */
    fun restoreFromCheckpointBytes(bytes: ByteArray) {
        if (bytes.size < MIN_CHECKPOINT_BYTES) {
            throw InvalidCheckpointException(
                "Checkpoint truncated or too small: ${bytes.size} bytes (expected >= $MIN_CHECKPOINT_BYTES)"
            )
        }
        val payloadLen = bytes.size - 8
        val crc = CRC32()
        crc.update(bytes, 0, payloadLen)
        val actualCrc = crc.value

        val footerDis = DataInputStream(ByteArrayInputStream(bytes, payloadLen, 8))
        val expectedCrc = footerDis.readLong()
        if (actualCrc != expectedCrc) {
            throw InvalidCheckpointException(
                "Checkpoint CRC32 mismatch: expected 0x${java.lang.Long.toHexString(expectedCrc)}, got 0x${java.lang.Long.toHexString(actualCrc)}"
            )
        }

        val dis = DataInputStream(ByteArrayInputStream(bytes, 0, payloadLen))
        restoreFromDataInput(dis, payloadLen)
    }

    /**
     * Restores the complete decoder state from [checkpointFile], optionally verifying that
     * [committedOutputFile] exists and contains at least `outputBytesWritten` bytes.
     *
     * @throws InvalidCheckpointException if the file is missing, corrupted, or inconsistent with output.
     */
    fun restoreFromCheckpointFile(checkpointFile: File, committedOutputFile: File? = null) {
        if (!checkpointFile.exists() || !checkpointFile.isFile) {
            throw InvalidCheckpointException("Checkpoint file does not exist: ${checkpointFile.absolutePath}")
        }
        val len = checkpointFile.length()
        if (len < MIN_CHECKPOINT_BYTES || len > MAX_CHECKPOINT_BYTES) {
            throw InvalidCheckpointException("Invalid checkpoint file length: $len bytes")
        }
        val bytes = checkpointFile.readBytes()
        restoreFromCheckpointBytes(bytes)

        if (committedOutputFile != null) {
            if (!committedOutputFile.exists()) {
                throw InvalidCheckpointException("Committed output file missing: ${committedOutputFile.absolutePath}")
            }
            if (committedOutputFile.length() < outputBytesWritten) {
                throw InvalidCheckpointException(
                    "Committed output file length (${committedOutputFile.length()}) is smaller than checkpoint outputBytesWritten ($outputBytesWritten)"
                )
            }
        }
    }

    private fun restoreFromDataInput(dis: DataInputStream, payloadLen: Int) {
        try {
            val magic = dis.readInt()
            if (magic != CHECKPOINT_MAGIC) {
                throw InvalidCheckpointException("Invalid checkpoint magic: 0x${Integer.toHexString(magic)}")
            }
            val version = dis.readInt()
            if (version != CHECKPOINT_VERSION) {
                throw InvalidCheckpointException("Unsupported checkpoint version: $version")
            }

            val savedCompressedConsumed = dis.readLong()
            val savedOutputWritten = dis.readLong()
            val savedCrc32 = dis.readLong()
            val savedBlockMode = dis.readInt()
            val savedIsFinal = dis.readBoolean()
            val savedBlockType = dis.readInt()
            val savedStoredRem = dis.readInt()
            val savedBitBuf = dis.readLong()
            val savedBitCount = dis.readInt()

            if (savedCompressedConsumed < 0L || savedOutputWritten < 0L) {
                throw InvalidCheckpointException("Negative byte counters in checkpoint")
            }
            if (savedCrc32 !in 0L..0xFFFFFFFFL) {
                throw InvalidCheckpointException("Invalid CRC32 value in checkpoint: $savedCrc32")
            }
            if (savedBlockMode !in MODE_BLOCK_HEADER..MODE_FINISHED) {
                throw InvalidCheckpointException("Invalid blockMode in checkpoint: $savedBlockMode")
            }
            if (savedBlockType !in 0..2) {
                throw InvalidCheckpointException("Invalid activeBlockType in checkpoint: $savedBlockType")
            }
            if (savedStoredRem !in 0..65535) {
                throw InvalidCheckpointException("Invalid storedBytesRemaining in checkpoint: $savedStoredRem")
            }
            if (savedBlockMode == MODE_STORED_BODY && (savedBlockType != 0 || savedStoredRem == 0)) {
                throw InvalidCheckpointException("Inconsistent MODE_STORED_BODY state in checkpoint")
            }
            if (savedBlockMode == MODE_HUFFMAN_BODY && savedBlockType !in 1..2) {
                throw InvalidCheckpointException("Inconsistent MODE_HUFFMAN_BODY blockType=$savedBlockType")
            }
            if (savedBitCount !in 0..64) {
                throw InvalidCheckpointException("Invalid bitCount in checkpoint: $savedBitCount")
            }

            val savedLitLenLengths = ByteArray(MAX_LIT_LEN_CODES)
            dis.readFully(savedLitLenLengths)
            val savedDistLengths = ByteArray(MAX_DIST_CODES)
            dis.readFully(savedDistLengths)

            for (b in savedLitLenLengths) {
                if (b.toInt() !in 0..MAX_HUFFMAN_BITS) {
                    throw InvalidCheckpointException("Out-of-range literal/length Huffman code length: ${b.toInt()}")
                }
            }
            for (b in savedDistLengths) {
                if (b.toInt() !in 0..MAX_HUFFMAN_BITS) {
                    throw InvalidCheckpointException("Out-of-range distance Huffman code length: ${b.toInt()}")
                }
            }

            val pendingLen = dis.readInt()
            if (pendingLen < 0 || pendingLen > MAX_PENDING_INPUT_BYTES || pendingLen > payloadLen) {
                throw InvalidCheckpointException("Invalid pendingInput length in checkpoint: $pendingLen")
            }
            val savedPending = ByteArray(pendingLen)
            if (pendingLen > 0) {
                dis.readFully(savedPending)
            }

            val savedWinPos = dis.readInt()
            val savedWinFilled = dis.readInt()
            if (savedWinPos !in 0 until WINDOW_SIZE) {
                throw InvalidCheckpointException("Invalid windowPos in checkpoint: $savedWinPos")
            }
            if (savedWinFilled !in 0..WINDOW_SIZE) {
                throw InvalidCheckpointException("Invalid windowFilled in checkpoint: $savedWinFilled")
            }
            if (savedOutputWritten == 0L && savedWinFilled != 0) {
                throw InvalidCheckpointException("Inconsistent windowFilled=$savedWinFilled when outputBytesWritten=0")
            }
            if (savedOutputWritten < savedWinFilled.toLong()) {
                throw InvalidCheckpointException(
                    "Inconsistent windowFilled ($savedWinFilled) > outputBytesWritten ($savedOutputWritten)"
                )
            }

            val savedWindow = ByteArray(WINDOW_SIZE)
            dis.readFully(savedWindow)

            // Rebuild and validate Huffman tables before mutating live state
            val newLitLenTable = IntArray(1 shl MAX_HUFFMAN_BITS)
            val newDistTable = IntArray(1 shl MAX_HUFFMAN_BITS)
            var newLitLenMask = 0
            var newDistMask = 0
            var newDistHasCodes = false

            if (savedBlockMode == MODE_HUFFMAN_BODY) {
                if (savedBlockType == 1) {
                    setupFixedCodeLengths(savedLitLenLengths, savedDistLengths)
                } else if (savedLitLenLengths[256].toInt() == 0) {
                    throw InvalidCheckpointException("Restored dynamic Huffman table missing EOB symbol (256)")
                }
                newLitLenMask = buildHuffmanTable(
                    codeLengths = savedLitLenLengths,
                    numSymbols = MAX_LIT_LEN_CODES,
                    maxAllowedBits = MAX_HUFFMAN_BITS,
                    table = newLitLenTable,
                    allowEmpty = false
                )
                newDistMask = buildHuffmanTable(
                    codeLengths = savedDistLengths,
                    numSymbols = MAX_DIST_CODES,
                    maxAllowedBits = MAX_HUFFMAN_BITS,
                    table = newDistTable,
                    allowEmpty = true
                )
                newDistHasCodes = savedDistLengths.any { it.toInt() != 0 }
            }

            // Commit validated state
            compressedBytesConsumed = savedCompressedConsumed
            outputBytesWritten = savedOutputWritten
            runningCrc.restoreValue(savedCrc32)
            blockMode = savedBlockMode
            isFinalBlock = savedIsFinal
            activeBlockType = savedBlockType
            storedBytesRemaining = savedStoredRem
            bitBuffer = savedBitBuf
            bitCount = savedBitCount
            System.arraycopy(savedLitLenLengths, 0, litLenCodeLengths, 0, MAX_LIT_LEN_CODES)
            System.arraycopy(savedDistLengths, 0, distCodeLengths, 0, MAX_DIST_CODES)
            System.arraycopy(newLitLenTable, 0, litLenTable, 0, newLitLenTable.size)
            System.arraycopy(newDistTable, 0, distTable, 0, newDistTable.size)
            litLenMask = newLitLenMask
            distMask = newDistMask
            distTableHasCodes = newDistHasCodes
            pendingInput = savedPending
            windowPos = savedWinPos
            windowFilled = savedWinFilled
            System.arraycopy(savedWindow, 0, window, 0, WINDOW_SIZE)
        } catch (e: InvalidCheckpointException) {
            throw e
        } catch (e: DataFormatException) {
            throw InvalidCheckpointException("Invalid Huffman table in checkpoint: ${e.message}", e)
        } catch (e: Throwable) {
            throw InvalidCheckpointException("Malformed checkpoint stream: ${e.message}", e)
        }
    }

    private fun setupFixedHuffmanTables() {
        setupFixedCodeLengths(litLenCodeLengths, distCodeLengths)
        litLenMask = buildHuffmanTable(
            codeLengths = litLenCodeLengths,
            numSymbols = MAX_LIT_LEN_CODES,
            maxAllowedBits = 9,
            table = litLenTable,
            allowEmpty = false
        )
        distMask = buildHuffmanTable(
            codeLengths = distCodeLengths,
            numSymbols = MAX_DIST_CODES,
            maxAllowedBits = 5,
            table = distTable,
            allowEmpty = false
        )
        distTableHasCodes = true
    }

    private fun setupFixedCodeLengths(litLengths: ByteArray, dstLengths: ByteArray) {
        for (i in 0..143) litLengths[i] = 8
        for (i in 144..255) litLengths[i] = 9
        for (i in 256..279) litLengths[i] = 7
        for (i in 280..287) litLengths[i] = 8
        for (i in 0..31) dstLengths[i] = 5
    }

    /**
     * Builds a flat 1-step canonical Huffman lookup table (`table[bitBuf and mask]`) and validates
     * Kraft's inequality so oversubscribed/corrupted Huffman trees are rejected immediately.
     *
     * @return The bitmask `(1 shl maxLen) - 1` for indexing [table].
     */
    private fun buildHuffmanTable(
        codeLengths: ByteArray,
        numSymbols: Int,
        maxAllowedBits: Int,
        table: IntArray,
        allowEmpty: Boolean
    ): Int {
        val blCount = IntArray(maxAllowedBits + 1)
        var maxLen = 0
        var nonZeroCount = 0

        for (i in 0 until numSymbols) {
            val len = codeLengths[i].toInt()
            if (len < 0 || len > maxAllowedBits) {
                throw DataFormatException("Huffman code length $len exceeds maxAllowedBits=$maxAllowedBits")
            }
            if (len > 0) {
                blCount[len]++
                nonZeroCount++
                if (len > maxLen) maxLen = len
            }
        }

        if (nonZeroCount == 0) {
            if (!allowEmpty) {
                throw DataFormatException("Empty Huffman table not permitted")
            }
            table[0] = 0
            return 0
        }

        // Validate Kraft's inequality: check for oversubscribed trees
        var left = 1
        for (bits in 1..maxAllowedBits) {
            left = (left shl 1) - blCount[bits]
            if (left < 0) {
                throw DataFormatException("Oversubscribed Huffman tree at bit length $bits")
            }
        }
        // In RFC 1951, an incomplete tree is only valid for the single-symbol edge case
        // (or distance/code-length tables with 1 code of length 1).
        if (left > 0 && nonZeroCount > 1 && maxAllowedBits == MAX_HUFFMAN_BITS && numSymbols == MAX_LIT_LEN_CODES) {
            throw DataFormatException("Incomplete literal/length Huffman tree (left=$left)")
        }

        val nextCode = IntArray(maxAllowedBits + 1)
        var code = 0
        for (bits in 1..maxAllowedBits) {
            code = (code + blCount[bits - 1]) shl 1
            nextCode[bits] = code
        }

        val tableSize = 1 shl maxLen
        table.fill(0, 0, tableSize)

        for (sym in 0 until numSymbols) {
            val len = codeLengths[sym].toInt()
            if (len == 0) continue
            val cw = nextCode[len]++
            // Reverse the `len` bits of `cw` for LSB-first lookup
            var rev = 0
            var tmp = cw
            for (b in 0 until len) {
                rev = (rev shl 1) or (tmp and 1)
                tmp = tmp ushr 1
            }
            val entry = ((sym + 1) shl 8) or len
            val step = 1 shl len
            var idx = rev
            while (idx < tableSize) {
                table[idx] = entry
                idx += step
            }
        }

        return tableSize - 1
    }

    companion object {
        const val CHECKPOINT_MAGIC = 0x4B484446 // "KHDF"
        const val CHECKPOINT_VERSION = 1

        const val MODE_BLOCK_HEADER = 0
        const val MODE_STORED_BODY = 1
        const val MODE_HUFFMAN_BODY = 2
        const val MODE_FINISHED = 3

        const val WINDOW_SIZE = 32768
        private const val WINDOW_MASK = WINDOW_SIZE - 1
        private const val OUT_SCRATCH_SIZE = 65536

        private const val MAX_HUFFMAN_BITS = 15
        private const val MAX_LIT_LEN_CODES = 288
        private const val MAX_DIST_CODES = 32

        // Safe margins before non-final chunk ends so we never pause mid-header or mid-symbol
        const val SAFE_HEADER_MARGIN_BYTES = 384
        const val SAFE_SYMBOL_MARGIN_BYTES = 16
        private const val MAX_PENDING_INPUT_BYTES = 65536

        private const val MIN_CHECKPOINT_BYTES = 4 + 4 + 8 + 8 + 8 + 4 + 1 + 4 + 4 + 8 + 4 +
                MAX_LIT_LEN_CODES + MAX_DIST_CODES + 4 + 4 + 4 + WINDOW_SIZE + 8
        private const val MAX_CHECKPOINT_BYTES = MIN_CHECKPOINT_BYTES + MAX_PENDING_INPUT_BYTES + 1024

        // RFC 1951 Section 3.2.7: permutation of code-length alphabet
        private val CODE_LENGTH_ORDER = intArrayOf(
            16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15
        )

        // RFC 1951 Section 3.2.5: Length base values and extra bits for symbols 257..285
        private val LENGTH_BASE = intArrayOf(
            3, 4, 5, 6, 7, 8, 9, 10,
            11, 13, 15, 17, 19, 23, 27, 31,
            35, 43, 51, 59, 67, 83, 99, 115,
            131, 163, 195, 227, 258
        )
        private val LENGTH_EXTRA_BITS = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 2, 2, 2, 2,
            3, 3, 3, 3, 4, 4, 4, 4,
            5, 5, 5, 5, 0
        )

        // RFC 1951 Section 3.2.5: Distance base values and extra bits for symbols 0..29
        private val DIST_BASE = intArrayOf(
            1, 2, 3, 4, 5, 7, 9, 13,
            17, 25, 33, 49, 65, 97, 129, 193,
            257, 385, 513, 769, 1025, 1537, 2049, 3073,
            4097, 6145, 8193, 12289, 16385, 24577
        )
        private val DIST_EXTRA_BITS = intArrayOf(
            0, 0, 0, 0, 1, 1, 2, 2,
            3, 3, 4, 4, 5, 5, 6, 6,
            7, 7, 8, 8, 9, 9, 10, 10,
            11, 11, 12, 12, 13, 13
        )
    }
}
