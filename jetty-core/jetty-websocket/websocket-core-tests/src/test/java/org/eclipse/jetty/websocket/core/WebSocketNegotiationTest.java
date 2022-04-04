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
import java.util.stream.Stream;

import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.UpgradeListener;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.exception.UpgradeException;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
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
    private final WebSocketComponents components = new WebSocketComponents();

    @BeforeEach
    public void startup() throws Exception
    {
        WebSocketNegotiator negotiator = new WebSocketNegotiator.AbstractNegotiator()
        {
            @Override
            public FrameHandler negotiate(WebSocketNegotiation negotiation) throws IOException
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
                        negotiation.setNegotiatedExtensions(Collections.emptyList());
                        break;

                    case "testNoSubProtocolSelected":
                        negotiation.setSubprotocol(null);
                        break;

                    case "test":
                    case "testExtensionThatDoesNotExist":
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

        server = new WebSocketServer(components, negotiator, false);
        client = new WebSocketCoreClient(null, components);

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

        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(client, server.getUri(), clientHandler);
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

        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(client, server.getUri(), clientHandler);
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

        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(client, server.getUri(), clientHandler);
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

        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(client, server.getUri(), clientHandler);
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
    public void testExtensionThatDoesNotExist() throws Exception
    {
        Socket client = new Socket();
        client.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()));

        HttpFields.Mutable httpFields = newUpgradeRequest("nonExistentExtensionName");
        String upgradeRequest = "GET / HTTP/1.1\r\n" + httpFields;
        client.getOutputStream().write(upgradeRequest.getBytes(StandardCharsets.ISO_8859_1));
        String response = getUpgradeResponse(client.getInputStream());

        assertThat(response, startsWith("HTTP/1.1 101 Switching Protocols"));
        assertThat(response, containsString("Sec-WebSocket-Protocol: test"));
        assertThat(response, containsString("Sec-WebSocket-Accept: +WahVcVmeMLKQUMm0fvPrjSjwzI="));
        assertThat(response, not(containsString(HttpHeader.SEC_WEBSOCKET_EXTENSIONS.asString())));
    }

    @Test
    public void testAcceptTwoExtensionsOfSameName() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler();

        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(client, server.getUri(), clientHandler);
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

        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(client, server.getUri(), clientHandler);

        try (StacklessLogging stacklessLogging = new StacklessLogging(HttpChannelState.class))
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
        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(client, server.getUri(), clientHandler);
        upgradeRequest.setSubProtocols("testNoSubProtocolSelected");
        CompletableFuture<HttpFields> headers = new CompletableFuture<>();
        upgradeRequest.addListener(new UpgradeListener()
        {
            @Override
            public void onHandshakeResponse(HttpRequest request, HttpResponse response)
            {
                headers.complete(response.getHeaders());
            }
        });

        CoreSession session = client.connect(upgradeRequest).get(5, TimeUnit.SECONDS);
        session.close(Callback.NOOP);
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
        assertThat(clientHandler.closeStatus.getCode(), is(CloseStatus.NO_CODE));

        // RFC6455: If the server does not agree to any of the client's requested subprotocols, the only acceptable
        // value is null. It MUST NOT send back a |Sec-WebSocket-Protocol| header field in its response.
        HttpFields httpFields = headers.get();
        assertThat(httpFields.get(HttpHeader.UPGRADE), is("websocket"));
        assertNull(httpFields.get(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL));
    }

    @Test
    public void testValidUpgradeRequest() throws Exception
    {
        Socket client = new Socket();
        client.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()));

        HttpFields.Mutable httpFields = newUpgradeRequest(null);
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

        HttpFields.Mutable httpFields = newUpgradeRequest(null);
        httpFields.remove(HttpHeader.SEC_WEBSOCKET_KEY);

        String upgradeRequest = "GET / HTTP/1.1\r\n" + httpFields;
        client.getOutputStream().write(upgradeRequest.getBytes(StandardCharsets.ISO_8859_1));
        String response = getUpgradeResponse(client.getInputStream());

        assertThat(response, containsString("400 Bad Request"));
    }

    @Test
    public void testListenerExtensionSelection() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler();

        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(client, server.getUri(), clientHandler);
        upgradeRequest.setSubProtocols("test");

        CompletableFuture<String> extensionHeader = new CompletableFuture<>();
        upgradeRequest.addListener(new UpgradeListener()
        {
            @Override
            public void onHandshakeRequest(HttpRequest request)
            {
                request.headers(headers -> headers.put(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate"));
            }

            @Override
            public void onHandshakeResponse(HttpRequest request, HttpResponse response)
            {
                extensionHeader.complete(response.getHeaders().get(HttpHeader.SEC_WEBSOCKET_EXTENSIONS));
            }
        });

        client.connect(upgradeRequest).get(5, TimeUnit.SECONDS);
        clientHandler.sendClose();
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
        assertNull(clientHandler.getError());

        assertThat(extensionHeader.get(5, TimeUnit.SECONDS), is("permessage-deflate"));
    }

    @Test
    public void testListenerExtensionSelectionError() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler();
        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(client, server.getUri(), clientHandler);
        upgradeRequest.setSubProtocols("test");
        upgradeRequest.addExtensions("permessage-deflate;server_no_context_takeover");

        upgradeRequest.addListener(new UpgradeListener()
        {
            @Override
            public void onHandshakeRequest(HttpRequest request)
            {
                request.headers(headers -> headers.put(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate"));
            }
        });

        ExecutionException error = assertThrows(ExecutionException.class,
            () -> client.connect(upgradeRequest).get(5, TimeUnit.SECONDS));

        Throwable upgradeException = error.getCause();
        assertThat(upgradeException, instanceOf(UpgradeException.class));
        Throwable cause = upgradeException.getCause();
        assertThat(cause, instanceOf(IllegalStateException.class));
        assertThat(cause.getMessage(), is("Extensions set in both the ClientUpgradeRequest and UpgradeListener"));
    }

    public static Stream<Arguments> internalExtensionScenarios() throws Exception
    {
        return Stream.of(
            Arguments.of("", ""),
            Arguments.of("ext1", "ext1"),
            Arguments.of("@int1", "@int1"),
            Arguments.of("ext1, ext1", "ext1"),
            Arguments.of("ext1, @int1", "ext1, @int1"),
            Arguments.of("@int1, ext1", "@int1, ext1"),
            Arguments.of("@int1, @int1", "@int1, @int1"),
            Arguments.of("@int1, ext1, ext2", "@int1, ext1, ext2"),
            Arguments.of("ext1, @int1, ext2", "ext1, @int1, ext2"),
            Arguments.of("ext1, ext2, @int1", "ext1, ext2, @int1"),
            Arguments.of("@int1, ext1, @int2, ext2, @int3", "@int1, ext1, @int2, ext2, @int3"),
            Arguments.of("ext1, ext1, ext1, @int1, ext2", "ext1, @int1, ext2"),
            Arguments.of("ext1, @int1, ext1, ext1, ext2", "@int1, ext1, ext2"),
            Arguments.of("ext1, ext2, ext3, @int1", "ext1, ext2, ext3, @int1")
        );
    }

    @ParameterizedTest
    @MethodSource("internalExtensionScenarios")
    public void testClientRequestedInternalExtensions(String reqExts, String negExts) throws Exception
    {
        // Add some simple Extensions for to make test examples clearer.
        WebSocketExtensionRegistry extRegistry = components.getExtensionRegistry();
        extRegistry.register("ext1", AbstractExtension.class);
        extRegistry.register("ext2", AbstractExtension.class);
        extRegistry.register("ext3", AbstractExtension.class);
        extRegistry.register("@int1", AbstractExtension.class);
        extRegistry.register("@int2", AbstractExtension.class);
        extRegistry.register("@int3", AbstractExtension.class);

        TestFrameHandler clientHandler = new TestFrameHandler();
        CompletableFuture<String> extensionHeader = new CompletableFuture<>();
        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(client, server.getUri(), clientHandler);
        upgradeRequest.setSubProtocols("test");
        if (!StringUtil.isEmpty(reqExts))
            upgradeRequest.addExtensions(reqExts.split(","));
        upgradeRequest.addListener(new UpgradeListener()
        {
            @Override
            public void onHandshakeResponse(HttpRequest request, HttpResponse response)
            {
                extensionHeader.complete(response.getHeaders().get(HttpHeader.SEC_WEBSOCKET_EXTENSIONS));
            }
        });

        // Connect to the client then close the Session.
        client.connect(upgradeRequest).get(5, TimeUnit.SECONDS);
        clientHandler.sendClose();
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
        assertNull(clientHandler.getError());

        // We had no internal Extensions in the response headers.
        assertThat(extensionHeader.get(5, TimeUnit.SECONDS), not(containsString("@")));

        // The list of Extensions on the client contains the internal Extensions.
        StringBuilder negotiatedExtensions = new StringBuilder();
        List<Extension> extensions = ((WebSocketCoreSession)clientHandler.coreSession).getExtensionStack().getExtensions();
        for (int i = 0; i < extensions.size(); i++)
        {
            negotiatedExtensions.append(extensions.get(i).getConfig().getParameterizedName());
            if (i != extensions.size() - 1)
                negotiatedExtensions.append(", ");
        }
        assertThat(negotiatedExtensions.toString(), is(negExts));
    }
}
