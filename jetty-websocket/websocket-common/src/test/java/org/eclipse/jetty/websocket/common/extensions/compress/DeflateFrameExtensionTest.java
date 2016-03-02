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

package org.eclipse.jetty.websocket.common.extensions.compress;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.extensions.AbstractExtensionTest;
import org.eclipse.jetty.websocket.common.extensions.ExtensionTool.Tester;
import org.eclipse.jetty.websocket.common.frames.BinaryFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.ByteBufferAssert;
import org.eclipse.jetty.websocket.common.test.IncomingFramesCapture;
import org.eclipse.jetty.websocket.common.test.LeakTrackingBufferPoolRule;
import org.eclipse.jetty.websocket.common.test.OutgoingNetworkBytesCapture;
import org.eclipse.jetty.websocket.common.test.UnitParser;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class DeflateFrameExtensionTest extends AbstractExtensionTest
{
    private static final Logger LOG = Log.getLogger(DeflateFrameExtensionTest.class);
    
    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("Test");

    private void assertIncoming(byte[] raw, String... expectedTextDatas)
    {
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();

        DeflateFrameExtension ext = new DeflateFrameExtension();
        ext.setBufferPool(bufferPool);
        ext.setPolicy(policy);

        ExtensionConfig config = ExtensionConfig.parse("deflate-frame");
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
        capture.assertHasFrame(OpCode.TEXT, len);

        int i = 0;
        for (WebSocketFrame actual : capture.getFrames())
        {
            String prefix = "Frame[" + i + "]";
            Assert.assertThat(prefix + ".opcode", actual.getOpCode(), is(OpCode.TEXT));
            Assert.assertThat(prefix + ".fin", actual.isFin(), is(true));
            Assert.assertThat(prefix + ".rsv1", actual.isRsv1(), is(false)); // RSV1 should be unset at this point
            Assert.assertThat(prefix + ".rsv2", actual.isRsv2(), is(false));
            Assert.assertThat(prefix + ".rsv3", actual.isRsv3(), is(false));

            ByteBuffer expected = BufferUtil.toBuffer(expectedTextDatas[i], StandardCharsets.UTF_8);
            Assert.assertThat(prefix + ".payloadLength", actual.getPayloadLength(), is(expected.remaining()));
            ByteBufferAssert.assertEquals(prefix + ".payload", expected, actual.getPayload().slice());
            i++;
        }
    }

    private void assertOutgoing(String text, String expectedHex) throws IOException
    {
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();

        DeflateFrameExtension ext = new DeflateFrameExtension();
        ext.setBufferPool(bufferPool);
        ext.setPolicy(policy);

        ExtensionConfig config = ExtensionConfig.parse("deflate-frame");
        ext.setConfig(config);

        Generator generator = new Generator(policy, bufferPool, true);
        generator.configureFromExtensions(Collections.singletonList(ext));

        OutgoingNetworkBytesCapture capture = new OutgoingNetworkBytesCapture(generator);
        ext.setNextOutgoingFrames(capture);

        Frame frame = new TextFrame().setPayload(text);
        ext.outgoingFrame(frame, null, BatchMode.OFF);

        capture.assertBytes(0, expectedHex);
    }

    @Test
    public void testBlockheadClient_HelloThere()
    {
        Tester tester = serverExtensions.newTester("deflate-frame");

        tester.assertNegotiated("deflate-frame");

        tester.parseIncomingHex(// Captured from Blockhead Client - "Hello" then "There" via unit test
                "c18700000000f248cdc9c90700", // "Hello"
                "c187000000000ac9482d4a0500" // "There"
        );

        tester.assertHasFrames("Hello", "There");
    }

    @Test
    public void testChrome20_Hello()
    {
        Tester tester = serverExtensions.newTester("deflate-frame");

        tester.assertNegotiated("deflate-frame");

        tester.parseIncomingHex(// Captured from Chrome 20.x - "Hello" (sent from browser)
                "c187832b5c11716391d84a2c5c" // "Hello"
        );

        tester.assertHasFrames("Hello");
    }

    @Test
    public void testChrome20_HelloThere()
    {
        Tester tester = serverExtensions.newTester("deflate-frame");

        tester.assertNegotiated("deflate-frame");

        tester.parseIncomingHex(// Captured from Chrome 20.x - "Hello" then "There" (sent from browser)
                "c1877b1971db8951bc12b21e71", // "Hello"
                "c18759edc8f4532480d913e8c8" // There
        );

        tester.assertHasFrames("Hello", "There");
    }

    @Test
    public void testChrome20_Info()
    {
        Tester tester = serverExtensions.newTester("deflate-frame");

        tester.assertNegotiated("deflate-frame");

        tester.parseIncomingHex(// Captured from Chrome 20.x - "info:" (sent from browser)
                "c187ca4def7f0081a4b47d4fef" // example payload 
        );

        tester.assertHasFrames("info:");
    }

    @Test
    public void testChrome20_TimeTime()
    {
        Tester tester = serverExtensions.newTester("deflate-frame");

        tester.assertNegotiated("deflate-frame");

        tester.parseIncomingHex(// Captured from Chrome 20.x - "time:" then "time:" once more (sent from browser)
                "c18782467424a88fb869374474", // "time:"
                "c1853cfda17f16fcb07f3c" // "time:"
        );

        tester.assertHasFrames("time:", "time:");
    }

    @Test
    public void testPyWebSocket_TimeTimeTime()
    {
        Tester tester = serverExtensions.newTester("deflate-frame");

        tester.assertNegotiated("deflate-frame");

        tester.parseIncomingHex(// Captured from Pywebsocket (r781) - "time:" sent 3 times.
                "c1876b100104" + "41d9cd49de1201", // "time:"
                "c1852ae3ff01" + "00e2ee012a", // "time:"
                "c18435558caa" + "37468caa" // "time:"
        );

        tester.assertHasFrames("time:", "time:", "time:");
    }

    @Test
    public void testCompress_TimeTimeTime()
    {
        // What pywebsocket produces for "time:", "time:", "time:"
        String expected[] = new String[]
                {"2AC9CC4DB50200", "2A01110000", "02130000"};

        // Lets see what we produce
        CapturedHexPayloads capture = new CapturedHexPayloads();
        DeflateFrameExtension ext = new DeflateFrameExtension();
        init(ext);
        ext.setNextOutgoingFrames(capture);

        ext.outgoingFrame(new TextFrame().setPayload("time:"), null, BatchMode.OFF);
        ext.outgoingFrame(new TextFrame().setPayload("time:"), null, BatchMode.OFF);
        ext.outgoingFrame(new TextFrame().setPayload("time:"), null, BatchMode.OFF);

        List<String> actual = capture.getCaptured();

        Assert.assertThat("Compressed Payloads", actual, contains(expected));
    }

    private void init(DeflateFrameExtension ext)
    {
        ext.setConfig(new ExtensionConfig(ext.getName()));
        ext.setBufferPool(bufferPool);
    }

    @Test
    public void testDeflateBasics() throws Exception
    {
        // Setup deflater basics
        Deflater compressor = new Deflater(Deflater.BEST_COMPRESSION, true);
        compressor.setStrategy(Deflater.DEFAULT_STRATEGY);

        // Text to compress
        String text = "info:";
        byte uncompressed[] = StringUtil.getUtf8Bytes(text);

        // Prime the compressor
        compressor.reset();
        compressor.setInput(uncompressed, 0, uncompressed.length);
        compressor.finish();

        // Perform compression
        ByteBuffer outbuf = ByteBuffer.allocate(64);
        BufferUtil.clearToFill(outbuf);

        while (!compressor.finished())
        {
            byte out[] = new byte[64];
            int len = compressor.deflate(out, 0, out.length, Deflater.SYNC_FLUSH);
            if (len > 0)
            {
                outbuf.put(out, 0, len);
            }
        }
        compressor.end();

        BufferUtil.flipToFlush(outbuf, 0);
        byte compressed[] = BufferUtil.toArray(outbuf);
        // Clear the BFINAL bit that has been set by the compressor.end() call.
        // In the real implementation we never end() the compressor.
        compressed[0] &= 0xFE;

        String actual = TypeUtil.toHexString(compressed);
        String expected = "CaCc4bCbB70200"; // what pywebsocket produces

        Assert.assertThat("Compressed data", actual, is(expected));
    }

    @Test
    public void testGeneratedTwoFrames() throws IOException
    {
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();

        DeflateFrameExtension ext = new DeflateFrameExtension();
        ext.setBufferPool(bufferPool);
        ext.setPolicy(policy);
        ext.setConfig(new ExtensionConfig(ext.getName()));

        Generator generator = new Generator(policy, bufferPool, true);
        generator.configureFromExtensions(Collections.singletonList(ext));

        OutgoingNetworkBytesCapture capture = new OutgoingNetworkBytesCapture(generator);
        ext.setNextOutgoingFrames(capture);

        ext.outgoingFrame(new TextFrame().setPayload("Hello"), null, BatchMode.OFF);
        ext.outgoingFrame(new TextFrame().setPayload("There"), null, BatchMode.OFF);

        capture.assertBytes(0, "c107f248cdc9c90700");
    }

    @Test
    public void testInflateBasics() throws Exception
    {
        // should result in "info:" text if properly inflated
        byte rawbuf[] = TypeUtil.fromHexString("CaCc4bCbB70200"); // what pywebsocket produces
        // byte rawbuf[] = TypeUtil.fromHexString("CbCc4bCbB70200"); // what java produces

        Inflater inflater = new Inflater(true);
        inflater.reset();
        inflater.setInput(rawbuf, 0, rawbuf.length);

        byte outbuf[] = new byte[64];
        int len = inflater.inflate(outbuf);
        inflater.end();
        Assert.assertThat("Inflated length", len, greaterThan(4));

        String actual = StringUtil.toUTF8String(outbuf, 0, len);
        Assert.assertThat("Inflated text", actual, is("info:"));
    }

    @Test
    public void testPyWebSocketServer_Hello()
    {
        // Captured from PyWebSocket - "Hello" (echo from server)
        byte rawbuf[] = TypeUtil.fromHexString("c107f248cdc9c90700");
        assertIncoming(rawbuf, "Hello");
    }

    @Test
    public void testPyWebSocketServer_Long()
    {
        // Captured from PyWebSocket - Long Text (echo from server)
        byte rawbuf[] = TypeUtil.fromHexString("c1421cca410a80300c44d1abccce9df7" + "f018298634d05631138ab7b7b8fdef1f" + "dc0282e2061d575a45f6f2686bab25e1"
                + "3fb7296fa02b5885eb3b0379c394f461" + "98cafd03");
        assertIncoming(rawbuf, "It's a big enough umbrella but it's always me that ends up getting wet.");
    }

    @Test
    public void testPyWebSocketServer_Medium()
    {
        // Captured from PyWebSocket - "stackoverflow" (echo from server)
        byte rawbuf[] = TypeUtil.fromHexString("c10f2a2e494ccece2f4b2d4acbc92f0700");
        assertIncoming(rawbuf, "stackoverflow");
    }

    /**
     * Make sure that the server generated compressed form for "Hello" is consistent with what PyWebSocket creates.
     * @throws IOException on test failure
     */
    @Test
    public void testServerGeneratedHello() throws IOException
    {
        assertOutgoing("Hello", "c107f248cdc9c90700");
    }

    /**
     * Make sure that the server generated compressed form for "There" is consistent with what PyWebSocket creates.
     * @throws IOException on test failure
     */
    @Test
    public void testServerGeneratedThere() throws IOException
    {
        assertOutgoing("There", "c1070ac9482d4a0500");
    }

    @Test
    public void testCompressAndDecompressBigPayload() throws Exception
    {
        byte[] input = new byte[1024 * 1024];
        // Make them not compressible.
        new Random().nextBytes(input);
        
        int maxMessageSize = (1024 * 1024) + 8192;
        
        DeflateFrameExtension clientExtension = new DeflateFrameExtension();
        clientExtension.setBufferPool(bufferPool);
        clientExtension.setPolicy(WebSocketPolicy.newClientPolicy());
        clientExtension.getPolicy().setMaxBinaryMessageSize(maxMessageSize);
        clientExtension.getPolicy().setMaxBinaryMessageBufferSize(maxMessageSize);
        clientExtension.setConfig(ExtensionConfig.parse("deflate-frame"));

        final DeflateFrameExtension serverExtension = new DeflateFrameExtension();
        serverExtension.setBufferPool(bufferPool);
        serverExtension.setPolicy(WebSocketPolicy.newServerPolicy());
        serverExtension.getPolicy().setMaxBinaryMessageSize(maxMessageSize);
        serverExtension.getPolicy().setMaxBinaryMessageBufferSize(maxMessageSize);
        serverExtension.setConfig(ExtensionConfig.parse("deflate-frame"));

        // Chain the next element to decompress.
        clientExtension.setNextOutgoingFrames(new OutgoingFrames()
        {
            @Override
            public void outgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
            {
                LOG.debug("outgoingFrame({})", frame);
                serverExtension.incomingFrame(frame);
                callback.writeSuccess();
            }
        });

        final ByteArrayOutputStream result = new ByteArrayOutputStream(input.length);
        serverExtension.setNextIncomingFrames(new IncomingFrames()
        {
            @Override
            public void incomingFrame(Frame frame)
            {
                LOG.debug("incomingFrame({})", frame);
                try
                {
                    result.write(BufferUtil.toArray(frame.getPayload()));
                }
                catch (IOException x)
                {
                    throw new RuntimeIOException(x);
                }
            }

            @Override
            public void incomingError(Throwable t)
            {
            }
        });

        BinaryFrame frame = new BinaryFrame();
        frame.setPayload(input);
        frame.setFin(true);
        clientExtension.outgoingFrame(frame, null, BatchMode.OFF);

        Assert.assertArrayEquals(input, result.toByteArray());
    }
}
