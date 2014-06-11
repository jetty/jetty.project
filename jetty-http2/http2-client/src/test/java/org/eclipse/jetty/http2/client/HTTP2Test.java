//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.hpack.MetaData;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class HTTP2Test
{
    private Server server;
    private ServerConnector connector;
    private String path;
    private HTTP2Client client;

    private void startServer(HttpServlet servlet) throws Exception
    {
        QueuedThreadPool serverExecutor = new QueuedThreadPool();
        serverExecutor.setName("server");
        server = new Server(serverExecutor);
        connector = new ServerConnector(server, new HTTP2ServerConnectionFactory(new HttpConfiguration()));
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/");
        path = "/test";
        context.addServlet(new ServletHolder(servlet), path);

        QueuedThreadPool clientExecutor = new QueuedThreadPool();
        clientExecutor.setName("client");
        client = new HTTP2Client(clientExecutor);
        server.addBean(client);

        server.start();
    }

    @After
    public void dispose() throws Exception
    {
        server.stop();
    }

    @Test
    public void testRequestNoContentResponseNoContent() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();
        String authority = host + ":" + port;
        InetSocketAddress address = new InetSocketAddress(host, port);
        FuturePromise<Session> promise = new FuturePromise<>();
        client.connect(address, new Session.Listener.Adapter(), promise);
        Session session = promise.get();

        HttpFields fields = new HttpFields();
        MetaData.Request metaData = new MetaData.Request(HttpScheme.HTTP, "GET", authority, host, port, path, fields);
        HeadersFrame frame = new HeadersFrame(1, metaData, null, true);
        final CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                Assert.assertTrue(stream.isClosed());
                Assert.assertTrue(stream.getId() > 0);

                Assert.assertTrue(frame.isEndStream());
                Assert.assertEquals(stream.getId(), frame.getStreamId());
                Assert.assertTrue(frame.getMetaData().isResponse());
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                Assert.assertEquals(200, response.getStatus());

                latch.countDown();
            }
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestNoContentResponseContent() throws Exception
    {
        final byte[] content = "Hello World!".getBytes(StandardCharsets.UTF_8);
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                resp.getOutputStream().write(content);
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();
        String authority = host + ":" + port;
        InetSocketAddress address = new InetSocketAddress(host, port);
        FuturePromise<Session> promise = new FuturePromise<>();
        client.connect(address, new Session.Listener.Adapter(), promise);
        Session session = promise.get();

        HttpFields fields = new HttpFields();
        MetaData.Request metaData = new MetaData.Request(HttpScheme.HTTP, "GET", authority, host, port, path, fields);
        HeadersFrame frame = new HeadersFrame(1, metaData, null, true);
        final CountDownLatch latch = new CountDownLatch(2);
        session.newStream(frame, new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                Assert.assertFalse(stream.isClosed());
                Assert.assertTrue(stream.getId() > 0);

                Assert.assertFalse(frame.isEndStream());
                Assert.assertEquals(stream.getId(), frame.getStreamId());
                Assert.assertTrue(frame.getMetaData().isResponse());
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                Assert.assertEquals(200, response.getStatus());

                latch.countDown();
            }

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                Assert.assertTrue(stream.isClosed());

                Assert.assertTrue(frame.isEndStream());
                Assert.assertEquals(ByteBuffer.wrap(content), frame.getData());

                latch.countDown();
            }
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
