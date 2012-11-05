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

package org.eclipse.jetty.websocket.core.extensions.compress;

import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.RequestedExtensionConfig;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.extensions.compress.PerMessageCompressionExtension;
import org.eclipse.jetty.websocket.core.ByteBufferAssert;
import org.eclipse.jetty.websocket.core.protocol.IncomingFramesCapture;
import org.eclipse.jetty.websocket.core.protocol.OutgoingFramesCapture;
import org.eclipse.jetty.websocket.core.protocol.OutgoingFramesCapture.Write;
import org.junit.Assert;
import org.junit.Test;

public class PerMessageCompressionExtensionTest
{
    private void assertDraftExample(String hexStr, String expectedStr)
    {
        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();

        // Setup extension
        PerMessageCompressionExtension ext = new PerMessageCompressionExtension();
        ext.setBufferPool(new MappedByteBufferPool());
        ext.setPolicy(policy);
        ExtensionConfig config = ExtensionConfig.parse("permessage-compress");
        ext.setConfig(config);

        // Setup capture of incoming frames
        IncomingFramesCapture capture = new IncomingFramesCapture();

        // Wire up stack
        ext.setNextIncomingFrames(capture); // ext -> capture

        // Receive frame
        String hex = hexStr.replaceAll("\\s*0x","");
        byte net[] = TypeUtil.fromHexString(hex);
        WebSocketFrame frame = WebSocketFrame.text();
        frame.setRsv1(true);
        frame.setPayload(net);

        // Send frame into stack
        ext.incoming(frame);

        // Verify captured frames.
        capture.assertFrameCount(1);
        capture.assertHasFrame(OpCode.TEXT,1);

        WebSocketFrame actual = capture.getFrames().pop();

        String prefix = "frame";
        Assert.assertThat(prefix + ".opcode",actual.getOpCode(),is(OpCode.TEXT));
        Assert.assertThat(prefix + ".fin",actual.isFin(),is(true));
        Assert.assertThat(prefix + ".rsv1",actual.isRsv1(),is(false)); // RSV1 should be unset at this point
        Assert.assertThat(prefix + ".rsv2",actual.isRsv2(),is(false));
        Assert.assertThat(prefix + ".rsv3",actual.isRsv3(),is(false));

        ByteBuffer expected = BufferUtil.toBuffer(expectedStr,StringUtil.__UTF8_CHARSET);
        Assert.assertThat(prefix + ".payloadLength",actual.getPayloadLength(),is(expected.remaining()));
        ByteBufferAssert.assertEquals(prefix + ".payload",expected,actual.getPayload().slice());
    }

    /**
     * Decode payload example as seen in draft-ietf-hybi-permessage-compression-01.
     */
    @Test
    public void testDraft01_Hello_UnCompressedBlock()
    {
        StringBuilder hex = new StringBuilder();
        // basic, 1 block, compressed with 0 compression level (aka, uncompressed).
        hex.append("0x00 0x05 0x00 0xfa 0xff 0x48 0x65 0x6c 0x6c 0x6f 0x00");
        assertDraftExample(hex.toString(),"Hello");
    }

    /**
     * Decode payload example as seen in draft-ietf-hybi-permessage-compression-01.
     */
    @Test
    public void testDraft01_OneCompressedBlock()
    {
        // basic, 1 block, compressed.
        assertDraftExample("0xf2 0x48 0xcd 0xc9 0xc9 0x07 0x00","Hello");
    }

    /**
     * Decode payload example as seen in draft-ietf-hybi-permessage-compression-01.
     */
    @Test
    public void testDraft01_TwoCompressedBlocks()
    {
        StringBuilder hex = new StringBuilder();
        // BFINAL 0, BTYPE 1, contains "He"
        hex.append("0xf2 0x48 0x05 0x00");
        // BFINAL 0, BTYPE 0, no compression, empty block
        hex.append("0x00 0x00 0xff 0xff");
        // Block containing "llo"
        hex.append("0xca 0xc9 0xc9 0x07 0x00");
        assertDraftExample(hex.toString(),"Hello");
    }

    /**
     * Decode payload example as seen in draft-ietf-hybi-permessage-compression-01.
     */
    @Test
    public void testDraft01_TwoCompressedBlocks_BFinal1()
    {
        StringBuilder hex = new StringBuilder();
        // Compressed with BFINAL 1
        hex.append("0xf3 0x48 0xcd 0xc9 0xc9 0x07 0x00");
        // last octet at BFINAL 0 and BTYPE 0
        hex.append("0x00");
        assertDraftExample(hex.toString(),"Hello");
    }

    /**
     * Decode payload example as seen in draft-ietf-hybi-permessage-compression-01.
     */
    @Test
    public void testDraft01_TwoCompressedBlocks_UsingSlidingWindow()
    {
        StringBuilder hex = new StringBuilder();
        // basic, 1 block, compressed.
        hex.append("0xf2 0x48 0xcd 0xc9 0xc9 0x07 0x00");
        // (HACK!) BFINAL 0, BTYPE 0, no compression, empty block
        hex.append("0x00 0x00 0xff 0xff");
        // if allowed, smaller sized compression using LZ77 sliding window
        hex.append("0xf2 0x00 0x11 0x00 0x00");
        assertDraftExample(hex.toString(),"HelloHello");
    }

    /**
     * Incoming PING (Control Frame) should pass through extension unmodified
     */
    @Test
    public void testIncomingPing() {
        IncomingFramesCapture capture = new IncomingFramesCapture();

        PerMessageCompressionExtension ext = new PerMessageCompressionExtension();
        ext.setBufferPool(new MappedByteBufferPool());
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("permessage-compress");
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

        PerMessageCompressionExtension ext = new PerMessageCompressionExtension();
        ext.setBufferPool(new MappedByteBufferPool());
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("permessage-compress");
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

        PerMessageCompressionExtension ext = new PerMessageCompressionExtension();
        ext.setBufferPool(new MappedByteBufferPool());
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("permessage-compress");
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
            // ByteBuffer uncompressed = ext.inflate(compressed);

            // Assert.assertThat(prefix + ".payloadLength",uncompressed.remaining(),is(expected.remaining()));
            // ByteBufferAssert.assertEquals(prefix + ".payload",expected,uncompressed);
        }
    }

    /**
     * Outgoing PING (Control Frame) should pass through extension unmodified
     */
    @Test
    public void testOutgoingPing() throws IOException
    {
        OutgoingFramesCapture capture = new OutgoingFramesCapture();

        PerMessageCompressionExtension ext = new PerMessageCompressionExtension();
        ext.setBufferPool(new MappedByteBufferPool());
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("permessage-compress");
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
