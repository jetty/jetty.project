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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.spdy.api.BytesDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.PingInfo;
import org.eclipse.jetty.spdy.api.PingResultInfo;
import org.eclipse.jetty.spdy.api.PushInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.client.SPDYClient;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;
import org.eclipse.jetty.spdy.server.SPDYServerConnectionFactory;
import org.eclipse.jetty.spdy.server.SPDYServerConnector;
import org.eclipse.jetty.spdy.server.http.SPDYTestUtils;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

@RunWith(value = Parameterized.class)
public abstract class ProxySPDYToSPDYTest
{
    @Parameterized.Parameters
    public static Collection<Short[]> parameters()
    {
        return Arrays.asList(new Short[]{SPDY.V2}, new Short[]{SPDY.V3});
    }

    @Rule
    public final TestTracker tracker = new TestTracker();
    private final short version;
    private SPDYClient.Factory factory;
    private Server server;
    private Server proxy;
    private ServerConnector proxyConnector;
    private SslContextFactory sslContextFactory = SPDYTestUtils.newSslContextFactory();

    public ProxySPDYToSPDYTest(short version)
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

    protected InetSocketAddress startProxy(InetSocketAddress address) throws Exception
    {
        proxy = new Server();
        ProxyEngineSelector proxyEngineSelector = new ProxyEngineSelector();
        SPDYProxyEngine spdyProxyEngine = new SPDYProxyEngine(factory);
        proxyEngineSelector.putProxyEngine("spdy/" + version, spdyProxyEngine);
        proxyEngineSelector.putProxyServerInfo("localhost", new ProxyEngineSelector.ProxyServerInfo("spdy/" + version, address.getHostName(), address.getPort()));
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
        if (server != null)
        {
            server.stop();
            server.join();
        }
        if (proxy != null)
        {
            proxy.stop();
            proxy.join();
        }
        factory.stop();
    }

    @Test
    public void testSYNThenREPLY() throws Exception
    {
        final String header = "foo";
        InetSocketAddress proxyAddress = startProxy(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Fields requestHeaders = synInfo.getHeaders();
                Assert.assertNotNull(requestHeaders.get("via"));
                Assert.assertNotNull(requestHeaders.get(header));

                Fields responseHeaders = new Fields();
                responseHeaders.put(header, "baz");
                stream.reply(new ReplyInfo(responseHeaders, true), new Callback.Adapter());
                return null;
            }
        }));
        proxyConnector.addConnectionFactory(proxyConnector.getConnectionFactory("spdy/" + version));

        Session client = factory.newSPDYClient(version).connect(proxyAddress, null);

        final CountDownLatch replyLatch = new CountDownLatch(1);
        Fields headers = SPDYTestUtils.createHeaders("localhost", proxyAddress.getPort(), version, "GET", "/");
        headers.put(header, "bar");
        client.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Fields headers = replyInfo.getHeaders();
                Assert.assertNotNull(headers.get(header));
                replyLatch.countDown();
            }
        });

        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));

        client.goAway(new GoAwayInfo(5, TimeUnit.SECONDS));
    }
@Test
    public void testSYNThenRSTFromUpstreamServer() throws Exception
    {
        final String header = "foo";
        InetSocketAddress proxyAddress = startProxy(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Fields requestHeaders = synInfo.getHeaders();
                Assert.assertNotNull(requestHeaders.get("via"));
                Assert.assertNotNull(requestHeaders.get(header));
                stream.getSession().rst(new RstInfo(stream.getId(), StreamStatus.REFUSED_STREAM), new Callback.Adapter());
                return null;
            }
        }));
        proxyConnector.addConnectionFactory(proxyConnector.getConnectionFactory("spdy/" + version));

        final CountDownLatch resetLatch = new CountDownLatch(1);
        Session client = factory.newSPDYClient(version).connect(proxyAddress, new SessionFrameListener.Adapter()
        {
            @Override
            public void onRst(Session session, RstInfo rstInfo)
            {
                resetLatch.countDown();
            }
        });

        Fields headers = SPDYTestUtils.createHeaders("localhost", proxyAddress.getPort(), version, "GET", "/");
        headers.put(header, "bar");
        client.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter());

        assertThat("reset is received by client", resetLatch.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testSYNThenREPLYAndDATA() throws Exception
    {
        final byte[] data = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);
        final String header = "foo";
        InetSocketAddress proxyAddress = startProxy(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Fields requestHeaders = synInfo.getHeaders();
                Assert.assertNotNull(requestHeaders.get("via"));
                Assert.assertNotNull(requestHeaders.get(header));

                Fields responseHeaders = new Fields();
                responseHeaders.put(header, "baz");
                stream.reply(new ReplyInfo(responseHeaders, false), new Callback.Adapter());
                stream.data(new BytesDataInfo(data, true), new Callback.Adapter());
                return null;
            }
        }));
        proxyConnector.addConnectionFactory(proxyConnector.getConnectionFactory("spdy/" + version));

        Session client = factory.newSPDYClient(version).connect(proxyAddress, null);

        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        Fields headers = new Fields();
        headers.put(HTTPSPDYHeader.HOST.name(version), "localhost:" + proxyAddress.getPort());
        headers.put(header, "bar");
        client.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final ByteArrayOutputStream result = new ByteArrayOutputStream();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Fields headers = replyInfo.getHeaders();
                Assert.assertNotNull(headers.get(header));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                result.write(dataInfo.asBytes(true), 0, dataInfo.length());
                if (dataInfo.isClose())
                {
                    Assert.assertArrayEquals(data, result.toByteArray());
                    dataLatch.countDown();
                }
            }
        });

        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));

        client.goAway(new GoAwayInfo(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSYNThenSPDYPushIsReceived() throws Exception
    {
        final byte[] data = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);
        InetSocketAddress proxyAddress = startProxy(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Fields responseHeaders = new Fields();
                responseHeaders.put(HTTPSPDYHeader.VERSION.name(version), "HTTP/1.1");
                responseHeaders.put(HTTPSPDYHeader.STATUS.name(version), "200 OK");
                stream.reply(new ReplyInfo(responseHeaders, false), new Callback.Adapter());

                Fields pushHeaders = new Fields();
                pushHeaders.put(HTTPSPDYHeader.URI.name(version), "/push");
                stream.push(new PushInfo(5, TimeUnit.SECONDS, pushHeaders, false), new Promise.Adapter<Stream>()
                {
                    @Override
                    public void succeeded(Stream pushStream)
                    {
                        pushStream.data(new BytesDataInfo(data, true), new Callback.Adapter());
                    }
                });

                stream.data(new BytesDataInfo(data, true), new Callback.Adapter());

                return null;
            }
        }));
        proxyConnector.addConnectionFactory(proxyConnector.getConnectionFactory("spdy/" + version));

        final CountDownLatch pushSynLatch = new CountDownLatch(1);
        final CountDownLatch pushDataLatch = new CountDownLatch(1);
        Session client = factory.newSPDYClient(version).connect(proxyAddress, null);

        Fields headers = new Fields();
        headers.put(HTTPSPDYHeader.HOST.name(version), "localhost:" + proxyAddress.getPort());
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        client.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
            {
                pushSynLatch.countDown();
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        dataInfo.consume(dataInfo.length());
                        if (dataInfo.isClose())
                            pushDataLatch.countDown();
                    }
                };
            }

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    dataLatch.countDown();
            }
        });

        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(pushSynLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(pushDataLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));

        client.goAway(new GoAwayInfo(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSYNThenSPDYNestedPushIsReceived() throws Exception
    {
        final byte[] data = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);
        InetSocketAddress proxyAddress = startProxy(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Fields responseHeaders = new Fields();
                responseHeaders.put(HTTPSPDYHeader.VERSION.name(version), "HTTP/1.1");
                responseHeaders.put(HTTPSPDYHeader.STATUS.name(version), "200 OK");
                stream.reply(new ReplyInfo(responseHeaders, false), new Callback.Adapter());

                final Fields pushHeaders = new Fields();
                pushHeaders.put(HTTPSPDYHeader.URI.name(version), "/push");
                stream.push(new PushInfo(5, TimeUnit.SECONDS, pushHeaders, false), new Promise.Adapter<Stream>()
                {
                    @Override
                    public void succeeded(Stream pushStream)
                    {
                        pushHeaders.put(HTTPSPDYHeader.URI.name(version), "/nestedpush");
                        pushStream.push(new PushInfo(5, TimeUnit.SECONDS, pushHeaders, false), new Adapter<Stream>()
                        {
                            @Override
                            public void succeeded(Stream pushStream)
                            {
                                pushHeaders.put(HTTPSPDYHeader.URI.name(version), "/anothernestedpush");
                                pushStream.push(new PushInfo(5, TimeUnit.SECONDS, pushHeaders, false), new Adapter<Stream>()
                                {
                                    @Override
                                    public void succeeded(Stream pushStream)
                                    {
                                        pushStream.data(new BytesDataInfo(data, true), new Callback.Adapter());
                                    }
                                });
                                pushStream.data(new BytesDataInfo(data, true), new Callback.Adapter());
                            }
                        });
                        pushStream.data(new BytesDataInfo(data, true), new Callback.Adapter());
                    }
                });

                stream.data(new BytesDataInfo(data, true), new Callback.Adapter());

                return null;
            }
        }));
        proxyConnector.addConnectionFactory(proxyConnector.getConnectionFactory("spdy/" + version));

        final CountDownLatch pushSynLatch = new CountDownLatch(3);
        final CountDownLatch pushDataLatch = new CountDownLatch(3);
        Session client = factory.newSPDYClient(version).connect(proxyAddress, null);

        Fields headers = new Fields();
        headers.put(HTTPSPDYHeader.HOST.name(version), "localhost:" + proxyAddress.getPort());
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        client.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            // onPush for 1st push stream
            @Override
            public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
            {
                pushSynLatch.countDown();
                return new StreamFrameListener.Adapter()
                {
                    // onPush for 2nd nested push stream
                    @Override
                    public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
                    {
                        pushSynLatch.countDown();
                        return new Adapter()
                        {
                            // onPush for 3rd nested push stream
                            @Override
                            public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
                            {
                                pushSynLatch.countDown();
                                return new Adapter()
                                {
                                    @Override
                                    public void onData(Stream stream, DataInfo dataInfo)
                                    {
                                        dataInfo.consume(dataInfo.length());
                                        if (dataInfo.isClose())
                                            pushDataLatch.countDown();
                                    }
                                };
                            }

                            @Override
                            public void onData(Stream stream, DataInfo dataInfo)
                            {
                                dataInfo.consume(dataInfo.length());
                                if (dataInfo.isClose())
                                    pushDataLatch.countDown();
                            }
                        };
                    }

                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        dataInfo.consume(dataInfo.length());
                        if (dataInfo.isClose())
                            pushDataLatch.countDown();
                    }
                };
            }

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    dataLatch.countDown();
            }
        });

        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(pushSynLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(pushDataLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));

        client.goAway(new GoAwayInfo(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPING() throws Exception
    {
        // PING is per hop, and it does not carry the information to which server to ping to
        // We just verify that it works

        InetSocketAddress proxyAddress = startProxy(startServer(new ServerSessionFrameListener.Adapter()));
        proxyConnector.addConnectionFactory(proxyConnector.getConnectionFactory("spdy/" + version));

        final CountDownLatch pingLatch = new CountDownLatch(1);
        Session client = factory.newSPDYClient(version).connect(proxyAddress, new SessionFrameListener.Adapter()
        {
            @Override
            public void onPing(Session session, PingResultInfo pingInfo)
            {
                pingLatch.countDown();
            }
        });

        client.ping(new PingInfo(5, TimeUnit.SECONDS));

        Assert.assertTrue(pingLatch.await(5, TimeUnit.SECONDS));

        client.goAway(new GoAwayInfo(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSYNThenReset() throws Exception
    {
        InetSocketAddress proxyAddress = startProxy(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertTrue(synInfo.isClose());
                Fields requestHeaders = synInfo.getHeaders();
                Assert.assertNotNull(requestHeaders.get("via"));

                stream.getSession().rst(new RstInfo(stream.getId(), StreamStatus.REFUSED_STREAM), new Callback.Adapter());

                return null;
            }
        }));
        proxyConnector.addConnectionFactory(proxyConnector.getConnectionFactory("spdy/" + version));

        final CountDownLatch resetLatch = new CountDownLatch(1);
        Session client = factory.newSPDYClient(version).connect(proxyAddress, new SessionFrameListener.Adapter()
        {
            @Override
            public void onRst(Session session, RstInfo rstInfo)
            {
                resetLatch.countDown();
            }
        });

        Fields headers = new Fields();
        headers.put(HTTPSPDYHeader.HOST.name(version), "localhost:" + proxyAddress.getPort());
        client.syn(new SynInfo(headers, true), null);

        Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));

        client.goAway(new GoAwayInfo(5, TimeUnit.SECONDS));
    }
}
