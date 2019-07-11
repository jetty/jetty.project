//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.core.FrameHandler.CoreSession;
import org.eclipse.jetty.websocket.core.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.UpgradeListener;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketNegotiationTest extends WebSocketTester
{
    public static class EchoFrameHandler extends TestFrameHandler
    {
        @Override
        public void onFrame(Frame frame)
        {
            super.onFrame(frame);
            Frame echo = new Frame(frame.getOpCode(), frame.getPayloadAsUTF8());
            getCoreSession().sendFrame(echo, Callback.NOOP, false);
        }
    }

    private WebSocketServer server;
    private WebSocketCoreClient client;

    @BeforeEach
    public void startup() throws Exception
    {
        WebSocketNegotiator negotiator = new WebSocketNegotiator.AbstractNegotiator()
        {
            @Override
            public FrameHandler negotiate(Negotiation negotiation) throws IOException
            {
                if (negotiation.getOfferedSubprotocols().isEmpty())
                {
                    negotiation.setSubprotocol("NotOffered");
                    return new EchoFrameHandler();
                }

                String subprotocol = negotiation.getOfferedSubprotocols().get(0);
                negotiation.setSubprotocol(subprotocol);
                switch (subprotocol)
                {
                    case "testExtensionSelection":
                        negotiation.setNegotiatedExtensions(List.of(ExtensionConfig.parse("permessage-deflate;client_no_context_takeover")));
                        break;

                    case "testNotOfferedParameter":
                        negotiation.setNegotiatedExtensions(List.of(ExtensionConfig.parse("permessage-deflate;server_no_context_takeover")));
                        break;

                    case "testNotAcceptingExtensions":
                        negotiation.setNegotiatedExtensions(Collections.EMPTY_LIST);
                        break;

                    case "testNoSubProtocolSelected":
                        negotiation.setSubprotocol(null);
                        break;

                    case "test":
                    case "testInvalidExtensionParameter":
                    case "testAcceptTwoExtensionsOfSameName":
                    case "testInvalidUpgradeRequest":
                        break;

                    default:
                        return null;
                }

                return new EchoFrameHandler();
            }
        };

        server = new WebSocketServer(negotiator);
        client = new WebSocketCoreClient();

        server.start();
        client.start();
    }

    @AfterEach
    public void shutdown() throws Exception
    {
        server.start();
        client.start();
    }

    @Test
    public void testExtensionSelection() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler();

        ClientUpgradeRequest upgradeRequest = ClientUpgradeRequest.from(client, server.getUri(), clientHandler);
        upgradeRequest.setSubProtocols("testExtensionSelection");
        upgradeRequest.addExtensions("permessage-deflate;server_no_context_takeover", "permessage-deflate;client_no_context_takeover");

        CompletableFuture<String> extensionHeader = new CompletableFuture<>();
        upgradeRequest.addListener(new UpgradeListener()
        {
            @Override
            public void onHandshakeResponse(HttpRequest request, HttpResponse response)
            {
                extensionHeader.complete(response.getHeaders().get(HttpHeader.SEC_WEBSOCKET_EXTENSIONS));
            }
        });

        CompletableFuture<CoreSession> connect = client.connect(upgradeRequest);
        connect.get(5, TimeUnit.SECONDS);

        clientHandler.sendText("hello world");
        clientHandler.sendClose();
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
        assertThat(clientHandler.receivedFrames.size(), is(2));
        assertNull(clientHandler.getError());

        assertThat(extensionHeader.get(5, TimeUnit.SECONDS), is("permessage-deflate;client_no_context_takeover"));
    }

    @Test
    public void testNotOfferedParameter() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler();

        ClientUpgradeRequest upgradeRequest = ClientUpgradeRequest.from(client, server.getUri(), clientHandler);
        upgradeRequest.setSubProtocols("testNotOfferedParameter");
        upgradeRequest.addExtensions("permessage-deflate;client_no_context_takeover");

        CompletableFuture<String> extensionHeader = new CompletableFuture<>();
        upgradeRequest.addListener(new UpgradeListener()
        {
            @Override
            public void onHandshakeResponse(HttpRequest request, HttpResponse response)
            {
                extensionHeader.complete(response.getHeaders().get(HttpHeader.SEC_WEBSOCKET_EXTENSIONS));
            }
        });

        CompletableFuture<CoreSession> connect = client.connect(upgradeRequest);
        connect.get(5, TimeUnit.SECONDS);
        clientHandler.sendText("hello world");
        clientHandler.sendClose();
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
        assertThat(clientHandler.receivedFrames.size(), is(2));
        assertNull(clientHandler.getError());

        assertThat(extensionHeader.get(5, TimeUnit.SECONDS), is("permessage-deflate;server_no_context_takeover"));
    }

    @Test
    public void testInvalidExtensionParameter() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler();

        ClientUpgradeRequest upgradeRequest = ClientUpgradeRequest.from(client, server.getUri(), clientHandler);
        upgradeRequest.setSubProtocols("testInvalidExtensionParameter");
        upgradeRequest.addExtensions("permessage-deflate;invalid_parameter");

        CompletableFuture<CoreSession> connect = client.connect(upgradeRequest);

        Throwable t = assertThrows(ExecutionException.class, () -> connect.get(5, TimeUnit.SECONDS));
        assertThat(t.getMessage(), containsString("Failed to upgrade to websocket:"));
        assertThat(t.getMessage(), containsString("400 Bad Request"));
    }

    @Test
    public void testNotAcceptingExtensions() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler();

        ClientUpgradeRequest upgradeRequest = ClientUpgradeRequest.from(client, server.getUri(), clientHandler);
        upgradeRequest.setSubProtocols("testNotAcceptingExtensions");
        upgradeRequest.addExtensions("permessage-deflate;server_no_context_takeover", "permessage-deflate;client_no_context_takeover");

        CompletableFuture<String> extensionHeader = new CompletableFuture<>();
        upgradeRequest.addListener(new UpgradeListener()
        {
            @Override
            public void onHandshakeResponse(HttpRequest request, HttpResponse response)
            {
                extensionHeader.complete(response.getHeaders().get(HttpHeader.SEC_WEBSOCKET_EXTENSIONS));
            }
        });

        CompletableFuture<CoreSession> connect = client.connect(upgradeRequest);
        connect.get(5, TimeUnit.SECONDS);

        clientHandler.sendText("hello world");
        clientHandler.sendClose();
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
        assertThat(clientHandler.receivedFrames.size(), is(2));
        assertNull(clientHandler.getError());

        assertNull(extensionHeader.get(5, TimeUnit.SECONDS));
    }

    @Test
    public void testAcceptTwoExtensionsOfSameName() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler();

        ClientUpgradeRequest upgradeRequest = ClientUpgradeRequest.from(client, server.getUri(), clientHandler);
        upgradeRequest.setSubProtocols("testAcceptTwoExtensionsOfSameName");
        upgradeRequest.addExtensions("permessage-deflate;server_no_context_takeover", "permessage-deflate;client_no_context_takeover");

        CompletableFuture<String> extensionHeader = new CompletableFuture<>();
        upgradeRequest.addListener(new UpgradeListener()
        {
            @Override
            public void onHandshakeResponse(HttpRequest request, HttpResponse response)
            {
                extensionHeader.complete(response.getHeaders().get(HttpHeader.SEC_WEBSOCKET_EXTENSIONS));
            }
        });

        CompletableFuture<CoreSession> connect = client.connect(upgradeRequest);
        connect.get(5, TimeUnit.SECONDS);

        clientHandler.sendText("hello world");
        clientHandler.sendClose();
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
        assertThat(clientHandler.receivedFrames.size(), is(2));
        assertNull(clientHandler.getError());

        assertThat(extensionHeader.get(5, TimeUnit.SECONDS), is("permessage-deflate;server_no_context_takeover"));
    }

    @Test
    public void testSubProtocolNotOffered() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler();

        ClientUpgradeRequest upgradeRequest = ClientUpgradeRequest.from(client, server.getUri(), clientHandler);

        try (StacklessLogging stacklessLogging = new StacklessLogging(HttpChannel.class))
        {
            CompletableFuture<CoreSession> connect = client.connect(upgradeRequest);
            Throwable t = assertThrows(ExecutionException.class, () -> connect.get(5, TimeUnit.SECONDS));
            assertThat(t.getMessage(), containsString("Failed to upgrade to websocket:"));
            assertThat(t.getMessage(), containsString("500 Server Error"));
        }
    }

    @Test
    public void testNoSubProtocolSelected() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler();

        ClientUpgradeRequest upgradeRequest = ClientUpgradeRequest.from(client, server.getUri(), clientHandler);
        upgradeRequest.setSubProtocols("testNoSubProtocolSelected");

        try (StacklessLogging stacklessLogging = new StacklessLogging(HttpChannel.class))
        {
            CompletableFuture<CoreSession> connect = client.connect(upgradeRequest);
            Throwable t = assertThrows(ExecutionException.class, () -> connect.get(5, TimeUnit.SECONDS));
            assertThat(t.getMessage(), containsString("Failed to upgrade to websocket:"));
            assertThat(t.getMessage(), containsString("500 Server Error"));
        }
    }

    @Test
    public void testValidUpgradeRequest() throws Exception
    {
        Socket client = new Socket();
        client.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()));

        HttpFields httpFields = newUpgradeRequest(null);
        String upgradeRequest = "GET / HTTP/1.1\r\n" + httpFields;
        client.getOutputStream().write(upgradeRequest.getBytes(StandardCharsets.ISO_8859_1));
        String response = getUpgradeResponse(client.getInputStream());

        assertThat(response, startsWith("HTTP/1.1 101 Switching Protocols"));
        assertThat(response, containsString("Sec-WebSocket-Protocol: test"));
        assertThat(response, containsString("Sec-WebSocket-Accept: +WahVcVmeMLKQUMm0fvPrjSjwzI="));
    }

    @Test
    public void testInvalidUpgradeRequestNoKey() throws Exception
    {
        Socket client = new Socket();
        client.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()));

        HttpFields httpFields = newUpgradeRequest(null);
        httpFields.remove(HttpHeader.SEC_WEBSOCKET_KEY);

        String upgradeRequest = "GET / HTTP/1.1\r\n" + httpFields;
        client.getOutputStream().write(upgradeRequest.getBytes(StandardCharsets.ISO_8859_1));
        String response = getUpgradeResponse(client.getInputStream());

        assertThat(response, containsString("400 Bad Request"));
    }
}