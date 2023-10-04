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

package org.eclipse.jetty.websocket.jmh;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@Threads(4)
@Warmup(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class WebSocketBenchmark
{
    private Server _server;
    private ServerConnector _connector;
    private WebSocketClient _client;

    @Param({"BINDING", "NON_BINDING"})
    public static String methodHandleType;

    @Setup(Level.Trial)
    public void setupTrial() throws Exception
    {
        int capacity;

        switch (methodHandleType)
        {
            case "BINDING":
                System.setProperty("jetty.websocket.methodholder.binding", Boolean.TRUE.toString());
                break;
            case "NON_BINDING":
                System.setProperty("jetty.websocket.methodholder.binding", Boolean.FALSE.toString());
                break;
            default:
                throw new IllegalStateException("Unknown methodHandleType Parameter");
        }

        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);
        ServletContextHandler contextHandler = new ServletContextHandler();
        _server.setHandler(contextHandler);
        JettyWebSocketServletContainerInitializer.configure(contextHandler, ((servletContext, container) ->
            container.addMapping("/", (req, resp) -> new ServerSocket())));
        _server.start();

        _client = new WebSocketClient();
        _client.start();
    }

    @TearDown(Level.Trial)
    public void stopTrial() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public void test() throws Exception
    {
        ClientSocket clientSocket = new ClientSocket();
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort());

        Session session = _client.connect(clientSocket, uri).get(5, TimeUnit.SECONDS);
        for (int i = 0; i < 1000; i++)
        {
            session.getRemote().sendString("fixed string");
        }

        session.getRemote().sendString("close");
        if (!clientSocket._closeLatch.await(5, TimeUnit.SECONDS))
            throw new IllegalStateException();
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(WebSocketBenchmark.class.getSimpleName())
            .warmupIterations(10)
            .measurementIterations(20)
            //.addProfiler(GCProfiler.class)
            .forks(1)
            .threads(1)
            .build();

        new Runner(opt).run();
    }

    @WebSocket
    public static class ServerSocket
    {
        @OnWebSocketConnect
        public void onOpen(Session session)
        {
        }

        @OnWebSocketMessage
        public void onMessage(Session session, String message)
        {
            if ("close".equals(message))
                session.close();
        }
    }

    @WebSocket
    public static class ClientSocket
    {
        public CountDownLatch _closeLatch = new CountDownLatch(1);

        @OnWebSocketConnect
        public void onOpen(Session session)
        {
        }

        @OnWebSocketMessage
        public void onMessage(Session session, String message)
        {
        }

        @OnWebSocketClose
        public void onClose(Session session, int statusCode, String reason)
        {
            _closeLatch.countDown();
        }
    }
}


