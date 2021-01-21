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

package org.eclipse.jetty.http2.hpack;

import java.nio.ByteBuffer;

public class NBitInteger
{
    public static int octectsNeeded(int n, int i)
    {
        if (n == 8)
        {
            int nbits = 0xFF;
            i = i - nbits;
            if (i < 0)
                return 1;
            if (i == 0)
                return 2;
            int lz = Integer.numberOfLeadingZeros(i);
            int log = 32 - lz;
            return 1 + (log + 6) / 7;
        }

        int nbits = 0xFF >>> (8 - n);
        i = i - nbits;
        if (i < 0)
            return 0;
        if (i == 0)
            return 1;
        int lz = Integer.numberOfLeadingZeros(i);
        int log = 32 - lz;
        return (log + 6) / 7;
    }

    public static void encode(ByteBuffer buf, int n, int i)
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

                int length = i - 0xFF;
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

                int length = i - bits;
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

    public static int decode(ByteBuffer buffer, int n)
    {
        if (n == 8)
        {
            int nbits = 0xFF;

            int i = buffer.get() & 0xff;

            if (i == nbits)
            {
                int m = 1;
                int b;
                do
                {
                    b = 0xff & buffer.get();
                    i = i + (b & 127) * m;
                    m = m * 128;
                }
                while ((b & 128) == 128);
            }
            return i;
        }

        int nbits = 0xFF >>> (8 - n);

        int i = buffer.get(buffer.position() - 1) & nbits;

        if (i == nbits)
        {
            int m = 1;
            int b;
            do
            {
                b = 0xff & buffer.get();
                i = i + (b & 127) * m;
                m = m * 128;
            }
            while ((b & 128) == 128);
        }
        return i;
    }
}
