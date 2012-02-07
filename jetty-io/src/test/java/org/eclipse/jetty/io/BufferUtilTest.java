// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.io;


import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;

import org.junit.Test;

/**
 *
 */
public class BufferUtilTest
{
    @Test
    public void testToInt() throws Exception
    {
        ByteBuffer buf[] =
        {
            BufferUtil.toBuffer("0"),
            BufferUtil.toBuffer(" 42 "),
            BufferUtil.toBuffer("   43abc"),
            BufferUtil.toBuffer("-44"),
            BufferUtil.toBuffer(" - 45;"),
            BufferUtil.toBuffer("-2147483648"),
            BufferUtil.toBuffer("2147483647"),
        };

        int val[] =
        {
            0,42,43,-44,-45,-2147483648,2147483647
        };

        for (int i=0;i<buf.length;i++)
            assertEquals("t"+i, val[i], BufferUtil.toInt(buf[i]));
    }

    @Test
    public void testPutInt() throws Exception
    {
        int val[] =
        {
            0,42,43,-44,-45,Integer.MIN_VALUE,Integer.MAX_VALUE
        };

        String str[] =
        {
            "0","42","43","-44","-45",""+Integer.MIN_VALUE,""+Integer.MAX_VALUE
        };

        ByteBuffer buffer = ByteBuffer.allocate(24);

        for (int i=0;i<val.length;i++)
        {
            buffer.clear();
            BufferUtil.putDecInt(buffer,val[i]);
            buffer.flip();
            assertEquals("t"+i,str[i],BufferUtil.toString(buffer));
        }
    }

    @Test
    public void testPutLong() throws Exception
    {
        long val[] =
        {
                0L,42L,43L,-44L,-45L,Long.MIN_VALUE,Long.MAX_VALUE
        };

        String str[] =
        {
                "0","42","43","-44","-45",""+Long.MIN_VALUE,""+Long.MAX_VALUE
        };

        ByteBuffer buffer = ByteBuffer.allocate(50);

        for (int i=0;i<val.length;i++)
        {
            buffer.clear();
            BufferUtil.putDecLong(buffer,val[i]);
            buffer.flip();
            assertEquals("t"+i,str[i],BufferUtil.toString(buffer));
        }
    }

    @Test
    public void testPutHexInt() throws Exception
    {
        int val[] =
        {
            0,42,43,-44,-45,-2147483648,2147483647
        };

        String str[] =
        {
            "0","2A","2B","-2C","-2D","-80000000","7FFFFFFF"
        };

        ByteBuffer buffer = ByteBuffer.allocate(50);

        for (int i=0;i<val.length;i++)
        {
            buffer.clear();
            BufferUtil.putHexInt(buffer,val[i]);
            buffer.flip();
            assertEquals("t"+i,str[i],BufferUtil.toString(buffer));
        }
    }
}
