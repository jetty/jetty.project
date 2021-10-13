//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.internal;

import java.nio.ByteBuffer;
import java.util.function.LongConsumer;

/**
 * <p>Encodes and decodes {@code long} values as specified by
 * <a href="https://datatracker.ietf.org/doc/html/rfc9000#section-16">QUIC</a>.</p>
 */
public class VarLenInt
{
    public static final int MAX_LENGTH = 8;
    private static final int ENCODING_MASK = 0b11_00_00_00;
    private static final int VALUE_MASK = 0b00_11_11_11;

    private int encoding = -1;
    private int length;
    private long value;

    public void reset()
    {
        encoding = -1;
        length = 0;
        value = 0;
    }

    public boolean decode(ByteBuffer buffer, LongConsumer consumer)
    {
        while (buffer.hasRemaining())
        {
            if (encoding < 0)
            {
                // The first byte is the most significant, and therefore the
                // one that holds the encoding in the 2 most significant bits.
                byte hiByte = buffer.get(buffer.position());
                encoding = (hiByte & ENCODING_MASK) >>> 6;
                // Start with the decoded hi byte.
                length = 1 << encoding;
                value = buffer.get() & VALUE_MASK;
                if (--length == 0)
                    return result(consumer);
            }
            else
            {
                if (length > 0)
                {
                    // Shift the value to the left for every byte.
                    value = (value << 8) + (buffer.get() & 0xFF);
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

    private boolean result(LongConsumer consumer)
    {
        consumer.accept(value);
        reset();
        return true;
    }

    public static void encode(ByteBuffer buffer, long value)
    {
        int length = length(value);
        int encoding = 31 - Integer.numberOfLeadingZeros(length);
        // Put the least significant bytes first, and proceed
        // backwards by shifting the value to the right until the
        // most significant byte, that also stores the encoding.
        int position = buffer.position();
        for (int i = length - 1; i > 0; --i)
        {
            buffer.put(position + i, (byte)(value & 0xFF));
            value = value >>> 8;
        }
        buffer.put(position, (byte)((value & VALUE_MASK) | (encoding << 6)));
        buffer.position(position + length);
    }

    public static int length(long value)
    {
        if (value < 0)
            throw new InvalidException("invalid_variable_length_integer");
        if (value < (1 << 6))
            return 1;
        if (value < (1 << 14))
            return 2;
        if (value < (1 << 30))
            return 4;
        if (value < (1L << 62))
            return 8;
        throw new InvalidException("invalid_variable_length_integer");
    }

    public static class InvalidException extends RuntimeException
    {
        public InvalidException(String message)
        {
            super(message);
        }
    }
}
