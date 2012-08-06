// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.extensions;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.ByteBufferAssert;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.extensions.deflate.DeflateFrameExtension;
import org.eclipse.jetty.websocket.protocol.ExtensionConfig;
import org.eclipse.jetty.websocket.protocol.IncomingFramesCapture;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.OutgoingFramesCapture;
import org.eclipse.jetty.websocket.protocol.OutgoingFramesCapture.Write;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.junit.Assert;
import org.junit.Test;

public class DeflateFrameExtensionTest
{
    /**
     * Test a large payload (a payload length over 65535 bytes)
     */
    @Test
    public void testFlateLarge()
    {
        // Server sends a big message
        StringBuilder msg = new StringBuilder();
        for (int i = 0; i < 5000; i++)
        {
            msg.append("0123456789ABCDEF ");
        }
        msg.append('X'); // so we can see the end in our debugging

        // ensure that test remains sane
        Assert.assertThat("Large Payload Length",msg.length(),greaterThan(0xFF_FF));

        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        // allow large payload for this test
        policy.setBufferSize(100000);
        policy.setMaxPayloadSize(150000);

        DeflateFrameExtension ext = new DeflateFrameExtension();
        ext.setBufferPool(new StandardByteBufferPool());
        ext.setPolicy(policy);

        ExtensionConfig config = ExtensionConfig.parse("x-deflate-frame;minLength=8");
        ext.setConfig(config);

        String expected = msg.toString();

        ByteBuffer orig = BufferUtil.toBuffer(expected,StringUtil.__UTF8_CHARSET);
        // compress
        ByteBuffer compressed = ext.deflate(orig);

        // decompress
        ByteBuffer decompressed = ext.inflate(compressed);

        // validate
        String actual = BufferUtil.toUTF8String(decompressed);
        Assert.assertEquals(expected,actual);
    }

    /**
     * Test a medium payload (a payload length between 128 - 65535 bytes)
     */
    @Test
    public void testFlateMedium()
    {
        // Server sends a big message
        StringBuilder msg = new StringBuilder();
        for (int i = 0; i < 1000; i++)
        {
            msg.append("0123456789ABCDEF ");
        }
        msg.append('X'); // so we can see the end in our debugging

        // ensure that test remains sane
        Assert.assertThat("Medium Payload Length",msg.length(),allOf(greaterThanOrEqualTo(0x7E),lessThanOrEqualTo(0xFF_FF)));

        DeflateFrameExtension ext = new DeflateFrameExtension();
        ext.setBufferPool(new StandardByteBufferPool());
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("x-deflate-frame;minLength=8");
        ext.setConfig(config);

        String expected = msg.toString();

        ByteBuffer orig = BufferUtil.toBuffer(expected,StringUtil.__UTF8_CHARSET);
        // compress
        ByteBuffer compressed = ext.deflate(orig);

        // decompress
        ByteBuffer decompressed = ext.inflate(compressed);

        // validate
        String actual = BufferUtil.toUTF8String(decompressed);
        Assert.assertEquals(expected,actual);
    }

    @Test
    public void testFlateSmall()
    {
        DeflateFrameExtension ext = new DeflateFrameExtension();
        ext.setBufferPool(new StandardByteBufferPool());
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("x-deflate-frame;minLength=8");
        ext.setConfig(config);

        // Quote
        StringBuilder quote = new StringBuilder();
        quote.append("No amount of experimentation can ever prove me right;\n");
        quote.append("a single experiment can prove me wrong.\n");
        quote.append("-- Albert Einstein");

        // ensure that test remains sane
        Assert.assertThat("Small Payload Length",quote.length(),lessThan(0x7E));

        String expected = quote.toString();

        ByteBuffer orig = BufferUtil.toBuffer(expected,StringUtil.__UTF8_CHARSET);
        // compress
        ByteBuffer compressed = ext.deflate(orig);

        // decompress
        ByteBuffer decompressed = ext.inflate(compressed);

        // validate
        String actual = BufferUtil.toUTF8String(decompressed);
        Assert.assertEquals(expected,actual);
    }

    /**
     * Test round-trips of many small frames (no frame larger than 126 bytes)
     */
    @Test
    public void testFlateSmall_Many()
    {
        DeflateFrameExtension ext = new DeflateFrameExtension();
        ext.setBufferPool(new StandardByteBufferPool());
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("x-deflate-frame;minLength=8");
        ext.setConfig(config);

        // Quote
        List<String> quote = new ArrayList<>();
        quote.add("No amount of experimentation can ever prove me right;");
        quote.add("a single experiment can prove me wrong.");
        quote.add("-- Albert Einstein");

        for (String expected : quote)
        {
            ByteBuffer orig = BufferUtil.toBuffer(expected,StringUtil.__UTF8_CHARSET);
            // compress
            ByteBuffer compressed = ext.deflate(orig);

            // decompress
            ByteBuffer decompressed = ext.inflate(compressed);

            // validate
            String actual = BufferUtil.toUTF8String(decompressed);
            Assert.assertEquals(expected,actual);
        }
    }

    /**
     * Verify that incoming compressed frames are properly decompressed
     */
    @Test
    public void testIncomingCompressedFrames()
    {
        IncomingFramesCapture capture = new IncomingFramesCapture();

        DeflateFrameExtension ext = new DeflateFrameExtension();
        ext.setBufferPool(new StandardByteBufferPool());
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("x-deflate-frame;minLength=16");
        ext.setConfig(config);

        ext.setNextIncomingFrames(capture);

        // Quote
        List<String> quote = new ArrayList<>();
        quote.add("No amount of experimentation can ever prove me right;");
        quote.add("a single experiment can prove me wrong.");
        quote.add("-- Albert Einstein");

        // Manually compress frame and pass into extension
        for (String q : quote)
        {
            ByteBuffer data = BufferUtil.toBuffer(q,StringUtil.__UTF8_CHARSET);
            WebSocketFrame frame = new WebSocketFrame(OpCode.TEXT);
            frame.setPayload(ext.deflate(data));
            frame.setRsv1(true); // required by extension
            ext.incoming(frame);
        }

        int len = quote.size();
        capture.assertFrameCount(len);
        capture.assertHasFrame(OpCode.TEXT,len);

        String prefix;
        for (int i = 0; i < len; i++)
        {
            prefix = "Frame[" + i + "]";

            WebSocketFrame actual = capture.getFrames().get(i);

            Assert.assertThat(prefix + ".opcode",actual.getOpCode(),is(OpCode.TEXT));
            Assert.assertThat(prefix + ".fin",actual.isFin(),is(true));
            Assert.assertThat(prefix + ".rsv1",actual.isRsv1(),is(false)); // RSV1 should be unset at this point
            Assert.assertThat(prefix + ".rsv2",actual.isRsv2(),is(false));
            Assert.assertThat(prefix + ".rsv3",actual.isRsv3(),is(false));

            ByteBuffer expected = BufferUtil.toBuffer(quote.get(i),StringUtil.__UTF8_CHARSET);
            Assert.assertThat(prefix + ".payloadLength",actual.getPayloadLength(),is(expected.remaining()));
            ByteBufferAssert.assertEquals(prefix + ".payload",expected,actual.getPayload().slice());
        }
    }

    /**
     * Incoming PING (Control Frame) should pass through extension unmodified
     */
    @Test
    public void testIncomingPing() {
        IncomingFramesCapture capture = new IncomingFramesCapture();

        DeflateFrameExtension ext = new DeflateFrameExtension();
        ext.setBufferPool(new StandardByteBufferPool());
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("x-deflate-frame;minLength=16");
        ext.setConfig(config);

        ext.setNextIncomingFrames(capture);

        String payload = "Are you there?";
        WebSocketFrame ping = WebSocketFrame.ping().setPayload(payload);
        ext.incoming(ping);

        capture.assertFrameCount(1);
        capture.assertHasFrame(OpCode.PING,1);
        WebSocketFrame actual = capture.getFrames().getFirst();

        Assert.assertThat("Frame.opcode",actual.getOpCode(),is(OpCode.PING));
        Assert.assertThat("Frame.fin",actual.isFin(),is(true));
        Assert.assertThat("Frame.rsv1",actual.isRsv1(),is(false));
        Assert.assertThat("Frame.rsv2",actual.isRsv2(),is(false));
        Assert.assertThat("Frame.rsv3",actual.isRsv3(),is(false));

        ByteBuffer expected = BufferUtil.toBuffer(payload,StringUtil.__UTF8_CHARSET);
        Assert.assertThat("Frame.payloadLength",actual.getPayloadLength(),is(expected.remaining()));
        ByteBufferAssert.assertEquals("Frame.payload",expected,actual.getPayload().slice());
    }

    /**
     * Verify that incoming uncompressed frames are properly passed through
     */
    @Test
    public void testIncomingUncompressedFrames()
    {
        IncomingFramesCapture capture = new IncomingFramesCapture();

        DeflateFrameExtension ext = new DeflateFrameExtension();
        ext.setBufferPool(new StandardByteBufferPool());
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("x-deflate-frame;minLength=16");
        ext.setConfig(config);

        ext.setNextIncomingFrames(capture);

        // Quote
        List<String> quote = new ArrayList<>();
        quote.add("No amount of experimentation can ever prove me right;");
        quote.add("a single experiment can prove me wrong.");
        quote.add("-- Albert Einstein");

        // leave frames as-is, no compression, and pass into extension
        for (String q : quote)
        {
            WebSocketFrame frame = new WebSocketFrame(OpCode.TEXT);
            frame.setPayload(q);
            frame.setRsv1(false); // indication to extension that frame is not compressed (ie: a normal frame)
            ext.incoming(frame);
        }

        int len = quote.size();
        capture.assertFrameCount(len);
        capture.assertHasFrame(OpCode.TEXT,len);

        String prefix;
        for (int i = 0; i < len; i++)
        {
            prefix = "Frame[" + i + "]";

            WebSocketFrame actual = capture.getFrames().get(i);

            Assert.assertThat(prefix + ".opcode",actual.getOpCode(),is(OpCode.TEXT));
            Assert.assertThat(prefix + ".fin",actual.isFin(),is(true));
            Assert.assertThat(prefix + ".rsv1",actual.isRsv1(),is(false));
            Assert.assertThat(prefix + ".rsv2",actual.isRsv2(),is(false));
            Assert.assertThat(prefix + ".rsv3",actual.isRsv3(),is(false));

            ByteBuffer expected = BufferUtil.toBuffer(quote.get(i),StringUtil.__UTF8_CHARSET);
            Assert.assertThat(prefix + ".payloadLength",actual.getPayloadLength(),is(expected.remaining()));
            ByteBufferAssert.assertEquals(prefix + ".payload",expected,actual.getPayload().slice());
        }
    }

    /**
     * Verify that outgoing text frames are compressed.
     */
    @Test
    public void testOutgoingFrames() throws IOException
    {
        OutgoingFramesCapture capture = new OutgoingFramesCapture();

        DeflateFrameExtension ext = new DeflateFrameExtension();
        ext.setBufferPool(new StandardByteBufferPool());
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("x-deflate-frame;minLength=16");
        ext.setConfig(config);

        ext.setNextOutgoingFrames(capture);

        // Quote
        List<String> quote = new ArrayList<>();
        quote.add("No amount of experimentation can ever prove me right;");
        quote.add("a single experiment can prove me wrong.");
        quote.add("-- Albert Einstein");

        // Write quote as separate frames
        List<Callback<?>> callbacks = new ArrayList<>();
        for (String section : quote)
        {
            WebSocketFrame frame = WebSocketFrame.text(section);
            FutureCallback<String> callback = new FutureCallback<>();
            ext.output("Q" + (callbacks.size()),callback,frame);
            callbacks.add(callback);
        }

        int len = quote.size();
        capture.assertFrameCount(len);
        capture.assertHasFrame(OpCode.TEXT,len);

        String prefix;
        LinkedList<Write<?>> writes = capture.getWrites();
        for (int i = 0; i < len; i++)
        {
            prefix = "Write[" + i + "]";
            Write<?> write = writes.get(i);
            // Validate callbacks
            Assert.assertThat(prefix + ".context",(String)write.context,is("Q" + i));
            Assert.assertSame(prefix + ".callback",callbacks.get(i),write.callback);

            // Validate Frame
            WebSocketFrame actual = write.frame;
            prefix = ".frame";
            Assert.assertThat(prefix + ".opcode",actual.getOpCode(),is(OpCode.TEXT));
            Assert.assertThat(prefix + ".fin",actual.isFin(),is(true));
            Assert.assertThat(prefix + ".rsv1",actual.isRsv1(),is(true));
            Assert.assertThat(prefix + ".rsv2",actual.isRsv2(),is(false));
            Assert.assertThat(prefix + ".rsv3",actual.isRsv3(),is(false));

            // Validate Payload
            ByteBuffer expected = BufferUtil.toBuffer(quote.get(i),StringUtil.__UTF8_CHARSET);
            // Decompress payload
            ByteBuffer compressed = actual.getPayload().slice();
            ByteBuffer uncompressed = ext.inflate(compressed);

            Assert.assertThat(prefix + ".payloadLength",uncompressed.remaining(),is(expected.remaining()));
            ByteBufferAssert.assertEquals(prefix + ".payload",expected,uncompressed);
        }
    }

    /**
     * Outgoing PING (Control Frame) should pass through extension unmodified
     */
    @Test
    public void testOutgoingPing() throws IOException
    {
        OutgoingFramesCapture capture = new OutgoingFramesCapture();

        DeflateFrameExtension ext = new DeflateFrameExtension();
        ext.setBufferPool(new StandardByteBufferPool());
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("x-deflate-frame;minLength=16");
        ext.setConfig(config);

        ext.setNextOutgoingFrames(capture);

        String payload = "Are you there?";
        WebSocketFrame ping = WebSocketFrame.ping().setPayload(payload);

        FutureCallback<String> callback = new FutureCallback<>();
        ext.output("TenFour",callback,ping);

        capture.assertFrameCount(1);
        capture.assertHasFrame(OpCode.PING,1);

        Write<?> write = capture.getWrites().getFirst();
        Assert.assertThat("Write.context",(String)write.context,is("TenFour"));
        Assert.assertSame("Write.callback",callback,write.callback);

        WebSocketFrame actual = write.frame;

        Assert.assertThat("Frame.opcode",actual.getOpCode(),is(OpCode.PING));
        Assert.assertThat("Frame.fin",actual.isFin(),is(true));
        Assert.assertThat("Frame.rsv1",actual.isRsv1(),is(false));
        Assert.assertThat("Frame.rsv2",actual.isRsv2(),is(false));
        Assert.assertThat("Frame.rsv3",actual.isRsv3(),is(false));

        ByteBuffer expected = BufferUtil.toBuffer(payload,StringUtil.__UTF8_CHARSET);
        Assert.assertThat("Frame.payloadLength",actual.getPayloadLength(),is(expected.remaining()));
        ByteBufferAssert.assertEquals("Frame.payload",expected,actual.getPayload().slice());
    }
}
