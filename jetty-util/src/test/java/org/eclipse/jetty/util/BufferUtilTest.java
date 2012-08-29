//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;


import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void testPut() throws Exception
    {
        ByteBuffer to = BufferUtil.allocate(10);
        ByteBuffer from=BufferUtil.toBuffer("12345");

        BufferUtil.clear(to);
        assertEquals(5,BufferUtil.flipPutFlip(from,to));
        assertTrue(BufferUtil.isEmpty(from));
        assertEquals("12345",BufferUtil.toString(to));

        from=BufferUtil.toBuffer("XX67890ZZ");
        from.position(2);

        assertEquals(5,BufferUtil.flipPutFlip(from,to));
        assertEquals(2,from.remaining());
        assertEquals("1234567890",BufferUtil.toString(to));
    }

    @Test
    public void testPutDirect() throws Exception
    {
        ByteBuffer to = BufferUtil.allocateDirect(10);
        ByteBuffer from=BufferUtil.toBuffer("12345");

        BufferUtil.clear(to);
        assertEquals(5,BufferUtil.flipPutFlip(from,to));
        assertTrue(BufferUtil.isEmpty(from));
        assertEquals("12345",BufferUtil.toString(to));

        from=BufferUtil.toBuffer("XX67890ZZ");
        from.position(2);

        assertEquals(5,BufferUtil.flipPutFlip(from,to));
        assertEquals(2,from.remaining());
        assertEquals("1234567890",BufferUtil.toString(to));
    }

    @Test
    public void testToBuffer_Array()
    {
        byte arr[] = new byte[128];
        Arrays.fill(arr,(byte)0x44);
        ByteBuffer buf = BufferUtil.toBuffer(arr);

        int count = 0;
        while (buf.remaining() > 0)
        {
            byte b = buf.get();
            Assert.assertEquals(b,0x44);
            count++;
        }

        Assert.assertEquals("Count of bytes",arr.length,count);
    }

    @Test
    public void testToBuffer_ArrayOffsetLength()
    {
        byte arr[] = new byte[128];
        Arrays.fill(arr,(byte)0xFF); // fill whole thing with FF
        int offset = 10;
        int length = 100;
        Arrays.fill(arr,offset,offset + length,(byte)0x77); // fill partial with 0x77
        ByteBuffer buf = BufferUtil.toBuffer(arr,offset,length);

        int count = 0;
        while (buf.remaining() > 0)
        {
            byte b = buf.get();
            Assert.assertEquals(b,0x77);
            count++;
        }

        Assert.assertEquals("Count of bytes",length,count);
    }
}
