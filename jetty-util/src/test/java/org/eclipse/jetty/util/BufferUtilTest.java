//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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


import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
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
            BufferUtil.clearToFill(buffer);
            BufferUtil.putDecInt(buffer,val[i]);
            BufferUtil.flipToFlush(buffer,0);
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
            BufferUtil.clearToFill(buffer);
            BufferUtil.putDecLong(buffer,val[i]);
            BufferUtil.flipToFlush(buffer,0);
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
            BufferUtil.clearToFill(buffer);
            BufferUtil.putHexInt(buffer,val[i]);
            BufferUtil.flipToFlush(buffer,0);
            assertEquals("t"+i,str[i],BufferUtil.toString(buffer));
        }
    }

    @Test
    public void testPut() throws Exception
    {
        ByteBuffer to = BufferUtil.allocate(10);
        ByteBuffer from=BufferUtil.toBuffer("12345");

        BufferUtil.clear(to);
        assertEquals(5,BufferUtil.append(to,from));
        assertTrue(BufferUtil.isEmpty(from));
        assertEquals("12345",BufferUtil.toString(to));

        from=BufferUtil.toBuffer("XX67890ZZ");
        from.position(2);

        assertEquals(5,BufferUtil.append(to,from));
        assertEquals(2,from.remaining());
        assertEquals("1234567890",BufferUtil.toString(to));
    }



    @Test
    public void testAppend() throws Exception
    {
        ByteBuffer to = BufferUtil.allocate(8);
        ByteBuffer from=BufferUtil.toBuffer("12345");

        BufferUtil.append(to,from.array(),0,3);
        assertEquals("123",BufferUtil.toString(to));
        BufferUtil.append(to,from.array(),3,2);
        assertEquals("12345",BufferUtil.toString(to));

        try
        {
            BufferUtil.append(to,from.array(),0,5);
            Assert.fail();
        }
        catch(BufferOverflowException e)
        {}
    }


    @Test
    public void testPutDirect() throws Exception
    {
        ByteBuffer to = BufferUtil.allocateDirect(10);
        ByteBuffer from=BufferUtil.toBuffer("12345");

        BufferUtil.clear(to);
        assertEquals(5,BufferUtil.append(to,from));
        assertTrue(BufferUtil.isEmpty(from));
        assertEquals("12345",BufferUtil.toString(to));

        from=BufferUtil.toBuffer("XX67890ZZ");
        from.position(2);

        assertEquals(5,BufferUtil.append(to,from));
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

    private static final Logger LOG = Log.getLogger(BufferUtilTest.class);

    @Test
    @Ignore("Very simple microbenchmark to compare different writeTo implementations. Only for development thus " +
            "ignored.")
    public void testWriteToMicrobenchmark() throws IOException
    {
        int capacity = 1024 * 128;
        int iterations = 100;
        int testRuns = 10;
        byte[] bytes = new byte[capacity];
        ThreadLocalRandom.current().nextBytes(bytes);
        ByteBuffer buffer = BufferUtil.allocate(capacity);
        BufferUtil.append(buffer, bytes, 0, capacity);
        long startTest = System.nanoTime();
        for (int i = 0; i < testRuns; i++)
        {
            long start = System.nanoTime();
            for (int j = 0; j < iterations; j++)
            {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                long startRun = System.nanoTime();
                BufferUtil.writeTo(buffer.asReadOnlyBuffer(), out);
                long elapsedRun = System.nanoTime() - startRun;
//                LOG.warn("run elapsed={}ms", elapsedRun / 1000);
                assertThat("Bytes in out equal bytes in buffer", Arrays.equals(bytes, out.toByteArray()), is(true));
            }
            long elapsed = System.nanoTime() - start;
            LOG.warn("elapsed={}ms average={}ms", elapsed / 1000, elapsed/iterations/1000);
        }
        LOG.warn("overall average: {}ms", (System.nanoTime() - startTest) / testRuns / iterations / 1000);
    }

    @Test
    public void testWriteToWithBufferThatDoesNotExposeArrayAndSmallContent() throws IOException
    {
        int capacity = BufferUtil.TEMP_BUFFER_SIZE/4;
        testWriteToWithBufferThatDoesNotExposeArray(capacity);
    }

    @Test
    public void testWriteToWithBufferThatDoesNotExposeArrayAndContentLengthMatchingTempBufferSize() throws IOException
    {
        int capacity = BufferUtil.TEMP_BUFFER_SIZE;
        testWriteToWithBufferThatDoesNotExposeArray(capacity);
    }

    @Test
    public void testWriteToWithBufferThatDoesNotExposeArrayAndContentSlightlyBiggerThanTwoTimesTempBufferSize()
            throws
            IOException
    {
        int capacity = BufferUtil.TEMP_BUFFER_SIZE*2+1024;
        testWriteToWithBufferThatDoesNotExposeArray(capacity);
    }


    @Test
    public void testEnsureCapacity() throws Exception
    {
        ByteBuffer b = BufferUtil.toBuffer("Goodbye Cruel World");
        assertTrue(b==BufferUtil.ensureCapacity(b, 0));
        assertTrue(b==BufferUtil.ensureCapacity(b, 10));
        assertTrue(b==BufferUtil.ensureCapacity(b, b.capacity()));


        ByteBuffer b1 = BufferUtil.ensureCapacity(b, 64);
        assertTrue(b!=b1);
        assertEquals(64, b1.capacity());
        assertEquals("Goodbye Cruel World", BufferUtil.toString(b1));

        b1.position(8);
        b1.limit(13);
        assertEquals("Cruel", BufferUtil.toString(b1));
        ByteBuffer b2 = b1.slice();
        assertEquals("Cruel", BufferUtil.toString(b2));
        System.err.println(BufferUtil.toDetailString(b2));
        assertEquals(8, b2.arrayOffset());
        assertEquals(5, b2.capacity());

        assertTrue(b2==BufferUtil.ensureCapacity(b2, 5));

        ByteBuffer b3 = BufferUtil.ensureCapacity(b2, 64);
        assertTrue(b2!=b3);
        assertEquals(64, b3.capacity());
        assertEquals("Cruel", BufferUtil.toString(b3));
        assertEquals(0, b3.arrayOffset());

    }


    private void testWriteToWithBufferThatDoesNotExposeArray(int capacity) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = new byte[capacity];
        ThreadLocalRandom.current().nextBytes(bytes);
        ByteBuffer buffer = BufferUtil.allocate(capacity);
        BufferUtil.append(buffer, bytes, 0, capacity);
        BufferUtil.writeTo(buffer.asReadOnlyBuffer(), out);
        assertThat("Bytes in out equal bytes in buffer", Arrays.equals(bytes, out.toByteArray()), is(true));
    }

}
