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

import static org.hamcrest.Matchers.*;

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
    @Test
    public void testFlate()
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

    @Test
    public void testFlateManySmall()
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
     * Verify that incoming frames are unmodified
     */
    @Test
    public void testIncomingFrames()
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
            Assert.assertThat(prefix + ".rsv1",actual.isRsv1(),is(true));
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
     * Verify that outgoing text frames are compressed.
     */
    @Test
    public void testOutgoingFrames()
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

            System.err.printf("Expected    : %s%n",BufferUtil.toDetailString(expected));
            System.err.printf("Compressed  : %s%n",BufferUtil.toDetailString(compressed));
            System.err.printf("Uncompressed: %s%n",BufferUtil.toDetailString(uncompressed));

            Assert.assertThat(prefix + ".payloadLength",uncompressed.remaining(),is(expected.remaining()));
            ByteBufferAssert.assertEquals(prefix + ".payload",expected,uncompressed);
        }
    }

    /**
     * Outgoing PING (Control Frame) should pass through extension unmodified
     */
    @Test
    public void testOutgoingPing()
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
