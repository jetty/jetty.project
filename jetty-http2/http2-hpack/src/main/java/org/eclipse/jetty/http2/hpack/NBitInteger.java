//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.hpack;

import java.nio.ByteBuffer;

public class NBitInteger
{
    public static int octectsNeeded(int n,int i)
    {
        if (n==8)
        {
            int nbits = 0xFF;
            i=i-nbits;
            if (i<0)
                return 1;
            if (i==0)
                return 2;
            int lz=Integer.numberOfLeadingZeros(i);
            int log=32-lz;
            return 1+(log+6)/7;
        }
        
        int nbits = 0xFF >>> (8 - n);
        i=i-nbits;
        if (i<0)
            return 0;
        if (i==0)
            return 1;
        int lz=Integer.numberOfLeadingZeros(i);
        int log=32-lz;
        return (log+6)/7;
    }
    
    public static void encode(ByteBuffer buf, int n, int i)
    {
        if (n==8)
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
            int p=buf.position()-1;
            int bits = 0xFF >>> (8 - n);

            if (i < bits)
            {
                buf.put(p,(byte)((buf.get(p)&~bits)|i));
            }
            else
            {
                buf.put(p,(byte)(buf.get(p)|bits));

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
        if (n==8)
        {
            int nbits = 0xFF;

            int i=buffer.get()&0xff;
            
            if (i == nbits)
            {       
                int m=1;
                int b;
                do
                {
                    b = 0xff&buffer.get();
                    i = i + (b&127) * m;
                    m = m*128;
                }
                while ((b&128) == 128);
            }
            return i;
        }
        
        int nbits = 0xFF >>> (8 - n);

        int i=buffer.get(buffer.position()-1)&nbits;
        
        if (i == nbits)
        {       
            int m=1;
            int b;
            do
            {
                b = 0xff&buffer.get();
                i = i + (b&127) * m;
                m = m*128;
            }
            while ((b&128) == 128);
        }
        return i;
    }
}
