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
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.RawFrameBuilder;
import org.eclipse.jetty.websocket.core.TestFrameHandler;
import org.eclipse.jetty.websocket.core.TestWebSocketNegotiator;
import org.eclipse.jetty.websocket.core.WebSocketServer;
import org.eclipse.jetty.websocket.core.WebSocketTester;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ValidationExtensionTest extends WebSocketTester
{
    private WebSocketServer server;
    TestFrameHandler serverHandler;

    @BeforeEach
    public void start() throws Exception
    {
        serverHandler = new TestFrameHandler();
        WebSocketNegotiator negotiator = new TestWebSocketNegotiator(serverHandler)
        {
            @Override
            public FrameHandler negotiate(WebSocketNegotiation negotiation) throws IOException
            {
                List<ExtensionConfig> negotiatedExtensions = new ArrayList<>();
                negotiatedExtensions.add(ExtensionConfig.parse(
                    "@validation; outgoing-sequence; incoming-sequence; outgoing-frame; incoming-frame; incoming-utf8; outgoing-utf8"));
                negotiation.setNegotiatedExtensions(negotiatedExtensions);

                return super.negotiate(negotiation);
            }
        };
        server = new WebSocketServer(negotiator);
        server.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        server.stop();
    }

    @Test
    public void testNonUtf8BinaryPayload() throws Exception
    {
        byte[] nonUtf8Payload = {0x7F, (byte)0xFF, (byte)0xFF};

        try (Socket client = newClient(server.getLocalPort()))
        {
            client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.BINARY, nonUtf8Payload, true));
            Frame frame = serverHandler.receivedFrames.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.BINARY));
            assertThat(BufferUtil.toArray(frame.getPayload()), is(nonUtf8Payload));

            //close normally
            client.getOutputStream().write(RawFrameBuilder.buildClose(CloseStatus.NORMAL_STATUS, true));
            assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
            frame = receiveFrame(client.getInputStream());
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.CLOSE));
            assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.NORMAL));
        }
    }

    @Test
    public void testValidContinuationOnNonUtf8Boundary() throws Exception
    {
        // Testing with 4 byte UTF8 character "\uD842\uDF9F"
        byte[] initialPayload = new byte[]{(byte)0xF0, (byte)0xA0};
        byte[] continuationPayload = new byte[]{(byte)0xAE, (byte)0x9F};

        try (Socket client = newClient(server.getLocalPort()))
        {
            client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.TEXT, initialPayload, true, false));
            Frame frame = serverHandler.receivedFrames.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.TEXT));
            assertThat(BufferUtil.toArray(frame.getPayload()), is(initialPayload));

            client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.CONTINUATION, continuationPayload, true));
            frame = serverHandler.receivedFrames.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.CONTINUATION));
            assertThat(BufferUtil.toArray(frame.getPayload()), is(continuationPayload));

            //close normally
            client.getOutputStream().write(RawFrameBuilder.buildClose(CloseStatus.NORMAL_STATUS, true));
            assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
            frame = receiveFrame(client.getInputStream());
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.CLOSE));
            assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.NORMAL));
        }
    }

    @Test
    public void testInvalidContinuationOnNonUtf8Boundary() throws Exception
    {
        // Testing with 4 byte UTF8 character "\uD842\uDF9F"
        byte[] initialPayload = new byte[]{(byte)0xF0, (byte)0xA0};
        byte[] incompleteContinuationPayload = new byte[]{(byte)0xAE};

        try (Socket client = newClient(server.getLocalPort()))
        {
            client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.TEXT, initialPayload, true, false));
            Frame frame = serverHandler.receivedFrames.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.TEXT));
            assertThat(BufferUtil.toArray(frame.getPayload()), is(initialPayload));

            client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.CONTINUATION, incompleteContinuationPayload, true));
            frame = receiveFrame(client.getInputStream());
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.CLOSE));
            assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.BAD_PAYLOAD));
        }
    }
}
