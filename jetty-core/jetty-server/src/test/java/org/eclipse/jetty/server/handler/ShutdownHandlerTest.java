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

package org.eclipse.jetty.server.handler;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ShutdownHandlerTest
{
    private Server server;

    public void createServer(Handler handler) throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void teardown()
    {
        LifeCycle.stop(server);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcdefg", "a token with space", "euro-€-token"})
    public void testShutdownServerWithCorrectTokenAndFromLocalhost(String shutdownToken) throws Exception
    {
        ShutdownHandler shutdownHandler = new ShutdownHandler(shutdownToken);
        shutdownHandler.setHandler(new EchoHandler());

        InetSocketAddress fakeRemoteAddr = new InetSocketAddress("127.0.0.1", 22033);
        Handler.Singleton fakeRemoteAddressHandler = new FakeRemoteAddressHandlerWrapper(fakeRemoteAddr);
        fakeRemoteAddressHandler.setHandler(shutdownHandler);

        createServer(fakeRemoteAddressHandler);
        server.start();

        CountDownLatch stopLatch = new CountDownLatch(1);
        server.addEventListener(new LifeCycle.Listener()
        {
            @Override
            public void lifeCycleStopped(LifeCycle event)
            {
                stopLatch.countDown();
            }
        });

        HttpTester.Response response = sendShutdownRequest(shutdownToken);
        assertEquals(HttpStatus.OK_200, response.getStatus());

        assertTrue(stopLatch.await(5, TimeUnit.SECONDS));
        assertEquals(AbstractLifeCycle.STOPPED, server.getState());
    }

    @Test
    public void testWrongToken() throws Exception
    {
        String shutdownToken = "abcdefg";
        ShutdownHandler shutdownHandler = new ShutdownHandler(shutdownToken);
        shutdownHandler.setHandler(new EchoHandler());
        createServer(shutdownHandler);
        server.start();

        HttpTester.Response response = sendShutdownRequest("wrongToken");
        assertEquals(HttpStatus.UNAUTHORIZED_401, response.getStatus());

        Thread.sleep(1000);
        assertEquals(AbstractLifeCycle.STARTED, server.getState());
    }

    @Test
    public void testShutdownRequestNotFromLocalhost() throws Exception
    {
        String shutdownToken = "abcdefg";

        ShutdownHandler shutdownHandler = new ShutdownHandler(shutdownToken);
        shutdownHandler.setHandler(new EchoHandler());

        InetSocketAddress fakeRemoteAddr = new InetSocketAddress("192.168.0.1", 12345);
        Handler.Singleton fakeRemoteAddressHandler = new FakeRemoteAddressHandlerWrapper(fakeRemoteAddr);
        fakeRemoteAddressHandler.setHandler(shutdownHandler);

        createServer(fakeRemoteAddressHandler);
        server.start();

        HttpTester.Response response = sendShutdownRequest(shutdownToken);
        assertEquals(HttpStatus.UNAUTHORIZED_401, response.getStatus());

        Thread.sleep(1000);
        assertEquals(AbstractLifeCycle.STARTED, server.getState());
    }

    private HttpTester.Response sendShutdownRequest(String shutdownToken) throws Exception
    {
        URI shutdownUri = server.getURI().resolve("/shutdown?token=" + URLEncoder.encode(shutdownToken, StandardCharsets.UTF_8));
        try (Socket client = new Socket(shutdownUri.getHost(), shutdownUri.getPort());
             OutputStream output = client.getOutputStream();
             InputStream input = client.getInputStream())
        {
            String rawRequest = """
                POST %s?%s HTTP/1.1
                Host: %s:%d
                Connection: close
                Content-Length: 0
                                
                """.formatted(shutdownUri.getRawPath(), shutdownUri.getRawQuery(), shutdownUri.getHost(), shutdownUri.getPort());

            output.write(rawRequest.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Response response = HttpTester.parseResponse(input);
            return response;
        }
    }

    static class FakeRemoteAddressHandlerWrapper extends Handler.Wrapper
    {
        private final InetSocketAddress fakeRemoteAddress;

        public FakeRemoteAddressHandlerWrapper(InetSocketAddress fakeRemoteAddress)
        {
            super();
            this.fakeRemoteAddress = fakeRemoteAddress;
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            Request fakedRequest = FakeRemoteAddressRequest.from(request, this.fakeRemoteAddress);
            return super.handle(fakedRequest, response, callback);
        }
    }

    static class FakeRemoteAddressConnectionMetadata extends ConnectionMetaData.Wrapper
    {
        private final InetSocketAddress fakeRemoteAddress;

        public FakeRemoteAddressConnectionMetadata(ConnectionMetaData wrapped, InetSocketAddress fakeRemoteAddress)
        {
            super(wrapped);
            this.fakeRemoteAddress = fakeRemoteAddress;
        }

        @Override
        public SocketAddress getRemoteSocketAddress()
        {
            return this.fakeRemoteAddress;
        }
    }

    static class FakeRemoteAddressRequest extends Request.Wrapper
    {
        private final ConnectionMetaData fakeConnectionMetaData;

        public static Request from(Request request, InetSocketAddress fakeRemoteAddress)
        {
            ConnectionMetaData fakeRemoteConnectionMetadata = new FakeRemoteAddressConnectionMetadata(request.getConnectionMetaData(), fakeRemoteAddress);
            return new FakeRemoteAddressRequest(request, fakeRemoteConnectionMetadata);
        }

        public FakeRemoteAddressRequest(Request wrapped, ConnectionMetaData fakeRemoteConnectionMetadata)
        {
            super(wrapped);
            this.fakeConnectionMetaData = fakeRemoteConnectionMetadata;
        }

        @Override
        public ConnectionMetaData getConnectionMetaData()
        {
            return this.fakeConnectionMetaData;
        }
    }
}
