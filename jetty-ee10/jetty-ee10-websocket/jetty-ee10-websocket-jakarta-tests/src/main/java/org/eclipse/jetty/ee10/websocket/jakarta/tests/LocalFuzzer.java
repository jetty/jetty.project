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

package org.eclipse.jetty.ee10.websocket.jakarta.tests;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.internal.Generator;
import org.eclipse.jetty.websocket.core.internal.Parser;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class LocalFuzzer extends Fuzzer.Adapter implements Fuzzer, AutoCloseable
{
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
        this(provider, requestPath, new HashMap<>());
    }

    public LocalFuzzer(Provider provider, CharSequence requestPath, Map<String, String> headers) throws Exception
    {
        super();
        this.provider = provider;
        String upgradeRequest = UpgradeUtils.generateUpgradeRequest(requestPath, headers);
        logger.debug("Request: {}", upgradeRequest);
        ByteBuffer upgradeRequestBytes = BufferUtil.toBuffer(upgradeRequest.toString(), StandardCharsets.UTF_8);
        this.endPoint = this.provider.newLocalConnection();
        this.upgradeResponse = performUpgrade(endPoint, upgradeRequestBytes);
        this.generator = new UnitGenerator(Behavior.CLIENT);
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

    public ByteBuffer asNetworkBuffer(List<Frame> frames)
    {
        int bufferLength = 0;
        for (Frame f : frames)
        {
            bufferLength += f.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
        }

        ByteBuffer buffer = ByteBuffer.allocate(bufferLength);

        for (Frame f : frames)
        {
            generator.generate(buffer, f);
        }
        BufferUtil.flipToFlush(buffer, 0);
        return buffer;
    }

    @Override
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

    @Override
    public void expect(List<Frame> expected) throws InterruptedException
    {
        // Get incoming frames
        // Wait for server to close
        endPoint.waitUntilClosed();

        // Get the server send echo bytes
        ByteBuffer incoming = endPoint.getOutput();

        // Parse those bytes into frames
        ParserCapture capture = new ParserCapture(provider.newClientParser());
        capture.parse(incoming);

        assertExpected(capture.framesQueue, expected);
    }

    public BlockingQueue<Frame> getOutputFrames()
    {
        // Get incoming frames
        // Wait for server to close
        endPoint.waitUntilClosed();

        // Get the server send echo bytes
        ByteBuffer incoming = endPoint.getOutput();

        // Parse those bytes into frames
        ParserCapture capture = new ParserCapture(provider.newClientParser());
        capture.parse(incoming);

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
    @Override
    public void sendBulk(List<Frame> frames)
    {
        int bufferLength = 0;
        for (Frame f : frames)
        {
            bufferLength += f.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
        }

        ByteBuffer outgoing = ByteBuffer.allocate(bufferLength);

        boolean eof = false;
        for (Frame f : frames)
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
    @Override
    public void sendFrames(List<Frame> frames)
    {
        boolean eof = false;
        for (Frame f : frames)
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
    public void sendFrames(Frame... frames)
    {
        boolean eof = false;
        for (Frame f : frames)
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
    public void sendSegmented(List<Frame> frames, int segmentSize)
    {
        int bufferLength = 0;
        for (Frame f : frames)
        {
            bufferLength += f.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
        }

        ByteBuffer outgoing = ByteBuffer.allocate(bufferLength);

        boolean eof = false;
        for (Frame f : frames)
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

        logger.debug("Response: {}", parsedResponse);

        assertThat("Is Switching Protocols", parsedResponse.getStatus(), is(101));
        assertThat("Is Connection Upgrade", parsedResponse.get(HttpHeader.SEC_WEBSOCKET_ACCEPT.asString()), notNullValue());
        assertThat("Is Connection Upgrade", parsedResponse.get("Connection"), is("Upgrade"));
        assertThat("Is WebSocket Upgrade", parsedResponse.get("Upgrade"), is("WebSocket"));
        return parsedResponse;
    }

    public interface Provider
    {
        Parser newClientParser();

        LocalConnector.LocalEndPoint newLocalConnection();
    }
}
