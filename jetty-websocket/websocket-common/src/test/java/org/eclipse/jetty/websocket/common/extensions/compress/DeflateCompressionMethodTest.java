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

package org.eclipse.jetty.websocket.common.extensions.compress;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test the Deflate Compression Method in use by several extensions.
 */
public class DeflateCompressionMethodTest
{
    private static final Logger LOG = Log.getLogger(DeflateCompressionMethodTest.class);

    private void assertRoundTrip(CompressionMethod method, CharSequence msg)
    {
        String expected = msg.toString();

        ByteBuffer orig = BufferUtil.toBuffer(expected,StringUtil.__UTF8_CHARSET);

        LOG.debug("orig: {}",BufferUtil.toDetailString(orig));

        // compress
        method.compress().begin();
        method.compress().input(orig);
        ByteBuffer compressed = method.compress().process();
        LOG.debug("compressed: {}",BufferUtil.toDetailString(compressed));
        Assert.assertThat("Compress.isDone",method.compress().isDone(),is(true));
        method.compress().end();

        // decompress
        ByteBuffer decompressed = ByteBuffer.allocate(msg.length());
        LOG.debug("decompressed(a): {}",BufferUtil.toDetailString(decompressed));
        method.decompress().begin();
        method.decompress().input(compressed);
        while (!method.decompress().isDone())
        {
            ByteBuffer window = method.decompress().process();
            BufferUtil.put(window,decompressed);
        }
        BufferUtil.flipToFlush(decompressed,0);
        LOG.debug("decompressed(f): {}",BufferUtil.toDetailString(decompressed));
        method.decompress().end();

        // validate
        String actual = BufferUtil.toUTF8String(decompressed);
        Assert.assertThat("Message Size",actual.length(),is(msg.length()));
        Assert.assertEquals("Message Contents",expected,actual);
    }

    /**
     * Test decompression with 2 buffers. First buffer is normal, second relies on back buffers created from first.
     */
    @Test
    public void testFollowupBackDistance()
    {
        // The Sample (Compressed) Data
        byte buf1[] = TypeUtil.fromHexString("2aC9Cc4dB50200"); // DEFLATE -> "time:"
        byte buf2[] = TypeUtil.fromHexString("2a01110000"); // DEFLATE -> "time:"

        // Setup Compression Method
        CompressionMethod method = new DeflateCompressionMethod();

        // Decompressed Data Holder
        ByteBuffer decompressed = ByteBuffer.allocate(32);
        BufferUtil.flipToFill(decompressed);

        // Perform Decompress on Buf 1
        BufferUtil.clearToFill(decompressed);
        // IGNORE method.decompress().begin();
        method.decompress().input(ByteBuffer.wrap(buf1));
        while (!method.decompress().isDone())
        {
            ByteBuffer window = method.decompress().process();
            BufferUtil.put(window,decompressed);
        }
        BufferUtil.flipToFlush(decompressed,0);
        LOG.debug("decompressed[1]: {}",BufferUtil.toDetailString(decompressed));
        // IGNORE method.decompress().end();

        // Perform Decompress on Buf 2
        BufferUtil.clearToFill(decompressed);
        // IGNORE method.decompress().begin();
        method.decompress().input(ByteBuffer.wrap(buf2));
        while (!method.decompress().isDone())
        {
            ByteBuffer window = method.decompress().process();
            BufferUtil.put(window,decompressed);
        }
        BufferUtil.flipToFlush(decompressed,0);
        LOG.debug("decompressed[2]: {}",BufferUtil.toDetailString(decompressed));
        // IGNORE method.decompress().end();
    }

    /**
     * Test a large payload (a payload length over 65535 bytes).
     * 
     * Round Trip (RT) Compress then Decompress
     */
    @Test
    public void testRTLarge()
    {
        // large sized message
        StringBuilder msg = new StringBuilder();
        for (int i = 0; i < 5000; i++)
        {
            msg.append("0123456789ABCDEF ");
        }
        msg.append('X'); // so we can see the end in our debugging

        // ensure that test remains sane
        Assert.assertThat("Large Payload Length",msg.length(),greaterThan(0xFF_FF));

        // Setup Compression Method
        CompressionMethod method = new DeflateCompressionMethod();

        // Test round trip
        assertRoundTrip(method,msg);
    }

    /**
     * Test many small payloads (each payload length less than 126 bytes).
     * 
     * Round Trip (RT) Compress then Decompress
     */
    @Test
    public void testRTManySmall()
    {
        // Quote
        List<String> quote = new ArrayList<>();
        quote.add("No amount of experimentation can ever prove me right;");
        quote.add("a single experiment can prove me wrong.");
        quote.add("-- Albert Einstein");

        // Setup Compression Method
        CompressionMethod method = new DeflateCompressionMethod();

        for (String msg : quote)
        {
            // Test round trip
            assertRoundTrip(method,msg);
        }
    }

    /**
     * Test a medium payload (a payload length between 126 - 65535 bytes).
     * 
     * Round Trip (RT) Compress then Decompress
     */
    @Test
    public void testRTMedium()
    {
        // medium sized message
        StringBuilder msg = new StringBuilder();
        for (int i = 0; i < 1000; i++)
        {
            msg.append("0123456789ABCDEF ");
        }
        msg.append('X'); // so we can see the end in our debugging

        // ensure that test remains sane
        Assert.assertThat("Medium Payload Length",msg.length(),allOf(greaterThanOrEqualTo(0x7E),lessThanOrEqualTo(0xFF_FF)));

        // Setup Compression Method
        CompressionMethod method = new DeflateCompressionMethod();

        // Test round trip
        assertRoundTrip(method, msg);
    }

    /**
     * Test a small payload (a payload length less than 126 bytes).
     * 
     * Round Trip (RT) Compress then Decompress
     */
    @Test
    public void testRTSmall()
    {
        // Quote
        StringBuilder quote = new StringBuilder();
        quote.append("No amount of experimentation can ever prove me right;\n");
        quote.append("a single experiment can prove me wrong.\n");
        quote.append("-- Albert Einstein");

        // ensure that test remains sane
        Assert.assertThat("Small Payload Length",quote.length(),lessThan(0x7E));

        // Setup Compression Method
        CompressionMethod method = new DeflateCompressionMethod();

        // Test round trip
        assertRoundTrip(method,quote);
    }
}
