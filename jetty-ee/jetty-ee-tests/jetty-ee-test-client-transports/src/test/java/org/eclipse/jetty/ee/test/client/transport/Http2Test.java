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

package org.eclipse.jetty.ee.test.client.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee.servlet.ServletContextHandler;
import org.eclipse.jetty.ee.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class Http2Test
{
    private Server server;
    private ServerConnector connector;
    private HTTP2Client client;

    private void start(HttpServlet httpServlet) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new ServerConnector(server, 1, 1, new HTTP2CServerConnectionFactory(new HttpConfiguration()));
        server.addConnector(connector);
        ServletContextHandler servletContextHandler = new ServletContextHandler("/");
        servletContextHandler.addServlet(new ServletHolder(httpServlet), "/*");
        server.setHandler(servletContextHandler);
        server.start();

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HTTP2Client();
        client.setExecutor(clientThreads);
        client.start();
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @ParameterizedTest
    @CsvSource(useHeadersInDisplayName = true, textBlock = """
        contentLengthMode, flushMode
        int              , false
        int              , true
        long             , false
        long             , true
        string           , false
        string           , true
        """)
    public void testServletContentLengthDoesSendEmptyLastDataFrame(String contentLengthMode, boolean flushMode) throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                int length = 64;
                switch (contentLengthMode)
                {
                    case "int" -> response.setContentLength(length);
                    case "long" -> response.setContentLengthLong(length);
                    case "string" -> response.setHeader("Content-Length", String.valueOf(length));
                }
                response.getOutputStream().write(new byte[length]);
                if (flushMode)
                    response.flushBuffer();
            }
        });

        InetSocketAddress address = new InetSocketAddress("localhost", connector.getLocalPort());
        FuturePromise<Session> sessionPromise = new FuturePromise<>();
        client.connect(address, new Session.Listener() {}, sessionPromise);
        Session session = sessionPromise.get(5, TimeUnit.SECONDS);

        MetaData.Request metaData = new MetaData.Request("GET", HttpURI.from("/"), HttpVersion.HTTP_2, HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        Queue<Stream.Data> datas = new ConcurrentLinkedQueue<>();
        session.newStream(frame, new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                while (true)
                {
                    Stream.Data data = stream.readData();
                    if (data == null)
                    {
                        stream.demand();
                        return;
                    }
                    datas.offer(data);
                    if (data.frame().isEndStream())
                        break;
                }
            }
        }).get(5, TimeUnit.SECONDS);

        await().atMost(5, TimeUnit.SECONDS).until(() -> datas.stream().anyMatch(d -> d.frame().isEndStream()));

        // There should only be 1 DATA frame with data and last=true,
        // not a DATA frame with data, and then an empty, last DATA frame.
        assertThat(datas.toString(), datas.size(), equalTo(1));
    }
}
