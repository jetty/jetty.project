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

package org.eclipse.jetty.http2.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.proxy.AsyncProxyServlet;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProxyTest
{
    private HTTP2Client client;
    private Server proxy;
    private ServerConnector proxyConnector;
    private Server server;
    private ServerConnector serverConnector;

    private void startServer(HttpServlet servlet) throws Exception
    {
        QueuedThreadPool serverPool = new QueuedThreadPool();
        serverPool.setName("server");
        server = new Server(serverPool);
        serverConnector = new ServerConnector(server);
        server.addConnector(serverConnector);

        ServletContextHandler appCtx = new ServletContextHandler(server, "/", true, false);
        ServletHolder appServletHolder = new ServletHolder(servlet);
        appCtx.addServlet(appServletHolder, "/*");

        server.start();
    }

    private void startProxy(HttpServlet proxyServlet, Map<String, String> initParams) throws Exception
    {
        QueuedThreadPool proxyPool = new QueuedThreadPool();
        proxyPool.setName("proxy");
        proxy = new Server(proxyPool);

        HttpConfiguration configuration = new HttpConfiguration();
        configuration.setSendDateHeader(false);
        configuration.setSendServerVersion(false);
        String value = initParams.get("outputBufferSize");
        if (value != null)
            configuration.setOutputBufferSize(Integer.parseInt(value));
        proxyConnector = new ServerConnector(proxy, new HTTP2ServerConnectionFactory(configuration));
        proxy.addConnector(proxyConnector);

        ServletContextHandler proxyContext = new ServletContextHandler(proxy, "/", true, false);
        ServletHolder proxyServletHolder = new ServletHolder(proxyServlet);
        proxyServletHolder.setInitParameters(initParams);
        proxyContext.addServlet(proxyServletHolder, "/*");

        proxy.start();
    }

    private void startClient() throws Exception
    {
        QueuedThreadPool clientExecutor = new QueuedThreadPool();
        clientExecutor.setName("client");
        client = new HTTP2Client();
        client.setExecutor(clientExecutor);
        client.start();
    }

    private Session newClient(Session.Listener listener) throws Exception
    {
        String host = "localhost";
        int port = proxyConnector.getLocalPort();
        InetSocketAddress address = new InetSocketAddress(host, port);
        FuturePromise<Session> promise = new FuturePromise<>();
        client.connect(address, listener, promise);
        return promise.get(5, TimeUnit.SECONDS);
    }

    private MetaData.Request newRequest(String method, String path, HttpFields fields)
    {
        String host = "localhost";
        int port = proxyConnector.getLocalPort();
        String authority = host + ":" + port;
        return new MetaData.Request(method, HttpScheme.HTTP.asString(), new HostPortHttpField(authority), path, HttpVersion.HTTP_2, fields, -1);
    }

    @AfterEach
    public void dispose() throws Exception
    {
        client.stop();
        proxy.stop();
        server.stop();
    }

    @Test
    public void testHTTPVersion() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                assertEquals(HttpVersion.HTTP_1_1.asString(), request.getProtocol());
            }
        });
        Map<String, String> params = new HashMap<>();
        params.put("proxyTo", "http://localhost:" + serverConnector.getLocalPort());
        startProxy(new AsyncProxyServlet.Transparent(), params);
        startClient();

        CountDownLatch clientLatch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter());
        MetaData.Request metaData = newRequest("GET", "/", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                assertTrue(frame.isEndStream());
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(HttpStatus.OK_200, response.getStatus());
                clientLatch.countDown();
            }
        });

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerBigDownloadSlowClient() throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        byte[] content = new byte[1024 * 1024];
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.getOutputStream().write(content);
                serverLatch.countDown();
            }
        });
        Map<String, String> params = new HashMap<>();
        params.put("proxyTo", "http://localhost:" + serverConnector.getLocalPort());
        startProxy(new AsyncProxyServlet.Transparent(), params);
        startClient();

        CountDownLatch clientLatch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter());
        MetaData.Request metaData = newRequest("GET", "/", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                try
                {
                    TimeUnit.MILLISECONDS.sleep(1);
                    callback.succeeded();
                    if (frame.isEndStream())
                        clientLatch.countDown();
                }
                catch (InterruptedException x)
                {
                    callback.failed(x);
                }
            }
        });

        assertTrue(serverLatch.await(15, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(15, TimeUnit.SECONDS));
    }
}
