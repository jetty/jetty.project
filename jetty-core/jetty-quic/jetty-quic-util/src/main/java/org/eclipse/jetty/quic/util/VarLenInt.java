//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.util;

import java.nio.ByteBuffer;
import java.util.function.LongConsumer;

import org.eclipse.jetty.io.RetainableByteBuffer;

/**
 * <p>Encodes and decodes {@code long} values as specified by
 * <a href="https://datatracker.ietf.org/doc/html/rfc9000#section-16">QUIC</a>.</p>
 */
public class VarLenInt
{
    /**
     * The max value supported by this variable-length codec, 2^62-1.
     */
    public static final long MAX_VALUE = (1L << 62) - 1;
    /**
     * The max number of bytes used to encode {@code long} values.
     */
    public static final int MAX_LENGTH = 8;

    private static final int ENCODING_MASK = 0b11_00_00_00;
    private static final int VALUE_MASK = 0b00_11_11_11;

    private int encoding = -1;
    private int length;
    private long value;

    /**
     * <p>Tries to decode a variable-length {@code long} from the given {@code ByteBuffer}.</p>
     * <p>If there are enough bytes to decode the {@code long} value, the given {@code Consumer}
     * is invoked with the value, and this method returns {@code true}.
     * Otherwise, there are not enough bytes to decode the {@code long} value, and this method
     * returns {@code false}.</p>
     *
     * @param byteBuffer the {@code ByteBuffer} to decode from
     * @param consumer the {@link LongConsumer} to invoke when the decoding is complete
     * @return whether the decoding was complete
     */
    public boolean tryDecode(ByteBuffer byteBuffer, LongConsumer consumer)
    {
        return tryDecode(byteBuffer, (l, v) -> consumer.accept(v));
    }

    /**
     * <p>Tries to decode a variable-length {@code long} from the given {@code ByteBuffer}.</p>
     * <p>If there are enough bytes to decode the {@code long} value, the given {@code IntLongConsumer}
     * is invoked with the number of bytes consumed and the value, and this method returns {@code true}.
     * Otherwise, there are not enough bytes to decode the {@code long} value, and this method
     * returns {@code false}.</p>
     *
     * @param byteBuffer the {@code ByteBuffer} to decode from
     * @param consumer the {@link LongConsumer} to invoke when the decoding is complete
     * @return whether the decoding was complete
     */
    public boolean tryDecode(ByteBuffer byteBuffer, IntLongConsumer consumer)
    {
        while (byteBuffer.hasRemaining())
        {
            if (encoding < 0)
            {
                encoding = lengthEncoding(byteBuffer);
                length = 1 << encoding;
                // Start with the hi byte.
                value = byteBuffer.get() & VALUE_MASK;
                if (--length == 0)
                    return result(consumer);
            }
            else
            {
                if (length > 0)
                {
                    // Shift the value to the left for every byte.
                    value = accumulateValue(value, byteBuffer.get());
                    if (--length == 0)
                        return result(consumer);
                }
                else
                {
                    throw new IllegalStateException();
                }
            }
        }
        return false;
    }

    private boolean result(IntLongConsumer consumer)
    {
        long result = value;
        int resultLength = 1 << encoding;
        encoding = -1;
        length = 0;
        value = 0;
        consumer.accept(resultLength, result);
        return true;
    }

    /**
     * <p>Decodes an {@code int} value from the given {@code ByteBuffer}.</p>
     *
     * @param byteBuffer the {@code ByteBuffer} to decode from
     * @return the decoded {@code int} value
     */
    public static int decodeInt(ByteBuffer byteBuffer)
    {
        return (int)decode(byteBuffer, true);
    }

    /**
     * <p>Decodes a {@code long} value from the given {@code ByteBuffer}.</p>
     *
     * @param byteBuffer the {@code ByteBuffer} to decode from
     * @return the decoded {@code long} value
     */
    public static long decodeLong(ByteBuffer byteBuffer)
    {
        return decode(byteBuffer, false);
    }

    private static long decode(ByteBuffer buffer, boolean asInt)
    {
        int encoding = lengthEncoding(buffer);
        if (asInt && encoding == 3)
            throw new QuicException(ErrorCode.PROTOCOL_VIOLATION_ERROR, "invalid_variable_length_integer");
        int length = 1 << encoding;
        // Start with the hi byte and shift the value to the left in every iteration.
        long value = buffer.get() & VALUE_MASK;
        for (int i = 1; i < length; ++i)
        {
            value = accumulateValue(value, buffer.get());
        }
        return value;
    }

    /**
     * <p>Returns {@code 0} for 1-byte encoding, {@code 1} for 2-byte encoding,
     * {@code 2} for 4-byte encoding and {@code 3} for 8-byte encoding.</p>
     *
     * @param byteBuffer the {@link ByteBuffer} to read the two most significant bits
     * of the byte at the current position to determine the variable length encoding
     * @return the base-2 logarithm of the N-byte variable length encoding
     */
    private static int lengthEncoding(ByteBuffer byteBuffer)
    {
        // The first byte is the most significant, and therefore the
        // one that holds the encoding in the 2 most significant bits.
        byte hiByte = byteBuffer.get(byteBuffer.position());
        return (hiByte & ENCODING_MASK) >>> 6;
    }

    private static long accumulateValue(long value, byte bite)
    {
        return (value << 8) + (bite & 0xFF);
    }

    /**
     * <p>Variable-length encodes the given {@code long} value into the given {@code ByteBuffer},
     * starting at its current position.</p>
     *
     * @param byteBuffer the {@code ByteBuffer} to encode into
     * @param value the {@code long} value to encode
     */
    public static void encode(ByteBuffer byteBuffer, long value)
    {
        int length = length(value);
        int encoding = 31 - Integer.numberOfLeadingZeros(length);
        // Put the least significant bytes first, and proceed
        // backwards by shifting the value to the right until the
        // most significant byte, that also stores the encoding.
        int position = byteBuffer.position();
        for (int i = length - 1; i > 0; --i)
        {
            byteBuffer.put(position + i, (byte)(value & 0xFF));
            value = value >>> Byte.SIZE;
        }
        byteBuffer.put(position, (byte)((value & VALUE_MASK) | (encoding << 6)));
        byteBuffer.position(position + length);
    }

    /**
     * <p>Variable-length encodes the given {@code long} value into the given
     * {@code RetainableByteBuffer.Mutable}.</p>
     *
     * @param buffer the {@code RetainableByteBuffer.Mutable} to encode into
     * @param value the {@code long} value to encode
     */
    public static void encode(RetainableByteBuffer.Mutable buffer, long value)
    {
        int length = length(value);
        int encoding = 31 - Integer.numberOfLeadingZeros(length);
        // Shift the value and work only on the least significant byte by masking.
        int shift = (length - 1) * Byte.SIZE;
        byte msb = (byte)(((value >>> shift) & VALUE_MASK) | (encoding << 6));
        buffer.put(msb);
        while (shift > 0)
        {
            shift -= Byte.SIZE;
            buffer.put((byte)((value >>> shift) & 0xFF));
        }
    }

    /**
     * <p>Returns the number of bytes necessary to variable-length encode the given {@code long} value.</p>
     *
     * @param value the {@code long} value to encode
     * @return the number of bytes necessary to variable-length encode the given {@code long} value
     */
    public static int length(long value)
    {
        if (value < 0)
            throw new QuicException(ErrorCode.PROTOCOL_VIOLATION_ERROR, "invalid_variable_length_integer");
        if (value < (1 << 6))
            return 1;
        if (value < (1 << 14))
            return 2;
        if (value < (1 << 30))
            return 4;
        if (value < (1L << 62))
            return 8;
        throw new QuicException(ErrorCode.PROTOCOL_VIOLATION_ERROR, "invalid_variable_length_integer");
    }

    public interface IntLongConsumer
    {
        public void accept(int i, long l);
    }
}
