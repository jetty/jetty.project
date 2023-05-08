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

package org.eclipse.jetty.http.compression;

import java.nio.ByteBuffer;

/**
 * Used to encode integers as described in RFC7541.
 */
public class NBitIntegerEncoder
{
    private NBitIntegerEncoder()
    {
    }

    /**
     * @param n the prefix used to encode this long.
     * @param i the integer to encode.
     * @return the number of octets it would take to encode the long.
     */
    public static int octetsNeeded(int n, long i)
    {
        if (n == 8)
        {
            int nbits = 0xFF;
            i = i - nbits;
            if (i < 0)
                return 1;
            if (i == 0)
                return 2;
            int lz = Long.numberOfLeadingZeros(i);
            int log = 64 - lz;
            return 1 + (log + 6) / 7;
        }

        int nbits = 0xFF >>> (8 - n);
        i = i - nbits;
        if (i < 0)
            return 0;
        if (i == 0)
            return 1;
        int lz = Long.numberOfLeadingZeros(i);
        int log = 64 - lz;
        return (log + 6) / 7;
    }

    /**
     *
     * @param buf the buffer to encode into.
     * @param n the prefix used to encode this long.
     * @param i the long to encode into the buffer.
     */
    public static void encode(ByteBuffer buf, int n, long i)
    {
        if (n == 8)
        {
            if (i < 0xFF)
            {
                buf.put((byte)i);
            }
            else
            {
                buf.put((byte)0xFF);

                long length = i - 0xFF;
                while (true)
                {
                    if ((length & ~0x7F) == 0)
                    {
                        buf.put((byte)length);
                        return;
                    }
                    else
                    {
                        buf.put((byte)((length & 0x7F) | 0x80));
                        length >>>= 7;
                    }
                }
            }
        }
        else
        {
            int p = buf.position() - 1;
            int bits = 0xFF >>> (8 - n);

            if (i < bits)
            {
                buf.put(p, (byte)((buf.get(p) & ~bits) | i));
            }
            else
            {
                buf.put(p, (byte)(buf.get(p) | bits));

                long length = i - bits;
                while (true)
                {
                    if ((length & ~0x7F) == 0)
                    {
                        buf.put((byte)length);
                        return;
                    }
                    else
                    {
                        buf.put((byte)((length & 0x7F) | 0x80));
                        length >>>= 7;
                    }
                }
            }
        }
    }
}
