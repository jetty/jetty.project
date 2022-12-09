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

package org.eclipse.jetty.http2.tests;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.proxy.ProxyHandler;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReverseProxyTest
{
    private HTTP2Client client;
    private Server proxy;
    private ServerConnector proxyConnector;
    private Server server;
    private ServerConnector serverConnector;

    private void startServer(Handler handler) throws Exception
    {
        QueuedThreadPool serverPool = new QueuedThreadPool();
        serverPool.setName("server");
        server = new Server(serverPool);
        serverConnector = new ServerConnector(server);
        server.addConnector(serverConnector);
        server.setHandler(handler);
        server.start();
    }

    private void startProxy(ProxyHandler handler) throws Exception
    {
        QueuedThreadPool proxyPool = new QueuedThreadPool();
        proxyPool.setName("proxy");
        proxy = new Server(proxyPool);
        HttpConfiguration configuration = new HttpConfiguration();
        configuration.setSendDateHeader(false);
        configuration.setSendServerVersion(false);
        proxyConnector = new ServerConnector(proxy, new HTTP2ServerConnectionFactory(configuration));
        proxy.addConnector(proxyConnector);
        proxy.setHandler(handler);
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
        startServer(new Handler.Abstract()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                assertEquals(HttpVersion.HTTP_1_1.asString(), request.getConnectionMetaData().getProtocol());
                callback.succeeded();
            }
        });
        startProxy(new ProxyHandler.Reverse(clientToProxyRequest ->
            HttpURI.build(clientToProxyRequest.getHttpURI()).port(serverConnector.getLocalPort())));
        startClient();

        CountDownLatch clientLatch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener() {});
        MetaData.Request metaData = newRequest("GET", "/", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener()
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
        startServer(new Handler.Abstract()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                response.write(true, ByteBuffer.wrap(content), Callback.from(() ->
                {
                    callback.succeeded();
                    serverLatch.countDown();
                }, callback::failed));
            }
        });
        startProxy(new ProxyHandler.Reverse(clientToProxyRequest ->
            HttpURI.build(clientToProxyRequest.getHttpURI()).port(serverConnector.getLocalPort())));
        startClient();

        CountDownLatch clientLatch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener() {});
        MetaData.Request metaData = newRequest("GET", "/", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                try
                {
                    TimeUnit.MILLISECONDS.sleep(1);
                    Stream.Data data = stream.readData();
                    data.release();
                    if (data.frame().isEndStream())
                        clientLatch.countDown();
                    else
                        stream.demand();
                }
                catch (InterruptedException x)
                {
                    if (LoggerFactory.getLogger(Server.class).isDebugEnabled())
                        LoggerFactory.getLogger(Server.class).debug("ignored", x);
                }
            }
        });

        assertTrue(serverLatch.await(15, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(15, TimeUnit.SECONDS));
    }
}
