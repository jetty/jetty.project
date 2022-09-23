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

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.TestFrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketServer;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.UpgradeListener;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PerMessageDeflaterBufferSizeTest
{
    private WebSocketServer server;
    private final TestFrameHandler serverHandler = new TestFrameHandler();
    private final TestNegotiator testNegotiator = new TestNegotiator();
    private URI serverUri;

    private WebSocketCoreClient client;

    public class TestNegotiator extends WebSocketNegotiator.AbstractNegotiator
    {
        int deflateBufferSize = -1;
        int inflateBufferSize = -1;

        @Override
        public FrameHandler negotiate(ServerUpgradeRequest request, ServerUpgradeResponse response, Callback callback)
        {
            for (ExtensionConfig extensionConfig : request.getExtensions())
            {
                assertFalse(extensionConfig.getName().startsWith("@"));
            }

            for (ExtensionConfig extensionConfig : response.getExtensions())
            {
                if ("permessage-deflate".equals(extensionConfig.getName()))
                {
                    if (deflateBufferSize != -1)
                        extensionConfig.setParameter("@deflate_buffer_size", deflateBufferSize);
                    if (inflateBufferSize != -1)
                        extensionConfig.setParameter("@inflate_buffer_size", inflateBufferSize);
                }
            }

            return serverHandler;
        }
    }

    @BeforeEach
    public void setup() throws Exception
    {
        server = new WebSocketServer(testNegotiator);
        server.start();
        serverUri = new URI("ws://localhost:" + server.getLocalPort());

        client = new WebSocketCoreClient();
        client.start();
    }

    @Test
    public void testClientDeflateBufferSize() throws Exception
    {
        int deflateBufferSize = 6;
        TestFrameHandler clientHandler = new TestFrameHandler();
        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(client, serverUri, clientHandler);
        upgradeRequest.addExtensions("permessage-deflate; @deflate_buffer_size=" + deflateBufferSize);

        CompletableFuture<HttpFields> futureRequestHeaders = new CompletableFuture<>();
        upgradeRequest.addListener(new UpgradeListener()
        {
            @Override
            public void onHandshakeRequest(HttpRequest request)
            {
                futureRequestHeaders.complete(request.getHeaders());
            }
        });

        // Connect to the server.
        CompletableFuture<CoreSession> connect = client.connect(upgradeRequest);
        connect.get(5, TimeUnit.SECONDS);

        // Make sure the internal parameter was not sent to the server.
        HttpFields requestHeaders = futureRequestHeaders.get();
        assertThat(requestHeaders.getFields(HttpHeader.SEC_WEBSOCKET_EXTENSIONS).size(), is(1));
        assertThat(requestHeaders.get(HttpHeader.SEC_WEBSOCKET_EXTENSIONS), is("permessage-deflate"));

        // We should now only be able to send this message in multiple frames as it exceeds deflate_buffer_size.
        String message = "0123456789";
        clientHandler.sendText(message);

        // Verify the frame has been fragmented into multiple parts.
        int numFrames = 0;
        StringBuilder receivedMessage = new StringBuilder();
        while (true)
        {
            Frame frame = Objects.requireNonNull(serverHandler.getFrames().poll(5, TimeUnit.SECONDS));
            receivedMessage.append(frame.getPayloadAsUTF8());
            numFrames++;
            if (frame.isFin())
                break;
        }

        // Check we got the message and it was split into multiple frames.
        assertThat(numFrames, greaterThan(1));
        assertThat(receivedMessage.toString(), Matchers.equalTo(message));

        clientHandler.sendClose();
        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
        assertNull(serverHandler.getError());
        assertNull(clientHandler.getError());
    }

    @Test
    public void testClientInflateBufferSize() throws Exception
    {
        int inflateBufferSize = 6;
        TestFrameHandler clientHandler = new TestFrameHandler();
        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(client, serverUri, clientHandler);
        upgradeRequest.addExtensions("permessage-deflate; @inflate_buffer_size=" + inflateBufferSize);

        CompletableFuture<HttpFields> futureRequestHeaders = new CompletableFuture<>();
        upgradeRequest.addListener(new UpgradeListener()
        {
            @Override
            public void onHandshakeRequest(HttpRequest request)
            {
                futureRequestHeaders.complete(request.getHeaders());
            }
        });

        // Connect to the server.
        CompletableFuture<CoreSession> connect = client.connect(upgradeRequest);
        connect.get(5, TimeUnit.SECONDS);

        // Make sure the internal parameter was not sent to the server.
        HttpFields requestHeaders = futureRequestHeaders.get();
        assertThat(requestHeaders.getFields(HttpHeader.SEC_WEBSOCKET_EXTENSIONS).size(), is(1));
        assertThat(requestHeaders.get(HttpHeader.SEC_WEBSOCKET_EXTENSIONS), is("permessage-deflate"));

        // We should now only be able to send this message in multiple frames as it exceeds deflate_buffer_size.
        String message = "0123456789";
        assertTrue(serverHandler.open.await(5, TimeUnit.SECONDS));
        serverHandler.sendText(message);

        // Verify the frame has been fragmented into multiple parts.
        int numFrames = 0;
        StringBuilder receivedMessage = new StringBuilder();
        while (true)
        {
            Frame frame = Objects.requireNonNull(clientHandler.getFrames().poll(5, TimeUnit.SECONDS));
            receivedMessage.append(frame.getPayloadAsUTF8());
            numFrames++;
            if (frame.isFin())
                break;
        }

        // Check we got the message and it was split into multiple frames.
        assertThat(numFrames, greaterThan(1));
        assertThat(receivedMessage.toString(), Matchers.equalTo(message));

        clientHandler.sendClose();
        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
        assertNull(serverHandler.getError());
        assertNull(clientHandler.getError());
    }

    @Test
    public void testServerDeflateBufferSize() throws Exception
    {
        testNegotiator.deflateBufferSize = 6;
        TestFrameHandler clientHandler = new TestFrameHandler();
        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(client, serverUri, clientHandler);
        upgradeRequest.addExtensions("permessage-deflate");

        CompletableFuture<HttpFields> futureResponseHeaders = new CompletableFuture<>();
        upgradeRequest.addListener(new UpgradeListener()
        {
            @Override
            public void onHandshakeResponse(HttpRequest request, HttpResponse response)
            {
                futureResponseHeaders.complete(request.getHeaders());
            }
        });

        // Connect to the server.
        CompletableFuture<CoreSession> connect = client.connect(upgradeRequest);
        connect.get(5, TimeUnit.SECONDS);

        // Make sure the internal parameter was not sent from the server.
        HttpFields responseHeaders = futureResponseHeaders.get();
        assertThat(responseHeaders.getFields(HttpHeader.SEC_WEBSOCKET_EXTENSIONS).size(), is(1));
        assertThat(responseHeaders.get(HttpHeader.SEC_WEBSOCKET_EXTENSIONS), is("permessage-deflate"));

        // We should now only be able to send this message in multiple frames as it exceeds deflate_buffer_size.
        String message = "0123456789";
        assertTrue(serverHandler.open.await(5, TimeUnit.SECONDS));
        serverHandler.sendText(message);

        // Verify the frame has been fragmented into multiple parts.
        int numFrames = 0;
        StringBuilder receivedMessage = new StringBuilder();
        while (true)
        {
            Frame frame = Objects.requireNonNull(clientHandler.getFrames().poll(5, TimeUnit.SECONDS));
            receivedMessage.append(frame.getPayloadAsUTF8());
            numFrames++;
            if (frame.isFin())
                break;
        }

        // Check we got the message and it was split into multiple frames.
        assertThat(numFrames, greaterThan(1));
        assertThat(receivedMessage.toString(), Matchers.equalTo(message));

        clientHandler.sendClose();
        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
        assertNull(serverHandler.getError());
        assertNull(clientHandler.getError());
    }

    @Test
    public void testServerInflateBufferSize() throws Exception
    {
        testNegotiator.inflateBufferSize = 6;
        TestFrameHandler clientHandler = new TestFrameHandler();
        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(client, serverUri, clientHandler);
        upgradeRequest.addExtensions("permessage-deflate");

        CompletableFuture<HttpFields> futureResponseHeaders = new CompletableFuture<>();
        upgradeRequest.addListener(new UpgradeListener()
        {
            @Override
            public void onHandshakeResponse(HttpRequest request, HttpResponse response)
            {
                futureResponseHeaders.complete(request.getHeaders());
            }
        });

        // Connect to the server.
        CompletableFuture<CoreSession> connect = client.connect(upgradeRequest);
        connect.get(5, TimeUnit.SECONDS);

        // Make sure the internal parameter was not sent from the server.
        HttpFields responseHeaders = futureResponseHeaders.get();
        assertThat(responseHeaders.getFields(HttpHeader.SEC_WEBSOCKET_EXTENSIONS).size(), is(1));
        assertThat(responseHeaders.get(HttpHeader.SEC_WEBSOCKET_EXTENSIONS), is("permessage-deflate"));

        // We should now only be able to send this message in multiple frames as it exceeds deflate_buffer_size.
        String message = "0123456789";
        ByteBuffer payload = toBuffer(message, true);
        clientHandler.getCoreSession().sendFrame(new Frame(OpCode.TEXT, payload), Callback.NOOP, false);

        // Verify the frame has been fragmented into multiple parts.
        int numFrames = 0;
        StringBuilder receivedMessage = new StringBuilder();
        while (true)
        {
            Frame frame = Objects.requireNonNull(serverHandler.getFrames().poll(5, TimeUnit.SECONDS));
            receivedMessage.append(frame.getPayloadAsUTF8());
            numFrames++;
            if (frame.isFin())
                break;
        }

        // Check we got the message and it was split into multiple frames.
        assertThat(numFrames, greaterThan(1));
        assertThat(receivedMessage.toString(), Matchers.equalTo(message));

        clientHandler.sendClose();
        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
        assertNull(serverHandler.getError());
        assertNull(clientHandler.getError());
    }

    public ByteBuffer toBuffer(String string, boolean direct)
    {
        ByteBuffer buffer = BufferUtil.allocate(string.length(), direct);
        BufferUtil.clearToFill(buffer);
        BufferUtil.put(BufferUtil.toBuffer(string), buffer);
        BufferUtil.flipToFlush(buffer, 0);

        // Sanity checks.
        assertThat(buffer.hasArray(), is(!direct));
        assertThat(BufferUtil.toString(buffer), is(string));

        return buffer;
    }
}
