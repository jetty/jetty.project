//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.extensions;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.ByteBufferAssert;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.Configuration.ConfigurationCustomizer;
import org.eclipse.jetty.websocket.core.DemandingIncomingFramesCapture;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.IncomingFramesCapture;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.OutgoingFramesCapture;
import org.eclipse.jetty.websocket.core.TestMessageHandler;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;
import org.eclipse.jetty.websocket.core.internal.ExtensionStack;
import org.eclipse.jetty.websocket.core.internal.Negotiated;
import org.eclipse.jetty.websocket.core.internal.PerMessageDeflateExtension;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Client side behavioral tests for permessage-deflate extension.
 * <p>
 * See: http://tools.ietf.org/html/draft-ietf-hybi-permessage-compression-15
 */
public class PerMessageDeflateExtensionTest extends AbstractExtensionTest
{
    private void init(PerMessageDeflateExtension ext)
    {
        ext.init(new ExtensionConfig(ext.getName()), components);
    }

    private void assertEndsWithTail(String hexStr, boolean expectedResult)
    {
        ByteBuffer buf = ByteBuffer.wrap(TypeUtil.fromHexString(hexStr));
        assertThat("endsWithTail([" + hexStr + "])", PerMessageDeflateExtension.endsWithTail(buf), is(expectedResult));
    }

    @Test
    public void testEndsWithTailBytes()
    {
        assertEndsWithTail("11223344", false);
        assertEndsWithTail("00", false);
        assertEndsWithTail("0000", false);
        assertEndsWithTail("FFFF0000", false);
        assertEndsWithTail("880000FFFF", true);
        assertEndsWithTail("0000FFFF", true);
    }

    /**
     * Decode payload example as seen in draft-ietf-hybi-permessage-compression-21.
     * <p>
     * Section 8.2.3.1: A message compressed using 1 compressed DEFLATE block
     */
    @Test
    public void testDraft21HelloUnCompressedBlock()
    {
        ExtensionTool.Tester tester = clientExtensions.newTester("permessage-deflate");

        tester.assertNegotiated("permessage-deflate");

        tester.parseIncomingHex(
            // basic, 1 block, compressed with 0 compression level (aka, uncompressed).
            "0xc1 0x07",  // (HEADER added for this test)
            "0xf2 0x48 0xcd 0xc9 0xc9 0x07 0x00" // example frame from RFC
        );

        tester.assertHasFrames("Hello");
    }

    /**
     * Decode payload example as seen in draft-ietf-hybi-permessage-compression-21.
     * <p>
     * Section 8.2.3.1: A message compressed using 1 compressed DEFLATE block (with fragmentation)
     */
    @Test
    public void testDraft21HelloUnCompressedBlockFragmented()
    {
        ExtensionTool.Tester tester = clientExtensions.newTester("permessage-deflate");

        tester.assertNegotiated("permessage-deflate");

        tester.parseIncomingHex(// basic, 1 block, compressed with 0 compression level (aka, uncompressed).
            // Fragment 1
            "0x41 0x03 0xf2 0x48 0xcd",
            // Fragment 2
            "0x80 0x04 0xc9 0xc9 0x07 0x00");

        tester.assertHasFrames(
            new Frame(OpCode.TEXT).setPayload("He").setFin(false),
            new Frame(OpCode.CONTINUATION).setPayload("llo").setFin(true));
    }

    /**
     * Decode payload example as seen in draft-ietf-hybi-permessage-compression-21.
     * <p>
     * Section 8.2.3.2: Sharing LZ77 Sliding Window
     */
    @Test
    public void testDraft21SharingL77SlidingWindowContextTakeover()
    {
        ExtensionTool.Tester tester = clientExtensions.newTester("permessage-deflate");

        tester.assertNegotiated("permessage-deflate");

        tester.parseIncomingHex(// context takeover (2 messages)
            // message 1
            "0xc1 0x07", // (HEADER added for this test)
            "0xf2 0x48 0xcd 0xc9 0xc9 0x07 0x00",
            // message 2
            "0xc1 0x07", // (HEADER added for this test)
            "0xf2 0x48 0xcd 0xc9 0xc9 0x07 0x00");

        tester.assertHasFrames("Hello", "Hello");
    }

    /**
     * Decode payload example as seen in draft-ietf-hybi-permessage-compression-21.
     * <p>
     * Section 8.2.3.2: Sharing LZ77 Sliding Window
     */
    @Test
    public void testDraft21SharingL77SlidingWindowNoContextTakeover()
    {
        ExtensionTool.Tester tester = clientExtensions.newTester("permessage-deflate");

        tester.assertNegotiated("permessage-deflate");

        tester.parseIncomingHex(// 2 message, shared LZ77 window
            // message 1
            "0xc1 0x07", // (HEADER added for this test)
            "0xf2 0x48 0xcd 0xc9 0xc9 0x07 0x00",
            // message 2
            "0xc1 0x05", // (HEADER added for this test)
            "0xf2 0x00 0x11 0x00 0x00"
        );

        tester.assertHasFrames("Hello", "Hello");
    }

    /**
     * Decode payload example as seen in draft-ietf-hybi-permessage-compression-21.
     * <p>
     * Section 8.2.3.3: Using a DEFLATE Block with No Compression
     */
    @Test
    public void testDraft21DeflateBlockWithNoCompression()
    {
        ExtensionTool.Tester tester = clientExtensions.newTester("permessage-deflate");

        tester.assertNegotiated("permessage-deflate");

        tester.parseIncomingHex(// 1 message / no compression
            "0xc1 0x0b 0x00 0x05 0x00 0xfa 0xff 0x48 0x65 0x6c 0x6c 0x6f 0x00" // example frame
        );

        tester.assertHasFrames("Hello");
    }

    /**
     * Decode payload example as seen in draft-ietf-hybi-permessage-compression-21.
     * <p>
     * Section 8.2.3.4: Using a DEFLATE Block with BFINAL Set to 1
     */
    @Test
    public void testDraft21DeflateBlockWithBFinal1()
    {
        ExtensionTool.Tester tester = clientExtensions.newTester("permessage-deflate");

        tester.assertNegotiated("permessage-deflate");

        tester.parseIncomingHex(// 1 message
            "0xc1 0x08", // header
            "0xf3 0x48 0xcd 0xc9 0xc9 0x07 0x00 0x00" // example payload
        );

        tester.assertHasFrames("Hello");
    }

    /**
     * Decode payload example as seen in draft-ietf-hybi-permessage-compression-21.
     * <p>
     * Section 8.2.3.5: Two DEFLATE Blocks in 1 Message
     */
    @Test
    public void testDraft21TwoDeflateBlocksOneMessage()
    {
        ExtensionTool.Tester tester = clientExtensions.newTester("permessage-deflate");

        tester.assertNegotiated("permessage-deflate");

        tester.parseIncomingHex(// 1 message, 1 frame, 2 deflate blocks
            "0xc1 0x0d", // (HEADER added for this test)
            "0xf2 0x48 0x05 0x00 0x00 0x00 0xff 0xff 0xca 0xc9 0xc9 0x07 0x00"
        );

        tester.assertHasFrames("Hello");
    }

    /**
     * Decode fragmented message (3 parts: TEXT, CONTINUATION, CONTINUATION)
     */
    @Test
    public void testParseFragmentedMessageGood()
    {
        ExtensionTool.Tester tester = clientExtensions.newTester("permessage-deflate");

        tester.assertNegotiated("permessage-deflate");

        tester.parseIncomingHex(// 1 message, 3 frame
            "410C", // HEADER TEXT / fin=false / rsv1=true
            "F248CDC9C95700000000FFFF",
            "000B", // HEADER CONTINUATION / fin=false / rsv1=false
            "0ACF2FCA4901000000FFFF",
            "8003", // HEADER CONTINUATION / fin=true / rsv1=false
            "520400"
        );

        Frame txtFrame = new Frame(OpCode.TEXT, false, "Hello ");
        Frame con1Frame = new Frame(OpCode.CONTINUATION, false, "World");
        Frame con2Frame = new Frame(OpCode.CONTINUATION, true, "!");

        tester.assertHasFrames(txtFrame, con1Frame, con2Frame);
    }

    /**
     * Decode fragmented message (3 parts: TEXT, CONTINUATION, CONTINUATION)
     * <p>
     * Continuation frames have RSV1 set, which MUST result in Failure
     * </p>
     */
    @Test
    public void testParseFragmentedMessageBadRsv1()
    {
        ExtensionTool.Tester tester = clientExtensions.newTester("permessage-deflate");

        tester.assertNegotiated("permessage-deflate");

        Throwable t = assertThrows(Throwable.class, () ->
            tester.parseIncomingHex(// 1 message, 3 frame
                "410C", // Header TEXT / fin=false / rsv1=true
                "F248CDC9C95700000000FFFF", // Payload
                "400B", // Header CONTINUATION / fin=false / rsv1=true
                "0ACF2FCA4901000000FFFF", // Payload
                "C003", // Header CONTINUATION / fin=true / rsv1=true
                "520400" // Payload
            ));

        assertThat(t.getCause(), instanceOf(ProtocolException.class));
        assertThat(t.getCause().getMessage(), is("Invalid RSV1 set on permessage-deflate CONTINUATION frame"));
    }

    /**
     * Incoming PING (Control Frame) should pass through extension unmodified
     */
    @Test
    public void testIncomingPing()
    {
        ExtensionConfig config = ExtensionConfig.parse("permessage-deflate");
        WebSocketCoreSession coreSession = newSession(config);
        PerMessageDeflateExtension ext = (PerMessageDeflateExtension)coreSession.getExtensionStack().getExtensions().get(0);

        // Setup capture of incoming frames
        IncomingFramesCapture capture = new IncomingFramesCapture();

        // Wire up stack
        ext.setNextIncomingFrames(capture);

        // Simulate initial demand from onOpen().
        coreSession.autoDemand();

        String payload = "Are you there?";
        Frame ping = new Frame(OpCode.PING).setPayload(payload);
        ext.onFrame(ping, Callback.NOOP);

        capture.assertFrameCount(1);
        capture.assertHasOpCount(OpCode.PING, 1);
        Frame actual = capture.frames.poll();

        assertThat("Frame.opcode", actual.getOpCode(), is(OpCode.PING));
        assertThat("Frame.fin", actual.isFin(), is(true));
        assertThat("Frame.rsv1", actual.isRsv1(), is(false));
        assertThat("Frame.rsv2", actual.isRsv2(), is(false));
        assertThat("Frame.rsv3", actual.isRsv3(), is(false));

        ByteBuffer expected = BufferUtil.toBuffer(payload, StandardCharsets.UTF_8);
        assertThat("Frame.payloadLength", actual.getPayloadLength(), is(expected.remaining()));
        ByteBufferAssert.assertEquals("Frame.payload", expected, actual.getPayload().slice());
    }

    /**
     * Verify that incoming uncompressed frames are properly passed through
     */
    @Test
    public void testIncomingUncompressedFrames()
    {
        ExtensionConfig config = ExtensionConfig.parse("permessage-deflate");
        WebSocketCoreSession coreSession = newSession(config);
        PerMessageDeflateExtension ext = (PerMessageDeflateExtension)coreSession.getExtensionStack().getExtensions().get(0);

        // Setup capture of incoming frames
        IncomingFramesCapture capture = new DemandingIncomingFramesCapture(coreSession);

        // Wire up stack
        ext.setNextIncomingFrames(capture);

        // Simulate initial demand from onOpen().
        coreSession.autoDemand();

        // Quote
        List<String> quote = new ArrayList<>();
        quote.add("No amount of experimentation can ever prove me right;");
        quote.add("a single experiment can prove me wrong.");
        quote.add("-- Albert Einstein");

        // leave frames as-is, no compression, and pass into extension
        for (String q : quote)
        {
            Frame frame = new Frame(OpCode.TEXT).setPayload(q);
            frame.setRsv1(false); // indication to extension that frame is not compressed (ie: a normal frame)
            ext.onFrame(frame, Callback.NOOP);
        }

        int len = quote.size();
        capture.assertFrameCount(len);
        capture.assertHasOpCount(OpCode.TEXT, len);

        String prefix;
        int i = 0;
        for (Frame actual : capture.frames)
        {
            prefix = "Frame[" + i + "]";

            assertThat(prefix + ".opcode", actual.getOpCode(), is(OpCode.TEXT));
            assertThat(prefix + ".fin", actual.isFin(), is(true));
            assertThat(prefix + ".rsv1", actual.isRsv1(), is(false));
            assertThat(prefix + ".rsv2", actual.isRsv2(), is(false));
            assertThat(prefix + ".rsv3", actual.isRsv3(), is(false));

            ByteBuffer expected = BufferUtil.toBuffer(quote.get(i), StandardCharsets.UTF_8);
            assertThat(prefix + ".payloadLength", actual.getPayloadLength(), is(expected.remaining()));
            ByteBufferAssert.assertEquals(prefix + ".payload", expected, actual.getPayload().slice());
            i++;
        }
    }

    @Test
    public void testIncomingFrameNoPayload()
    {
        ExtensionConfig config = ExtensionConfig.parse("permessage-deflate");
        WebSocketCoreSession coreSession = newSession(config);
        PerMessageDeflateExtension ext = (PerMessageDeflateExtension)coreSession.getExtensionStack().getExtensions().get(0);

        // Setup capture of incoming frames
        IncomingFramesCapture capture = new IncomingFramesCapture();

        // Wire up stack
        ext.setNextIncomingFrames(capture);

        // Simulate initial demand from onOpen().
        coreSession.autoDemand();

        Frame ping = new Frame(OpCode.TEXT);
        ping.setRsv1(true);
        ext.onFrame(ping, Callback.NOOP);

        capture.assertFrameCount(1);
        Frame actual = capture.frames.poll();

        assertThat("Frame.opcode", actual.getOpCode(), is(OpCode.TEXT));
        assertThat("Frame.fin", actual.isFin(), is(true));
        assertThat("Frame.rsv1", actual.isRsv1(), is(false));
        assertThat("Frame.rsv2", actual.isRsv2(), is(false));
        assertThat("Frame.rsv3", actual.isRsv3(), is(false));

        assertThat("Frame.payloadLength", actual.getPayloadLength(), is(0));
        assertThat("Frame.payload", actual.getPayload(), is(BufferUtil.EMPTY_BUFFER));
    }

    /**
     * Outgoing PING (Control Frame) should pass through extension unmodified
     *
     * @throws IOException on test failure
     */
    @Test
    public void testOutgoingPing() throws IOException
    {
        PerMessageDeflateExtension ext = new PerMessageDeflateExtension();
        ExtensionConfig config = ExtensionConfig.parse("permessage-deflate");
        ext.init(config, components);

        // Setup capture of outgoing frames
        OutgoingFramesCapture capture = new OutgoingFramesCapture();

        // Wire up stack
        ext.setNextOutgoingFrames(capture);

        String payload = "Are you there?";
        Frame ping = new Frame(OpCode.PING).setPayload(payload);

        ext.sendFrame(ping, null, false);

        capture.assertFrameCount(1);
        capture.assertHasOpCount(OpCode.PING, 1);

        Frame actual = capture.frames.poll();

        assertThat("Frame.opcode", actual.getOpCode(), is(OpCode.PING));
        assertThat("Frame.fin", actual.isFin(), is(true));
        assertThat("Frame.rsv1", actual.isRsv1(), is(false));
        assertThat("Frame.rsv2", actual.isRsv2(), is(false));
        assertThat("Frame.rsv3", actual.isRsv3(), is(false));

        ByteBuffer expected = BufferUtil.toBuffer(payload, StandardCharsets.UTF_8);
        assertThat("Frame.payloadLength", actual.getPayloadLength(), is(expected.remaining()));
        ByteBufferAssert.assertEquals("Frame.payload", expected, actual.getPayload().slice());
    }

    /**
     * Outgoing Fragmented Message
     *
     * @throws IOException on test failure
     */
    @Test
    public void testOutgoingFragmentedMessage() throws IOException, InterruptedException
    {
        PerMessageDeflateExtension ext = new PerMessageDeflateExtension();
        ext.init(ExtensionConfig.parse("permessage-deflate"), components);
        ext.setCoreSession(newSession());

        // Setup capture of outgoing frames
        OutgoingFramesCapture capture = new OutgoingFramesCapture();

        // Wire up stack
        ext.setNextOutgoingFrames(capture);

        Frame txtFrame = new Frame(OpCode.TEXT, false, "Hello ");
        Frame con1Frame = new Frame(OpCode.CONTINUATION, false, "World");
        Frame con2Frame = new Frame(OpCode.CONTINUATION, true, "!");
        ext.sendFrame(txtFrame, Callback.NOOP, false);
        ext.sendFrame(con1Frame, Callback.NOOP, false);
        ext.sendFrame(con2Frame, Callback.NOOP, false);

        capture.assertFrameCount(3);

        Frame capturedFrame;

        capturedFrame = capture.frames.poll(1, TimeUnit.SECONDS);
        assertThat("Frame.opcode", capturedFrame.getOpCode(), is(OpCode.TEXT));
        assertThat("Frame.fin", capturedFrame.isFin(), is(false));
        assertThat("Frame.rsv1", capturedFrame.isRsv1(), is(true));
        assertThat("Frame.rsv2", capturedFrame.isRsv2(), is(false));
        assertThat("Frame.rsv3", capturedFrame.isRsv3(), is(false));

        capturedFrame = capture.frames.poll(1, TimeUnit.SECONDS);
        assertThat("Frame.opcode", capturedFrame.getOpCode(), is(OpCode.CONTINUATION));
        assertThat("Frame.fin", capturedFrame.isFin(), is(false));
        assertThat("Frame.rsv1", capturedFrame.isRsv1(), is(false));
        assertThat("Frame.rsv2", capturedFrame.isRsv2(), is(false));
        assertThat("Frame.rsv3", capturedFrame.isRsv3(), is(false));

        capturedFrame = capture.frames.poll(1, TimeUnit.SECONDS);
        assertThat("Frame.opcode", capturedFrame.getOpCode(), is(OpCode.CONTINUATION));
        assertThat("Frame.fin", capturedFrame.isFin(), is(true));
        assertThat("Frame.rsv1", capturedFrame.isRsv1(), is(false));
        assertThat("Frame.rsv2", capturedFrame.isRsv2(), is(false));
        assertThat("Frame.rsv3", capturedFrame.isRsv3(), is(false));
    }

    @Test
    public void testOutgoingFrameNoPayload()
    {
        PerMessageDeflateExtension ext = new PerMessageDeflateExtension();
        ExtensionConfig config = ExtensionConfig.parse("permessage-deflate");
        ext.init(config, components);
        ext.setCoreSession(newSession());

        // Setup capture of incoming frames
        OutgoingFramesCapture capture = new OutgoingFramesCapture();

        // Wire up stack
        ext.setNextOutgoingFrames(capture);

        Frame frame = new Frame(OpCode.TEXT);
        ext.sendFrame(frame, Callback.NOOP, false);

        capture.assertFrameCount(1);
        Frame actual = capture.frames.poll();

        assertThat("Frame.opcode", actual.getOpCode(), is(OpCode.TEXT));
        assertThat("Frame.fin", actual.isFin(), is(true));
        assertThat("Frame.rsv1", actual.isRsv1(), is(true));
        assertThat("Frame.rsv2", actual.isRsv2(), is(false));
        assertThat("Frame.rsv3", actual.isRsv3(), is(false));

        assertThat("Frame.payloadLength", actual.getPayloadLength(), is(1));
        //assertThat("Frame.payload", actual.getPayload(), is(BufferUtil.EMPTY_BUFFER));
    }

    @Test
    public void testPyWebSocketClientNoContextTakeoverThreeOra()
    {
        ExtensionTool.Tester tester = clientExtensions.newTester("permessage-deflate; client_max_window_bits; client_no_context_takeover");

        tester.assertNegotiated("permessage-deflate");

        // Captured from Pywebsocket (r790) - 3 messages with similar parts.

        tester.parseIncomingHex(
            // context takeover (3 messages)
            "c1 09 0a c9 2f 4a 0c 01  62 00 00", // ToraTora
            "c1 0b 72 2c c9 2f 4a 74  cb 01 12 00 00", // AtoraFlora
            "c1 0b 0a c8 c8 c9 2f 4a  0c 01 62 00 00" // PhloraTora
        );

        tester.assertHasFrames("ToraTora", "AtoraFlora", "PhloraTora");
    }

    @Test
    public void testPyWebSocketClientToraToraTora()
    {
        ExtensionTool.Tester tester = clientExtensions.newTester("permessage-deflate; client_max_window_bits");

        tester.assertNegotiated("permessage-deflate");

        // Captured from Pywebsocket (r790) - "tora" sent 3 times.

        tester.parseIncomingHex(
            // context takeover (3 messages)
            "c1 06 2a c9 2f 4a 04 00", // tora 1
            "c1 05 2a 01 62 00 00", // tora 2
            "c1 04 02 61 00 00" // tora 3
        );

        tester.assertHasFrames("tora", "tora", "tora");
    }

    @Test
    public void testPyWebSocketServerNoContextTakeoverThreeOra()
    {
        ExtensionTool.Tester tester = serverExtensions.newTester("permessage-deflate; client_max_window_bits; client_no_context_takeover");

        tester.assertNegotiated("permessage-deflate");

        // Captured from Pywebsocket (r790) - 3 messages with similar parts.

        tester.parseIncomingHex(// context takeover (3 messages)
            "c1 89 88 bc 1b b1 82 75  34 fb 84 bd 79 b1 88", // ToraTora
            "c1 8b 50 86 88 b2 22 aa  41 9d 1a f2 43 b3 42 86 88", // AtoraFlora
            "c1 8b e2 3e 05 53 e8 f6  cd 9a cd 74 09 52 80 3e 05" // PhloraTora
        );

        tester.assertHasFrames("ToraTora", "AtoraFlora", "PhloraTora");
    }

    @Test
    public void testPyWebSocketServerToraToraTora()
    {
        ExtensionTool.Tester tester = serverExtensions.newTester("permessage-deflate; client_max_window_bits");

        tester.assertNegotiated("permessage-deflate");

        // Captured from Pywebsocket (r790) - "tora" sent 3 times.

        tester.parseIncomingHex(// context takeover (3 messages)
            "c1 86 69 39 fe 91 43 f0  d1 db 6d 39", // tora 1
            "c1 85 2d f3 eb 96 07 f2  89 96 2d", // tora 2
            "c1 84 53 ad a5 34 51 cc  a5 34" // tora 3
        );

        tester.assertHasFrames("tora", "tora", "tora");
    }

    private WebSocketCoreSession newSession()
    {
        return newSession(null);
    }

    private WebSocketCoreSession newSession(ExtensionConfig config)
    {
        return newSessionFromConfig(new ConfigurationCustomizer(), config == null ? Collections.emptyList() : Collections.singletonList(config));
    }

    private WebSocketCoreSession newSessionFromConfig(ConfigurationCustomizer configuration, List<ExtensionConfig> configs)
    {
        ExtensionStack exStack = new ExtensionStack(components, Behavior.SERVER);
        exStack.negotiate(configs, configs);
        exStack.setLastDemand(l -> {}); // Never delegate to WebSocketConnection as it is null for this test.
        WebSocketCoreSession coreSession = new WebSocketCoreSession(new TestMessageHandler(), Behavior.SERVER, Negotiated.from(exStack), components);
        configuration.customize(configuration);
        return coreSession;
    }
}
