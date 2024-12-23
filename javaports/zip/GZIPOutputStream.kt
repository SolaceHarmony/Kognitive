/*
 * Copyright (c) 1996, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package ai.solace.core.kognitive.utils.ports.zip

import java.io.IOException

/**
 * This class implements a stream filter for writing compressed data in
 * the GZIP file format.
 * <p> Unless otherwise noted, passing a {@code null} argument to a constructor
 * or method in this class will cause a {@link NullPointerException} to be
 * thrown.
 *
 * @author      David Connelly
 * @since 1.1
 *
 */
open class GZIPOutputStream : DeflaterOutputStream {
    /**
     * CRC-32 of uncompressed data.
     */
    protected var crc: CRC32 = CRC32()

    /*
     * GZIP header magic number.
     */
    private val GZIP_MAGIC = 0x8b1f

    /*
     * Trailer size in bytes.
     *
     */
    private val TRAILER_SIZE = 8

    // Represents the default "unknown" value for OS header, per RFC-1952
    private val OS_UNKNOWN: Byte = 255.toByte()

    /**
     * Creates a new output stream with the specified buffer size.
     *
     * <p>The new output stream instance is created as if by invoking
     * the 3-argument constructor GZIPOutputStream(out, size, false).
     *
     * @param out the output stream
     * @param size the output buffer size
     * @throws    IOException If an I/O error has occurred.
     * @throws    IllegalArgumentException if {@code size <= 0}
     */
    constructor(out: OutputStream, size: Int) : this(out, size, false)

    /**
     * Creates a new output stream with the specified buffer size and
     * flush mode.
     *
     * @param out the output stream
     * @param size the output buffer size
     * @param syncFlush
     *        if {@code true} invocation of the inherited
     *        {@link DeflaterOutputStream#flush() flush()} method of
     *        this instance flushes the compressor with flush mode
     *        {@link Deflater#SYNC_FLUSH} before flushing the output
     *        stream, otherwise only flushes the output stream
     * @throws    IOException If an I/O error has occurred.
     * @throws    IllegalArgumentException if {@code size <= 0}
     *
     * @since 1.7
     */
    constructor(out: OutputStream, size: Int, syncFlush: Boolean) : super(
        out,
        if (out != null) Deflater(Deflater.DEFAULT_COMPRESSION, true) else null,
        size,
        syncFlush
    ) {
        usesDefaultDeflater = true
        writeHeader()
        crc.reset()
    }

    /**
     * Creates a new output stream with a default buffer size.
     *
     * <p>The new output stream instance is created as if by invoking
     * the 2-argument constructor GZIPOutputStream(out, false).
     *
     * @param out the output stream
     * @throws    IOException If an I/O error has occurred.
     */
    constructor(out: OutputStream) : this(out, 512, false)

    /**
     * Creates a new output stream with a default buffer size and
     * the specified flush mode.
     *
     * @param out the output stream
     * @param syncFlush
     *        if {@code true} invocation of the inherited
     *        {@link DeflaterOutputStream#flush() flush()} method of
     *        this instance flushes the compressor with flush mode
     *        {@link Deflater#SYNC_FLUSH} before flushing the output
     *        stream, otherwise only flushes the output stream
     *
     * @throws    IOException If an I/O error has occurred.
     *
     * @since 1.7
     */
    constructor(out: OutputStream, syncFlush: Boolean) : this(out, 512, syncFlush)

    /**
     * Writes array of bytes to the compressed output stream. This method
     * will block until all the bytes are written.
     * @param buf the data to be written
     * @param off the start offset of the data
     * @param len the length of the data
     * @throws    IOException If an I/O error has occurred.
     */
    @Synchronized
    @Throws(IOException::class)
    override fun write(buf: ByteArray, off: Int, len: Int) {
        super.write(buf, off, len)
        crc.update(buf, off, len)
    }

    /**
     * Finishes writing compressed data to the output stream without closing
     * the underlying stream. Use this method when applying multiple filters
     * in succession to the same output stream.
     * @throws    IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    override fun finish() {
        if (!def.finished()) {
            try {
                def.finish()
                while (!def.finished()) {
                    var len = def.deflate(buf, 0, buf.size)
                    if (def.finished() && len <= buf.size - TRAILER_SIZE) {
                        // last deflater buffer. Fit trailer at the end
                        writeTrailer(buf, len)
                        len += TRAILER_SIZE
                        out.write(buf, 0, len)
                        return
                    }
                    if (len > 0) out.write(buf, 0, len)
                }
                // if we can't fit the trailer at the end of the last
                // deflater buffer, we write it separately
                val trailer = ByteArray(TRAILER_SIZE)
                writeTrailer(trailer, 0)
                out.write(trailer)
            } catch (e: IOException) {
                if (usesDefaultDeflater) def.end()
                throw e
            }
        }
    }

    /*
     * Writes GZIP member header.
     */
    @Throws(IOException::class)
    private fun writeHeader() {
        out.write(
            byteArrayOf(
                GZIP_MAGIC.toByte(),        // Magic number (short)
                (GZIP_MAGIC shr 8).toByte(),  // Magic number (short)
                Deflater.DEFLATED.toByte(),        // Compression method (CM)
                0,                        // Flags (FLG)
                0,                        // Modification time MTIME (int)
                0,                        // Modification time MTIME (int)
                0,                        // Modification time MTIME (int)
                0,                        // Modification time MTIME (int)
                0,                        // Extra flags (XFLG)
                OS_UNKNOWN                // Operating system (OS)
            )
        )
    }

    /*
     * Writes GZIP member trailer to a byte array, starting at a given
     * offset.
     */
    @Throws(IOException::class)
    private fun writeTrailer(buf: ByteArray, offset: Int) {
        writeInt(crc.value.toInt(), buf, offset) // CRC-32 of uncompr. data
        // RFC 1952: Size of the original (uncompressed) input data modulo 2^32
        val iSize = def.bytesRead.toInt()
        writeInt(iSize, buf, offset + 4)
    }

    /*
     * Writes integer in Intel byte order to a byte array, starting at a
     * given offset.
     */
    @Throws(IOException::class)
    private fun writeInt(i: Int, buf: ByteArray, offset: Int) {
        writeShort(i and 0xffff, buf, offset)
        writeShort(i shr 16 and 0xffff, buf, offset + 2)
    }

    /*
     * Writes short integer in Intel byte order to a byte array, starting
     * at a given offset
     */
    @Throws(IOException::class)
    private fun writeShort(s: Int, buf: ByteArray, offset: Int) {
        buf[offset] = (s and 0xff).toByte()
        buf[offset + 1] = (s shr 8 and 0xff).toByte()
    }
}