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

package org.eclipse.jetty.spdy.server.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.NegotiatingServerConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.client.SPDYClient;
import org.eclipse.jetty.spdy.server.http.SPDYTestUtils;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static junit.framework.Assert.fail;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

@Ignore
@RunWith(value = Parameterized.class)
public abstract class ProxySPDYToHTTPLoadTest
{
    private static final Logger LOG = Log.getLogger(ProxySPDYToHTTPLoadTest.class);

    @Parameterized.Parameters
    public static Collection<Short[]> parameters()
    {
        return Arrays.asList(new Short[]{SPDY.V2}, new Short[]{SPDY.V3});
    }

    @Rule
    public final TestTracker tracker = new TestTracker();
    private final short version;
    private final NegotiatingServerConnectionFactory negotiator;
    private final String server1String = "server1";
    private final String server2String = "server2";
    private SPDYClient.Factory factory;
    private Server server1;
    private Server server2;
    private Server proxy;
    private ServerConnector proxyConnector;
    private SslContextFactory sslContextFactory = SPDYTestUtils.newSslContextFactory();

    public ProxySPDYToHTTPLoadTest(short version, NegotiatingServerConnectionFactory negotiator)
    {
        this.version = version;
        this.negotiator = negotiator;
    }

    @Before
    public void init() throws Exception
    {
        // change the ports if you want to trace the network traffic
        server1 = startServer(new TestServerHandler(server1String, null), 0);
        server2 = startServer(new TestServerHandler(server2String, null), 0);
        factory = new SPDYClient.Factory(sslContextFactory);
        factory.start();
    }

    @After
    public void destroy() throws Exception
    {
        stopServer(server1);
        stopServer(server2);
        if (proxy != null)
        {
            proxy.stop();
            proxy.join();
        }
        factory.stop();
    }

    private void stopServer(Server server) throws Exception
    {
        if (server != null)
        {
            server.stop();
            server.join();
        }
    }

    protected Server startServer(Handler handler, int port) throws Exception
    {
        QueuedThreadPool threadPool = new QueuedThreadPool(256);
        threadPool.setName("upstreamServerQTP");
        Server server = new Server(threadPool);
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.setHandler(handler);
        server.addConnector(connector);
        server.start();
        return server;
    }

    private InetSocketAddress getServerAddress(Server server)
    {
        return new InetSocketAddress("localhost", ((ServerConnector)server.getConnectors()[0]).getLocalPort());
    }

    protected InetSocketAddress startProxy(InetSocketAddress server1, InetSocketAddress server2,
                                           long proxyConnectorTimeout, long proxyEngineTimeout) throws Exception
    {
        QueuedThreadPool threadPool = new QueuedThreadPool(256);
        threadPool.setName("proxyQTP");
        proxy = new Server(threadPool);
        ProxyEngineSelector proxyEngineSelector = new ProxyEngineSelector();
        HttpClient httpClient = new HttpClient();
        httpClient.start();
        httpClient.setIdleTimeout(proxyEngineTimeout);
        HTTPProxyEngine httpProxyEngine = new HTTPProxyEngine(httpClient);
        proxyEngineSelector.putProxyEngine("http/1.1", httpProxyEngine);

        proxyEngineSelector.putProxyServerInfo("localhost", new ProxyEngineSelector.ProxyServerInfo("http/1.1",
                server1.getHostName(), server1.getPort()));
        // server2 will be available at two different ProxyServerInfos with different hosts
        proxyEngineSelector.putProxyServerInfo("127.0.0.1", new ProxyEngineSelector.ProxyServerInfo("http/1.1",
                server2.getHostName(), server2.getPort()));
        proxyEngineSelector.putProxyServerInfo("127.0.0.2", new ProxyEngineSelector.ProxyServerInfo("http/1.1",
                server2.getHostName(), server2.getPort()));

        proxyConnector = new HTTPSPDYProxyServerConnector(proxy, sslContextFactory, new HttpConfiguration(), proxyEngineSelector, negotiator);
        proxyConnector.setPort(0);
        proxyConnector.setIdleTimeout(proxyConnectorTimeout);
        proxy.addConnector(proxyConnector);
        proxy.start();
        return new InetSocketAddress("localhost", proxyConnector.getLocalPort());
    }

    @Test
    public void testSimpleLoadTest() throws Exception
    {
        final InetSocketAddress proxyAddress = startProxy(getServerAddress(server1), getServerAddress(server2), 30000,
                30000);

        final int requestsPerClient = 50;

        ExecutorService executorService = Executors.newFixedThreadPool(3);

        Runnable client1 = createClientRunnable(proxyAddress, requestsPerClient, server1String, "localhost");
        Runnable client2 = createClientRunnable(proxyAddress, requestsPerClient, server2String, "127.0.0.1");
        Runnable client3 = createClientRunnable(proxyAddress, requestsPerClient, server2String, "127.0.0.2");

        List<Future> futures = new ArrayList<>();

        futures.add(executorService.submit(client1));
        futures.add(executorService.submit(client2));
        futures.add(executorService.submit(client3));

        for (Future future : futures)
        {
            future.get(60, TimeUnit.SECONDS);
        }
    }

    private Runnable createClientRunnable(final InetSocketAddress proxyAddress, final int requestsPerClient,
                                          final String serverIdentificationString, final String serverHost)
    {
        Runnable client = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    Session client = factory.newSPDYClient(version).connect(proxyAddress, null);
                    for (int i = 0; i < requestsPerClient; i++)
                    {
                        sendSingleClientRequest(proxyAddress, client, serverIdentificationString, serverHost);
                    }
                }
                catch (InterruptedException | ExecutionException | TimeoutException e)
                {
                    e.printStackTrace();
                    fail();
                }
            }
        };
        return client;
    }

    private void sendSingleClientRequest(InetSocketAddress proxyAddress, Session client, final String serverIdentificationString, String serverHost) throws ExecutionException, InterruptedException, TimeoutException
    {
        final String data = UUID.randomUUID().toString();

        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);

        Fields headers = SPDYTestUtils.createHeaders(serverHost, proxyAddress.getPort(), version, "POST", "/");

        Stream stream = client.syn(new SynInfo(headers, false), new StreamFrameListener.Adapter()
        {
            private final ByteArrayOutputStream result = new ByteArrayOutputStream();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                LOG.debug("Got reply: {}", replyInfo);
                Fields headers = replyInfo.getHeaders();
                assertThat("response comes from the given server", headers.get(serverIdentificationString),
                        is(notNullValue()));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                result.write(dataInfo.asBytes(true), 0, dataInfo.length());
                if (dataInfo.isClose())
                {
                    LOG.debug("Got last dataFrame: {}", dataInfo);
                    assertThat("received data matches send data", result.toString(), is(data));
                    dataLatch.countDown();
                }
            }
        });

        stream.data(new StringDataInfo(data, true), new Callback.Adapter());

        assertThat("reply has been received", replyLatch.await(15, TimeUnit.SECONDS), is(true));
        assertThat("data has been received", dataLatch.await(15, TimeUnit.SECONDS), is(true));
        LOG.debug("Successfully received response");
    }

    private class TestServerHandler extends DefaultHandler
    {
        private final String responseHeader;
        private final byte[] responseData;

        private TestServerHandler(String responseHeader, byte[] responseData)
        {
            this.responseHeader = responseHeader;
            this.responseData = responseData;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request,
                           HttpServletResponse response) throws IOException, ServletException
        {
            assertThat("Via Header is set", baseRequest.getHeader("X-Forwarded-For"), is(notNullValue()));
            assertThat("X-Forwarded-For Header is set", baseRequest.getHeader("X-Forwarded-For"),
                    is(notNullValue()));
            assertThat("X-Forwarded-Host Header is set", baseRequest.getHeader("X-Forwarded-Host"),
                    is(notNullValue()));
            assertThat("X-Forwarded-Proto Header is set", baseRequest.getHeader("X-Forwarded-Proto"),
                    is(notNullValue()));
            assertThat("X-Forwarded-Server Header is set", baseRequest.getHeader("X-Forwarded-Server"),
                    is(notNullValue()));
            baseRequest.setHandled(true);

            IO.copy(request.getInputStream(), response.getOutputStream());

            if (responseHeader != null)
                response.addHeader(responseHeader, "bar");
            if (responseData != null)
                response.getOutputStream().write(responseData);
        }
    }

}
