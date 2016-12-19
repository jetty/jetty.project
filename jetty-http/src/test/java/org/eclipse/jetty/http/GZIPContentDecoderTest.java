//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;


public class GZIPContentDecoderTest
{
    @Rule
    public final TestTracker tracker = new TestTracker();


    ArrayByteBufferPool pool;
    AtomicInteger buffers = new AtomicInteger(0);
    
    @Before
    public void beforeClass() throws Exception
    {
        buffers.set(0);
        pool = new ArrayByteBufferPool()
            {

                @Override
                public ByteBuffer acquire(int size, boolean direct)
                {
                    buffers.incrementAndGet();
                    return super.acquire(size,direct);
                }

                @Override
                public void release(ByteBuffer buffer)
                {
                    buffers.decrementAndGet();
                    super.release(buffer);
                }
            
            };
    }
    
    @After
    public void afterClass() throws Exception
    {
        assertEquals(0,buffers.get());
    }
    
    @Test
    public void testCompresedContentFormat() throws Exception
    {
        assertTrue(CompressedContentFormat.tagEquals("tag","tag"));
        assertTrue(CompressedContentFormat.tagEquals("\"tag\"","\"tag\""));
        assertTrue(CompressedContentFormat.tagEquals("\"tag\"","\"tag--gzip\""));
        assertFalse(CompressedContentFormat.tagEquals("Zag","Xag--gzip"));
        assertFalse(CompressedContentFormat.tagEquals("xtag","tag"));
    }
    
    @Test
    public void testStreamNoBlocks() throws Exception
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.close();
        byte[] bytes = baos.toByteArray();

        GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(bytes), 1);
        int read = input.read();
        assertEquals(-1, read);
    }

    @Test
    public void testStreamBigBlockOneByteAtATime() throws Exception
    {
        String data = "0123456789ABCDEF";
        for (int i = 0; i < 10; ++i)
            data += data;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        baos = new ByteArrayOutputStream();
        GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(bytes), 1);
        int read;
        while ((read = input.read()) >= 0)
            baos.write(read);
        assertEquals(data, new String(baos.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void testNoBlocks() throws Exception
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.close();
        byte[] bytes = baos.toByteArray();

        GZIPContentDecoder decoder = new GZIPContentDecoder(pool,2048);
        ByteBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
        assertEquals(0, decoded.remaining());
    }

    @Test
    public void testSmallBlock() throws Exception
    {
        String data = "0";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        GZIPContentDecoder decoder = new GZIPContentDecoder(pool,2048);
        ByteBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
        assertEquals(data, StandardCharsets.UTF_8.decode(decoded).toString());
        decoder.release(decoded);
    }

    @Test
    public void testSmallBlockWithGZIPChunkedAtBegin() throws Exception
    {
        String data = "0";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        // The header is 10 bytes, chunk at 11 bytes
        byte[] bytes1 = new byte[11];
        System.arraycopy(bytes, 0, bytes1, 0, bytes1.length);
        byte[] bytes2 = new byte[bytes.length - bytes1.length];
        System.arraycopy(bytes, bytes1.length, bytes2, 0, bytes2.length);

        GZIPContentDecoder decoder = new GZIPContentDecoder(pool,2048);
        ByteBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes1));
        assertEquals(0, decoded.capacity());
        decoded = decoder.decode(ByteBuffer.wrap(bytes2));
        assertEquals(data, StandardCharsets.UTF_8.decode(decoded).toString());
        decoder.release(decoded);
    }

    @Test
    public void testSmallBlockWithGZIPChunkedAtEnd() throws Exception
    {
        String data = "0";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        // The trailer is 8 bytes, chunk the last 9 bytes
        byte[] bytes1 = new byte[bytes.length - 9];
        System.arraycopy(bytes, 0, bytes1, 0, bytes1.length);
        byte[] bytes2 = new byte[bytes.length - bytes1.length];
        System.arraycopy(bytes, bytes1.length, bytes2, 0, bytes2.length);

        GZIPContentDecoder decoder = new GZIPContentDecoder(pool,2048);
        ByteBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes1));
        assertEquals(data, StandardCharsets.UTF_8.decode(decoded).toString());
        assertFalse(decoder.isFinished());
        decoder.release(decoded);
        decoded = decoder.decode(ByteBuffer.wrap(bytes2));
        assertEquals(0, decoded.remaining());
        assertTrue(decoder.isFinished());
        decoder.release(decoded);
    }

    @Test
    public void testSmallBlockWithGZIPTrailerChunked() throws Exception
    {
        String data = "0";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        // The trailer is 4+4 bytes, chunk the last 3 bytes
        byte[] bytes1 = new byte[bytes.length - 3];
        System.arraycopy(bytes, 0, bytes1, 0, bytes1.length);
        byte[] bytes2 = new byte[bytes.length - bytes1.length];
        System.arraycopy(bytes, bytes1.length, bytes2, 0, bytes2.length);

        GZIPContentDecoder decoder = new GZIPContentDecoder(pool,2048);
        ByteBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes1));
        assertEquals(0, decoded.capacity());
        decoder.release(decoded);
        decoded = decoder.decode(ByteBuffer.wrap(bytes2));
        assertEquals(data, StandardCharsets.UTF_8.decode(decoded).toString());
        decoder.release(decoded);
    }

    @Test
    public void testTwoSmallBlocks() throws Exception
    {
        String data1 = "0";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data1.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes1 = baos.toByteArray();

        String data2 = "1";
        baos = new ByteArrayOutputStream();
        output = new GZIPOutputStream(baos);
        output.write(data2.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes2 = baos.toByteArray();

        byte[] bytes = new byte[bytes1.length + bytes2.length];
        System.arraycopy(bytes1, 0, bytes, 0, bytes1.length);
        System.arraycopy(bytes2, 0, bytes, bytes1.length, bytes2.length);

        GZIPContentDecoder decoder = new GZIPContentDecoder(pool,2048);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        ByteBuffer decoded = decoder.decode(buffer);
        assertEquals(data1, StandardCharsets.UTF_8.decode(decoded).toString());
        assertTrue(decoder.isFinished());
        assertTrue(buffer.hasRemaining());
        decoder.release(decoded);
        decoded = decoder.decode(buffer);
        assertEquals(data2, StandardCharsets.UTF_8.decode(decoded).toString());
        assertTrue(decoder.isFinished());
        assertFalse(buffer.hasRemaining());
        decoder.release(decoded);
    }

    @Test
    public void testBigBlock() throws Exception
    {
        String data = "0123456789ABCDEF";
        for (int i = 0; i < 10; ++i)
            data += data;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        String result = "";
        GZIPContentDecoder decoder = new GZIPContentDecoder(pool,2048);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining())
        {
            ByteBuffer decoded = decoder.decode(buffer);
            result += StandardCharsets.UTF_8.decode(decoded).toString();
            decoder.release(decoded);
        }
        assertEquals(data, result);
    }

    @Test
    public void testBigBlockOneByteAtATime() throws Exception
    {
        String data = "0123456789ABCDEF";
        for (int i = 0; i < 10; ++i)
            data += data;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        String result = "";
        GZIPContentDecoder decoder = new GZIPContentDecoder(64);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining())
        {
            ByteBuffer decoded = decoder.decode(ByteBuffer.wrap(new byte[]{buffer.get()}));
            if (decoded.hasRemaining())
                result += StandardCharsets.UTF_8.decode(decoded).toString();
            decoder.release(decoded);
        }
        assertEquals(data, result);
        assertTrue(decoder.isFinished());
    }

    @Test
    public void testBigBlockWithExtraBytes() throws Exception
    {
        String data1 = "0123456789ABCDEF";
        for (int i = 0; i < 10; ++i)
            data1 += data1;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data1.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes1 = baos.toByteArray();

        String data2 = "HELLO";
        byte[] bytes2 = data2.getBytes(StandardCharsets.UTF_8);

        byte[] bytes = new byte[bytes1.length + bytes2.length];
        System.arraycopy(bytes1, 0, bytes, 0, bytes1.length);
        System.arraycopy(bytes2, 0, bytes, bytes1.length, bytes2.length);

        String result = "";
        GZIPContentDecoder decoder = new GZIPContentDecoder(64);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining())
        {
            ByteBuffer decoded = decoder.decode(buffer);
            if (decoded.hasRemaining())
                result += StandardCharsets.UTF_8.decode(decoded).toString();
            decoder.release(decoded);
            if (decoder.isFinished())
                break;
        }
        assertEquals(data1, result);
        assertTrue(buffer.hasRemaining());
        assertEquals(data2, StandardCharsets.UTF_8.decode(buffer).toString());
    }
}
