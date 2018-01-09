//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.toolchain.test.ByteBufferAssert;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Generator;
import org.eclipse.jetty.websocket.core.Parser;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;
import org.hamcrest.Matchers;

public class LocalFuzzer implements AutoCloseable
{
    private static final Logger LOG = Log.getLogger(LocalFuzzer.class);
    public final Provider provider;
    public final UnitGenerator generator;
    public final LocalConnector.LocalEndPoint endPoint;
    public final HttpTester.Response upgradeResponse;

    public LocalFuzzer(Provider provider) throws Exception
    {
        this(provider, null);
    }

    public LocalFuzzer(Provider provider, CharSequence requestPath) throws Exception
    {
        this(provider, requestPath, UpgradeUtils.newDefaultUpgradeRequestHeaders());
    }

    public LocalFuzzer(Provider provider, CharSequence requestPath, Map<String, String> headers) throws Exception
    {
        this.provider = provider;
        String upgradeRequest = UpgradeUtils.generateUpgradeRequest(requestPath, headers);
        LOG.debug("Request: {}", upgradeRequest);
        ByteBuffer upgradeRequestBytes = BufferUtil.toBuffer(upgradeRequest.toString(), StandardCharsets.UTF_8);
        this.endPoint = this.provider.newLocalConnection();
        this.upgradeResponse = performUpgrade(endPoint, upgradeRequestBytes);
        this.generator = new UnitGenerator(WebSocketPolicy.newClientPolicy());
    }

    public void addInputInSegments(LocalConnector.LocalEndPoint endPoint, ByteBuffer outgoing, int segmentSize)
    {
        while (outgoing.remaining() > 0)
        {
            int len = Math.min(segmentSize, outgoing.remaining());
            ByteBuffer slice = outgoing.slice();
            slice.limit(len);
            endPoint.addInput(slice);
            outgoing.position(outgoing.position() + len);
        }
    }

    public void expectMessage(BlockingQueue<WebSocketFrame> framesQueue, byte expectedDataOp, ByteBuffer expectedMessage) throws InterruptedException
    {
        ByteBuffer actualPayload = ByteBuffer.allocate(expectedMessage.remaining());

        WebSocketFrame frame = framesQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Initial Frame.opCode", frame.getOpCode(), is(expectedDataOp));

        actualPayload.put(frame.getPayload());
        while (!frame.isFin())
        {
            frame = framesQueue.poll(1, TimeUnit.SECONDS);
            assertThat("Frame.opCode", frame.getOpCode(), is(OpCode.CONTINUATION));
            actualPayload.put(frame.getPayload());
        }
        actualPayload.flip();
        ByteBufferAssert.assertEquals("Actual Message Payload", actualPayload, expectedMessage);
    }

    public ByteBuffer asNetworkBuffer(List<WebSocketFrame> frames)
    {
        int bufferLength = 0;
        for (WebSocketFrame f : frames)
        {
            bufferLength += f.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
        }

        ByteBuffer buffer = ByteBuffer.allocate(bufferLength);

        for (WebSocketFrame f : frames)
        {
            generator.generate(buffer, f);
        }
        BufferUtil.flipToFlush(buffer, 0);
        return buffer;
    }

    public void assertExpected(BlockingQueue<WebSocketFrame> framesQueue, List<WebSocketFrame> expect) throws InterruptedException
    {
        int expectedCount = expect.size();

        String prefix;
        for (int i = 0; i < expectedCount; i++)
        {
            prefix = "Frame[" + i + "]";

            WebSocketFrame expected = expect.get(i);
            WebSocketFrame actual = framesQueue.poll(3, TimeUnit.SECONDS);
            assertThat(prefix + ".poll", actual, notNullValue());

            if (LOG.isDebugEnabled())
            {
                if (actual.getOpCode() == OpCode.CLOSE)
                    LOG.debug("{} CloseFrame: {}", prefix, CloseFrame.toCloseStatus(actual.getPayload()));
                else
                    LOG.debug("{} {}", prefix, actual);
            }

            assertThat(prefix + ".opcode", OpCode.name(actual.getOpCode()), Matchers.is(OpCode.name(expected.getOpCode())));
            prefix += "(op=" + actual.getOpCode() + "," + (actual.isFin() ? "" : "!") + "fin)";
            if (expected.getOpCode() == OpCode.CLOSE)
            {
                CloseStatus expectedClose = CloseFrame.toCloseStatus(expected.getPayload());
                CloseStatus actualClose = CloseFrame.toCloseStatus(actual.getPayload());
                assertThat(prefix + ".code", actualClose.getCode(), Matchers.is(expectedClose.getCode()));
            }
            else if (expected.hasPayload())
            {
                if (expected.getOpCode() == OpCode.TEXT)
                {
                    String expectedText = expected.getPayloadAsUTF8();
                    String actualText = actual.getPayloadAsUTF8();
                    assertThat(prefix + ".text-payload", actualText, is(expectedText));
                }
                else
                {
                    assertThat(prefix + ".payloadLength", actual.getPayloadLength(), Matchers.is(expected.getPayloadLength()));
                    ByteBufferAssert.assertEquals(prefix + ".payload", expected.getPayload(), actual.getPayload());
                }
            }
            else
            {
                assertThat(prefix + ".payloadLength", actual.getPayloadLength(), Matchers.is(0));
            }
        }
    }


    public void close() throws Exception
    {
        endPoint.close();
    }

    /**
     * Send the EOF signal
     */
    public void eof()
    {
        endPoint.addInputEOF();
    }

    public void expect(List<WebSocketFrame> expected) throws InterruptedException
    {
        // Get incoming frames
        // Wait for server to close
        endPoint.waitUntilClosed();

        // Get the server send echo bytes
        ByteBuffer incoming = endPoint.getOutput();

        // Parse those bytes into frames
        ParserCapture capture = new ParserCapture();
        Parser parser = provider.newClientParser(capture);
        parser.parse(incoming);

        assertExpected(capture.framesQueue, expected);
    }

    public BlockingQueue<WebSocketFrame> getOutputFrames()
    {
        // Get incoming frames
        // Wait for server to close
        endPoint.waitUntilClosed();

        // Get the server send echo bytes
        ByteBuffer incoming = endPoint.getOutput();

        // Parse those bytes into frames
        ParserCapture capture = new ParserCapture();
        Parser parser = provider.newClientParser(capture);
        parser.parse(incoming);

        return capture.framesQueue;
    }

    /**
     * Send raw bytes
     */
    public void send(ByteBuffer buffer)
    {
        endPoint.addInput(buffer);
    }

    /**
     * Send some of the raw bytes
     *
     * @param buffer the buffer
     * @param length the number of bytes to send from buffer
     */
    public void send(ByteBuffer buffer, int length)
    {
        int limit = Math.min(length, buffer.remaining());
        ByteBuffer sliced = buffer.slice();
        sliced.limit(limit);
        endPoint.addInput(sliced);
        buffer.position(buffer.position() + limit);
    }

    /**
     * Generate a single ByteBuffer representing the entire
     * list of generated frames, and submit it to {@link LocalConnector.LocalEndPoint#addInput(ByteBuffer)}
     *
     * @param frames the list of frames to send
     */
    public void sendBulk(List<WebSocketFrame> frames)
    {
        int bufferLength = 0;
        for (WebSocketFrame f : frames)
        {
            bufferLength += f.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
        }

        ByteBuffer outgoing = ByteBuffer.allocate(bufferLength);

        boolean eof = false;
        for (WebSocketFrame f : frames)
        {
            generator.generate(outgoing, f);
            if (f.getOpCode() == OpCode.CLOSE)
                eof = true;
        }
        BufferUtil.flipToFlush(outgoing, 0);
        endPoint.addInput(outgoing);
        if (eof)
            endPoint.addInputEOF();
    }

    /**
     * Generate a ByteBuffer for each frame, and submit each to
     * {@link LocalConnector.LocalEndPoint#addInput(ByteBuffer)}
     *
     * @param frames the list of frames to send
     */
    public void sendFrames(List<WebSocketFrame> frames)
    {
        boolean eof = false;
        for (WebSocketFrame f : frames)
        {
            ByteBuffer buffer = generator.generate(f);
            endPoint.addInput(buffer);
            if (f.getOpCode() == OpCode.CLOSE)
                eof = true;
        }

        if (eof)
            endPoint.addInputEOF();
    }

    /**
     * Generate a ByteBuffer for each frame, and submit each to
     * {@link LocalConnector.LocalEndPoint#addInput(ByteBuffer)}
     *
     * @param frames the list of frames to send
     */
    public void sendFrames(WebSocketFrame... frames)
    {
        boolean eof = false;
        for (WebSocketFrame f : frames)
        {
            ByteBuffer buffer = generator.generate(f);
            endPoint.addInput(buffer);
            if (f.getOpCode() == OpCode.CLOSE)
                eof = true;
        }

        if (eof)
            endPoint.addInputEOF();
    }

    /**
     * Generate a ByteBuffer for each frame, and submit each to
     * {@link LocalConnector.LocalEndPoint#addInput(ByteBuffer)}
     *
     * @param frames the list of frames to send
     */
    public void sendSegmented(List<WebSocketFrame> frames, int segmentSize)
    {
        int bufferLength = 0;
        for (WebSocketFrame f : frames)
        {
            bufferLength += f.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
        }
        
        ByteBuffer outgoing = ByteBuffer.allocate(bufferLength);
        
        boolean eof = false;
        for (WebSocketFrame f : frames)
        {
            generator.generate(outgoing, f);
            if (f.getOpCode() == OpCode.CLOSE)
                eof = true;
        }
        BufferUtil.flipToFlush(outgoing, 0);
        addInputInSegments(endPoint, outgoing, segmentSize);
        if (eof)
            endPoint.addInputEOF();
    }
    
    private HttpTester.Response performUpgrade(LocalConnector.LocalEndPoint endPoint, ByteBuffer buf) throws Exception
    {
        endPoint.addInput(buf);
        
        // Get response
        ByteBuffer response = endPoint.waitForResponse(false, 1, TimeUnit.SECONDS);
        HttpTester.Response parsedResponse = HttpTester.parseResponse(response);
        
        LOG.debug("Response: {}", parsedResponse);
        
        assertThat("Is Switching Protocols", parsedResponse.getStatus(), is(101));
        assertThat("Is Connection Upgrade", parsedResponse.get(WebSocketConstants.SEC_WEBSOCKET_ACCEPT), notNullValue());
        assertThat("Is Connection Upgrade", parsedResponse.get("Connection"), is("Upgrade"));
        assertThat("Is WebSocket Upgrade", parsedResponse.get("Upgrade"), is("WebSocket"));
        return parsedResponse;
    }
    
    public interface Provider
    {
        Parser newClientParser(Parser.Handler parserHandler);
        
        LocalConnector.LocalEndPoint newLocalConnection();
    }
}