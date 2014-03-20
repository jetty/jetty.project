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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.client.SPDYClient;
import org.eclipse.jetty.spdy.server.SPDYServerConnectionFactory;
import org.eclipse.jetty.spdy.server.SPDYServerConnector;
import org.eclipse.jetty.spdy.server.http.SPDYTestUtils;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static junit.framework.Assert.fail;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

@RunWith(value = Parameterized.class)
public abstract class ProxySPDYToSPDYLoadTest
{
    @Parameterized.Parameters
    public static Collection<Short[]> parameters()
    {
        return Arrays.asList(new Short[]{SPDY.V2}, new Short[]{SPDY.V3});
    }

    @Rule
    public final TestTracker tracker = new TestTracker();
    private final short version;
    private static final String UUID_HEADER_NAME = "uuidHeader";
    private static final String SERVER_ID_HEADER = "serverId";
    private SPDYClient.Factory factory;
    private Server server;
    private Server proxy;
    private ServerConnector proxyConnector;
    private SslContextFactory sslContextFactory = SPDYTestUtils.newSslContextFactory();

    public ProxySPDYToSPDYLoadTest(short version)
    {
        this.version = version;
    }

    protected InetSocketAddress startServer(ServerSessionFrameListener listener) throws Exception
    {
        server = new Server();
        SPDYServerConnector serverConnector = new SPDYServerConnector(server, sslContextFactory, listener);
        serverConnector.addConnectionFactory(new SPDYServerConnectionFactory(version, listener));
        serverConnector.setPort(0);
        server.addConnector(serverConnector);
        server.start();
        return new InetSocketAddress("localhost", serverConnector.getLocalPort());
    }

    protected InetSocketAddress startProxy(InetSocketAddress server1, InetSocketAddress server2) throws Exception
    {
        proxy = new Server();
        ProxyEngineSelector proxyEngineSelector = new ProxyEngineSelector();

        SPDYProxyEngine spdyProxyEngine = new SPDYProxyEngine(factory);
        proxyEngineSelector.putProxyEngine("spdy/" + version, spdyProxyEngine);

        proxyEngineSelector.putProxyServerInfo("localhost", new ProxyEngineSelector.ProxyServerInfo("spdy/" + version,
                "localhost", server1.getPort()));
        // server2 will be available at two different ProxyServerInfos with different hosts
        proxyEngineSelector.putProxyServerInfo("127.0.0.1", new ProxyEngineSelector.ProxyServerInfo("spdy/" + version,
                "127.0.0.1", server2.getPort()));
        // ProxyServerInfo is mapped to 127.0.0.2 in the proxyEngineSelector. However to be able to connect the
        // ProxyServerInfo contains 127.0.0.1 as target host
        proxyEngineSelector.putProxyServerInfo("127.0.0.2", new ProxyEngineSelector.ProxyServerInfo("spdy/" + version,
                "127.0.0.1", server2.getPort()));

        proxyConnector = new HTTPSPDYProxyServerConnector(proxy, sslContextFactory, proxyEngineSelector);
        proxyConnector.setPort(0);
        proxy.addConnector(proxyConnector);
        proxy.start();
        return new InetSocketAddress("localhost", proxyConnector.getLocalPort());
    }

    @Before
    public void init() throws Exception
    {
        factory = new SPDYClient.Factory(sslContextFactory);
        factory.start();
    }

    @After
    public void destroy() throws Exception
    {
        server.stop();
        server.join();
        proxy.stop();
        proxy.join();
        factory.stop();
    }

    @Test
    public void testSimpleLoadTest() throws Exception
    {
        String server1String = "server1";
        String server2String = "server2";

        InetSocketAddress server1 = startServer(new TestServerFrameListener(server1String));
        InetSocketAddress server2 = startServer(new TestServerFrameListener(server2String));
        final InetSocketAddress proxyAddress = startProxy(server1, server2);

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
                    fail();
                    e.printStackTrace();
                }
            }
        };
        return client;
    }

    private void sendSingleClientRequest(InetSocketAddress proxyAddress, Session client, final String serverIdentificationString, String serverHost) throws ExecutionException, InterruptedException, TimeoutException
    {
        final String uuid = UUID.randomUUID().toString();

        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);

        Fields headers = SPDYTestUtils.createHeaders(serverHost, proxyAddress.getPort(), version, "POST", "/");
        headers.add(UUID_HEADER_NAME, uuid);

        Stream stream = client.syn(new SynInfo(headers, false), new StreamFrameListener.Adapter()
        {
            private final ByteArrayOutputStream result = new ByteArrayOutputStream();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Fields headers = replyInfo.getHeaders();
                assertThat("uuid matches expected uuid", headers.get(UUID_HEADER_NAME).getValue(), is(uuid));
                assertThat("response comes from the given server", headers.get(SERVER_ID_HEADER).getValue(),
                        is(serverIdentificationString));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                result.write(dataInfo.asBytes(true), 0, dataInfo.length());
                if (dataInfo.isClose())
                {
                    assertThat("received data matches send data", uuid, is(result.toString()));
                    dataLatch.countDown();
                }
            }
        });

        stream.data(new StringDataInfo(uuid, true), new Callback.Adapter());

        assertThat("reply has been received", replyLatch.await(5, TimeUnit.SECONDS), is(true));
        assertThat("data has been received", dataLatch.await(5, TimeUnit.SECONDS), is(true));
    }

    private class TestServerFrameListener extends ServerSessionFrameListener.Adapter
    {
        private String serverId;

        private TestServerFrameListener(String serverId)
        {
            this.serverId = serverId;
        }

        @Override
        public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
        {
            Fields requestHeaders = synInfo.getHeaders();
            Assert.assertNotNull(requestHeaders.get("via"));
            Fields.Field uuidHeader = requestHeaders.get(UUID_HEADER_NAME);
            Assert.assertNotNull(uuidHeader);

            Fields responseHeaders = new Fields();
            responseHeaders.put(UUID_HEADER_NAME, uuidHeader.getValue());
            responseHeaders.put(SERVER_ID_HEADER, serverId);
            stream.reply(new ReplyInfo(responseHeaders, false), new Callback.Adapter());
            return new StreamFrameListener.Adapter()
            {
                @Override
                public void onData(Stream stream, DataInfo dataInfo)
                {
                    stream.data(dataInfo, new Callback.Adapter());
                }
            };
        }
    }

}
