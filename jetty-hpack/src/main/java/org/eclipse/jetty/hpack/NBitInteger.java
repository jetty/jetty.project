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

package org.eclipse.jetty.hpack;

import java.nio.ByteBuffer;

public class NBitInteger
{
    public static void encode3(ByteBuffer buf, int n, int i)
    {
        int p=buf.position()-1;
        
        int nbits = 0xFF >>> (8 - 3);

        if (i < nbits)
        {
            buf.put(p,(byte)((buf.get(p)&~nbits)|i));
        }
        else
        {
            buf.put(p,(byte)(buf.get(p)|nbits));
            
            int length = i - nbits;
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
    

    public static void encode5(ByteBuffer buf, int i)
    {
        int p=buf.position()-1;
        int nbits = 0xFF >>> (8 - 5);

        if (i < nbits)
        {
            buf.put(p,(byte)((buf.get(p)&~nbits)|i));
        }
        else
        {
            buf.put(p,(byte)(buf.get(p)|nbits));
            
            int length = i - nbits;
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
    

    public static void encode8(ByteBuffer buf, int i)
    {   
        int nbits = 0xFF;

        if (i < nbits)
        {
            buf.put((byte)i);
        }
        else
        {
            buf.put((byte)nbits);
            
            int length = i - nbits;
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

    public static int dencode5(ByteBuffer buf)
    {
        int nbits = 0xFF >>> (8 - 5);

        int i=buf.get(buf.position()-1)&nbits;
        
        if (i == nbits)
        {       
            int m=1;
            int b;
            do
            {
                b = 0xff&buf.get();
                i = i + (b&127) * m;
                m = m*128;
            }
            while ((b&128) == 128);
        }
        return i;
    }

    public static int dencode8(ByteBuffer buf)
    {
        int nbits = 0xFF >>> (8 - 8);

        int i=buf.get()&nbits;
        
        if (i == nbits)
        {       
            int m=1;
            int b;
            do
            {
                b = 0xff&buf.get();
                i = i + (b&127) * m;
                m = m*128;
            }
            while ((b&128) == 128);
        }
        return i;
    }
    
}
