//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.ByteBufferAssert;
import org.eclipse.jetty.websocket.common.Hex;
import org.eclipse.jetty.websocket.common.IncomingFramesCapture;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.OutgoingFramesCapture;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.UnitParser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.PingFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class PerMessageDeflateExtensionTest
{
    @Rule
    public TestName testname = new TestName();

    private void assertIncoming(byte[] raw, String... expectedTextDatas)
    {
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();

        PerMessageDeflateExtension ext = new PerMessageDeflateExtension();
        ext.setBufferPool(new MappedByteBufferPool());
        ext.setPolicy(policy);

        ExtensionConfig config = ExtensionConfig.parse("permessage-deflate; c2s_max_window_bits");
        ext.setConfig(config);

        // Setup capture of incoming frames
        IncomingFramesCapture capture = new IncomingFramesCapture();

        // Wire up stack
        ext.setNextIncomingFrames(capture);

        Parser parser = new UnitParser(policy);
        parser.configureFromExtensions(Collections.singletonList(ext));
        parser.setIncomingFramesHandler(ext);

        parser.parse(ByteBuffer.wrap(raw));

        int len = expectedTextDatas.length;
        capture.assertFrameCount(len);
        capture.assertHasFrame(OpCode.TEXT,len);

        for (int i = 0; i < len; i++)
        {
            WebSocketFrame actual = capture.getFrames().get(i);
            String prefix = "Frame[" + i + "]";
            Assert.assertThat(prefix + ".opcode",actual.getOpCode(),is(OpCode.TEXT));
            Assert.assertThat(prefix + ".fin",actual.isFin(),is(true));
            Assert.assertThat(prefix + ".rsv1",actual.isRsv1(),is(false)); // RSV1 should be unset at this point
            Assert.assertThat(prefix + ".rsv2",actual.isRsv2(),is(false));
            Assert.assertThat(prefix + ".rsv3",actual.isRsv3(),is(false));

            ByteBuffer expected = BufferUtil.toBuffer(expectedTextDatas[i],StringUtil.__UTF8_CHARSET);
            Assert.assertThat(prefix + ".payloadLength",actual.getPayloadLength(),is(expected.remaining()));
            ByteBufferAssert.assertEquals(prefix + ".payload",expected,actual.getPayload().slice());
        }
    }

    private void assertDraftExample(String hexStr, String expectedStr)
    {
        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();

        // Setup extension
        PerMessageDeflateExtension ext = new PerMessageDeflateExtension();
        ext.setBufferPool(new MappedByteBufferPool());
        ext.setPolicy(policy);
        ExtensionConfig config = ExtensionConfig.parse("permessage-deflate");
        ext.setConfig(config);

        // Setup capture of incoming frames
        IncomingFramesCapture capture = new IncomingFramesCapture();

        // Wire up stack
        ext.setNextIncomingFrames(capture);

        // Receive frame
        String hex = hexStr.replaceAll("\\s*0x","");
        byte net[] = TypeUtil.fromHexString(hex);
        TextFrame frame = new TextFrame();
        frame.setRsv1(true);
        frame.setPayload(ByteBuffer.wrap(net));

        // Send frame into stack
        ext.incomingFrame(frame);

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

    private void assertDraft12Example(String hexStrCompleteFrame, String... expectedStrs)
    {
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();

        // Setup extension
        PerMessageDeflateExtension ext = new PerMessageDeflateExtension();
        ext.setBufferPool(new MappedByteBufferPool());
        ext.setPolicy(policy);
        ExtensionConfig config = ExtensionConfig.parse("permessage-deflate");
        ext.setConfig(config);

        // Setup capture of incoming frames
        IncomingFramesCapture capture = new IncomingFramesCapture();

        // Wire up stack
        ext.setNextIncomingFrames(capture);

        // Receive frame
        String hex = hexStrCompleteFrame.replaceAll("\\s*0x","");
        byte net[] = TypeUtil.fromHexString(hex);

        Parser parser = new UnitParser(policy);
        parser.configureFromExtensions(Collections.singletonList(ext));
        parser.setIncomingFramesHandler(ext);
        parser.parse(ByteBuffer.wrap(net));

        // Verify captured frames.
        int expectedCount = expectedStrs.length;
        capture.assertFrameCount(expectedCount);
        capture.assertHasFrame(OpCode.TEXT,expectedCount);

        for (int i = 0; i < expectedCount; i++)
        {
            WebSocketFrame actual = capture.getFrames().pop();

            String prefix = String.format("frame[%d]",i);
            Assert.assertThat(prefix + ".opcode",actual.getOpCode(),is(OpCode.TEXT));
            Assert.assertThat(prefix + ".fin",actual.isFin(),is(true));
            Assert.assertThat(prefix + ".rsv1",actual.isRsv1(),is(false)); // RSV1 should be unset at this point
            Assert.assertThat(prefix + ".rsv2",actual.isRsv2(),is(false));
            Assert.assertThat(prefix + ".rsv3",actual.isRsv3(),is(false));

            ByteBuffer expected = BufferUtil.toBuffer(expectedStrs[i],StringUtil.__UTF8_CHARSET);
            Assert.assertThat(prefix + ".payloadLength",actual.getPayloadLength(),is(expected.remaining()));
            ByteBufferAssert.assertEquals(prefix + ".payload",expected,actual.getPayload().slice());
        }
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
     * Decode payload example as seen in draft-ietf-hybi-permessage-compression-12. Section 8.2.3.1
     */
    @Test
    public void testDraft12_Hello_UnCompressedBlock()
    {
        StringBuilder hex = new StringBuilder();
        // basic, 1 block, compressed with 0 compression level (aka, uncompressed).
        hex.append("0xc1 0x07 0xf2 0x48 0xcd 0xc9 0xc9 0x07 0x00");
        assertDraft12Example(hex.toString(),"Hello");
    }

    /**
     * Decode payload example as seen in draft-ietf-hybi-permessage-compression-12. Section 8.2.3.2
     */
    @Test
    public void testDraft12_Hello_NoSharingLZ77SlidingWindow()
    {
        StringBuilder hex = new StringBuilder();
        // message 1
        hex.append("0xc1 0x07"); // (HEADER added for this test)
        hex.append("0xf2 0x48 0xcd 0xc9 0xc9 0x07 0x00");
        // message 2
        hex.append("0xc1 0x07"); // (HEADER added for this test)
        hex.append("0xf2 0x48 0xcd 0xc9 0xc9 0x07 0x00");
        assertDraft12Example(hex.toString(),"Hello","Hello");
    }
    
    /**
     * Decode payload example as seen in draft-ietf-hybi-permessage-compression-12. Section 8.2.3.2
     */
    @Test
    public void testDraft12_Hello_SharingLZ77SlidingWindow()
    {
        StringBuilder hex = new StringBuilder();
        // message 1
        hex.append("0xc1 0x07"); // (HEADER added for this test)
        hex.append("0xf2 0x48 0xcd 0xc9 0xc9 0x07 0x00");
        // message 2
        hex.append("0xc1 0x05"); // (HEADER added for this test)
        hex.append("0xf2 0x00 0x11 0x00 0x00");
        assertDraft12Example(hex.toString(),"Hello","Hello");
    }

    /**
     * Decode payload example as seen in draft-ietf-hybi-permessage-compression-12. Section 8.2.3.3
     */
    @Test
    public void testDraft12_Hello_NoCompressionBlock()
    {
        StringBuilder hex = new StringBuilder();
        // basic, 1 block, compressed with no compression.
        hex.append("0xc1 0x0b 0x00 0x05 0x00 0xfa 0xff 0x48 0x65 0x6c 0x6c 0x6f 0x00");
        assertDraft12Example(hex.toString(),"Hello");
    }

    /**
     * Decode payload example as seen in draft-ietf-hybi-permessage-compression-12. Section 8.2.3.4
     */
    @Test
    public void testDraft12_Hello_Bfinal1()
    {
        StringBuilder hex = new StringBuilder();
        // basic, 1 block, compressed with BFINAL set to 1.
        hex.append("0xc1 0x08"); // (HEADER added for this test)
        hex.append("0xf3 0x48 0xcd 0xc9 0xc9 0x07 0x00 0x00");
        assertDraft12Example(hex.toString(),"Hello");
    }

    /**
     * Decode payload example as seen in draft-ietf-hybi-permessage-compression-12. Section 8.2.3.5
     */
    @Test
    public void testDraft12_Hello_TwoDeflateBlocks()
    {
        StringBuilder hex = new StringBuilder();
        hex.append("0xc1 0x0d"); // (HEADER added for this test)
        // 2 deflate blocks
        hex.append("0xf2 0x48 0x05 0x00 0x00 0x00 0xff 0xff 0xca 0xc9 0xc9 0x07 0x00");
        assertDraft12Example(hex.toString(),"Hello");
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

    @Test
    public void testPyWebSocket_ToraToraTora()
    {
        // Captured from Pywebsocket (r781) - "tora" sent 3 times.
        String tora1 = "c186b0c7fe48" + "9a0ed102b4c7";
        String tora2 = "c185ccb6cb50" + "e6b7a950cc";
        String tora3 = "c1847b9aac69" + "79fbac69";
        byte rawbuf[] = Hex.asByteArray(tora1 + tora2 + tora3);
        assertIncoming(rawbuf,"tora","tora","tora");
    }

    /**
     * Incoming PING (Control Frame) should pass through extension unmodified
     */
    @Test
    public void testIncomingPing()
    {
        PerMessageDeflateExtension ext = new PerMessageDeflateExtension();
        ext.setBufferPool(new MappedByteBufferPool());
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("permessage-compress");
        ext.setConfig(config);

        // Setup capture of incoming frames
        IncomingFramesCapture capture = new IncomingFramesCapture();

        // Wire up stack
        ext.setNextIncomingFrames(capture);

        String payload = "Are you there?";
        Frame ping = new PingFrame().setPayload(payload);
        ext.incomingFrame(ping);

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
        PerMessageDeflateExtension ext = new PerMessageDeflateExtension();
        ext.setBufferPool(new MappedByteBufferPool());
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("permessage-compress");
        ext.setConfig(config);

        // Setup capture of incoming frames
        IncomingFramesCapture capture = new IncomingFramesCapture();

        // Wire up stack
        ext.setNextIncomingFrames(capture);

        // Quote
        List<String> quote = new ArrayList<>();
        quote.add("No amount of experimentation can ever prove me right;");
        quote.add("a single experiment can prove me wrong.");
        quote.add("-- Albert Einstein");

        // leave frames as-is, no compression, and pass into extension
        for (String q : quote)
        {
            TextFrame frame = new TextFrame().setPayload(q);
            frame.setRsv1(false); // indication to extension that frame is not compressed (ie: a normal frame)
            ext.incomingFrame(frame);
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
     * Outgoing PING (Control Frame) should pass through extension unmodified
     */
    @Test
    public void testOutgoingPing() throws IOException
    {
        PerMessageDeflateExtension ext = new PerMessageDeflateExtension();
        ext.setBufferPool(new MappedByteBufferPool());
        ext.setPolicy(WebSocketPolicy.newServerPolicy());
        ExtensionConfig config = ExtensionConfig.parse("permessage-compress");
        ext.setConfig(config);

        // Setup capture of outgoing frames
        OutgoingFramesCapture capture = new OutgoingFramesCapture();

        // Wire up stack
        ext.setNextOutgoingFrames(capture);

        String payload = "Are you there?";
        Frame ping = new PingFrame().setPayload(payload);

        ext.outgoingFrame(ping,null);

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
}
