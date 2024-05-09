//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.tests.client;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.EOFException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.api.exceptions.UpgradeException;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.JettyUpgradeListener;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketCreator;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.eclipse.jetty.websocket.tests.EchoSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class ClientResponseTest
{
    private Server _server;
    private ServerConnector _connector;
    private WebSocketClient _client;

    public void before(WebSocketCreator creator) throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        ContextHandler contextHandler = new ContextHandler();
        WebSocketUpgradeHandler upgradeHandler = WebSocketUpgradeHandler.from(_server, contextHandler);
        contextHandler.setHandler(upgradeHandler);
        _server.setHandler(contextHandler);
        upgradeHandler.configure(container -> container.addMapping("/", creator));

        _server.start();

        _client = new WebSocketClient();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void testResponseOnUpgradeFailure() throws Exception
    {
        before((req, resp, cb) ->
        {
            resp.setStatus(HttpStatus.IM_A_TEAPOT_418);
            resp.getHeaders().put("specialHeader", "value123");
            resp.write(true, BufferUtil.toBuffer("failed by test"), cb);
            return null;
        });

        EchoSocket clientEndpoint = new EchoSocket();
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();

        CompletableFuture<Response> responseFuture = new CompletableFuture<>();
        CompletableFuture<String> contentFuture = new CompletableFuture<>();
        JettyUpgradeListener upgradeListener = new JettyUpgradeListener()
        {
            @Override
            public void onHandshakeRequest(Request request)
            {
                request.onResponseContentSource((resp, source) -> assertDoesNotThrow(
                    () -> contentFuture.complete(IO.toString(Content.Source.asInputStream(source)))));
            }

            @Override
            public void onHandshakeResponse(Request request, Response response)
            {
                responseFuture.complete(response);
            }
        };

        Throwable t = assertThrows(
            Throwable.class, () -> _client.connect(clientEndpoint, uri, upgradeRequest, upgradeListener)
                .get(5, TimeUnit.SECONDS));
        assertThat(t, instanceOf(ExecutionException.class));
        assertThat(t.getCause(), instanceOf(UpgradeException.class));
        assertThat(
            t.getCause().getMessage(),
            containsString("Failed to upgrade to websocket: Unexpected HTTP Response Status Code: 418"));

        Response response = responseFuture.get(5, TimeUnit.SECONDS);
        String content = contentFuture.get(5, TimeUnit.SECONDS);
        assertThat(response.getStatus(), equalTo(HttpStatus.IM_A_TEAPOT_418));
        assertThat(response.getHeaders().get("specialHeader"), equalTo("value123"));
        assertThat(content, equalTo("failed by test"));
    }

    @Test
    public void testServerAbort() throws Exception
    {
        before((req, resp, cb) ->
        {
            req.getConnectionMetaData().getConnection().getEndPoint().close();
            cb.failed(new EofException());
            return null;
        });

        EchoSocket clientEndpoint = new EchoSocket();
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();

        CompletableFuture<Void> onHandShakeRequest = new CompletableFuture<>();
        CompletableFuture<Void> onHandShakeResponse = new CompletableFuture<>();
        JettyUpgradeListener upgradeListener = new JettyUpgradeListener()
        {
            @Override
            public void onHandshakeRequest(Request request)
            {
                onHandShakeRequest.complete(null);
            }

            @Override
            public void onHandshakeResponse(Request request, Response response)
            {
                onHandShakeResponse.complete(null);
            }
        };

        Throwable t = assertThrows(
            Throwable.class, () -> _client.connect(clientEndpoint, uri, upgradeRequest, upgradeListener)
                .get(5, TimeUnit.SECONDS));
        assertThat(t, instanceOf(ExecutionException.class));
        assertThat(t.getCause(), instanceOf(EOFException.class));

        assertDoesNotThrow(() -> onHandShakeRequest.get(5, TimeUnit.SECONDS));
        assertThrows(Throwable.class, () -> onHandShakeResponse.get(1, TimeUnit.SECONDS));
    }
}
