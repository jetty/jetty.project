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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.GoAwayResultInfo;
import org.eclipse.jetty.spdy.api.PingInfo;
import org.eclipse.jetty.spdy.api.PingResultInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.client.SPDYClient;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;
import org.eclipse.jetty.spdy.server.http.SPDYTestUtils;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

@RunWith(value = Parameterized.class)
public abstract class ProxySPDYToHTTPTest
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

    public ProxySPDYToHTTPTest(short version)
    {
        this.version = version;
    }

    protected InetSocketAddress startServer(Handler handler) throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.setHandler(handler);
        server.addConnector(connector);
        server.start();
        return new InetSocketAddress("localhost", connector.getLocalPort());
    }

    protected InetSocketAddress startProxy(InetSocketAddress address, long proxyConnectorTimeout, long proxyEngineTimeout) throws Exception
    {
        proxy = new Server();
        ProxyEngineSelector proxyEngineSelector = new ProxyEngineSelector();
        HttpClient httpClient = new HttpClient();
        httpClient.start();
        httpClient.setIdleTimeout(proxyEngineTimeout);
        HTTPProxyEngine httpProxyEngine = new HTTPProxyEngine(httpClient);
        proxyEngineSelector.putProxyEngine("http/1.1", httpProxyEngine);
        proxyEngineSelector.putProxyServerInfo("localhost", new ProxyEngineSelector.ProxyServerInfo("http/1.1", address.getHostName(), address.getPort()));
        proxyConnector = new HTTPSPDYProxyServerConnector(proxy, sslContextFactory, proxyEngineSelector);
        proxyConnector.setPort(0);
        proxyConnector.setIdleTimeout(proxyConnectorTimeout);
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
        ((StdErrLog)Log.getLogger(HttpChannel.class)).setHideStacks(false);
    }

    @Test
    public void testSYNThenREPLY() throws Exception
    {
        final String header = "foo";

        InetSocketAddress proxyAddress = startProxy(startServer(new TestServerHandler(header, null)), 30000, 30000);

        Session client = factory.newSPDYClient(version).connect(proxyAddress, null);

        final CountDownLatch replyLatch = new CountDownLatch(1);

        Fields headers = SPDYTestUtils.createHeaders("localhost", proxyAddress.getPort(), version, "GET", "/");
        headers.put(header, "bar");
        headers.put("connection", "close");

        client.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Fields headers = replyInfo.getHeaders();
                assertThat("Version header is set", headers.get(HTTPSPDYHeader.VERSION.name(version)), is(notNullValue()));
                assertThat("Custom set header foo is set on response", headers.get(header), is(notNullValue()));
                assertThat("HOP headers like connection are removed before forwarding",
                        headers.get("connection"), is(nullValue()));
                replyLatch.countDown();
            }
        });

        assertThat("Reply is send to SPDY client", replyLatch.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testSYNThenREPLYAndDATA() throws Exception
    {
        final byte[] data = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);
        final String header = "foo";

        InetSocketAddress proxyAddress = startProxy(startServer(new TestServerHandler(header, data)), 30000, 30000);

        Session client = factory.newSPDYClient(version).connect(proxyAddress, null);

        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);

        Fields headers = SPDYTestUtils.createHeaders("localhost", proxyAddress.getPort(), version, "GET", "/");
        headers.put(header, "bar");
        headers.put("connection", "close");

        client.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final ByteArrayOutputStream result = new ByteArrayOutputStream();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Fields headers = replyInfo.getHeaders();
                assertThat("Trailer header has been filtered by proxy", headers.get("trailer"),
                        is(nullValue()));
                assertThat("custom header exists in response", headers.get(header), is(notNullValue()));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                result.write(dataInfo.asBytes(true), 0, dataInfo.length());
                if (dataInfo.isClose())
                {
                    assertThat("received data matches send data", result.toByteArray(), is(data));
                    dataLatch.countDown();
                }
            }
        });

        assertThat("reply has been received", replyLatch.await(5, TimeUnit.SECONDS), is(true));
        assertThat("data has been received", dataLatch.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testHttpServerCommitsResponseTwice() throws Exception
    {
        final long timeout = 1000;

        InetSocketAddress proxyAddress = startProxy(startServer(new DefaultHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.addHeader("some response", "header");
                response.flushBuffer();
                try
                {
                    Thread.sleep(timeout * 2);
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }

            }
        }), 30000, timeout);

        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch resetLatch = new CountDownLatch(1);

        Session client = factory.newSPDYClient(version).connect(proxyAddress, new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onRst(Session session, RstInfo rstInfo)
            {
                resetLatch.countDown();
            }
        });

        Fields headers = SPDYTestUtils.createHeaders("localhost", proxyAddress.getPort(), version, "GET", "/");
        headers.put("connection", "close");

        client.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                replyLatch.countDown();
            }
        });

        assertThat("reply has been received", replyLatch.await(5, TimeUnit.SECONDS), is(true));
        assertThat("stream is reset", resetLatch.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testHttpServerSendsRedirect() throws Exception
    {
        InetSocketAddress proxyAddress = startProxy(startServer(new DefaultHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setStatus(HttpServletResponse.SC_FOUND);
                response.setHeader("Location", "http://doesnot.exist");
            }
        }), 30000, 30000);

        final CountDownLatch replyLatch = new CountDownLatch(1);

        Session client = factory.newSPDYClient(version).connect(proxyAddress, null);
        Fields headers = SPDYTestUtils.createHeaders("localhost", proxyAddress.getPort(), version, "GET", "/");

        client.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                assertThat("Status code is 302", replyInfo.getHeaders().get(HTTPSPDYHeader.STATUS.name(version)).getValue(),
                        is("302"));
                assertThat("Location header has been received", replyInfo.getHeaders().get("Location"), is(notNullValue()));
                replyLatch.countDown();
            }
        });

        assertThat("reply has been received", replyLatch.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testSYNWithRequestContentThenREPLYAndDATA() throws Exception
    {
        final String data = "0123456789ABCDEF";
        final String header = "foo";

        InetSocketAddress proxyAddress = startProxy(startServer(new TestServerHandler(header, null)), 30000, 30000);

        Session client = factory.newSPDYClient(version).connect(proxyAddress, null);

        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);

        Fields headers = SPDYTestUtils.createHeaders("localhost", proxyAddress.getPort(), version, "POST", "/");
        headers.put(header, "bar");
        headers.put("connection", "close");

        Stream stream = client.syn(new SynInfo(headers, false), new StreamFrameListener.Adapter()
        {
            private final ByteArrayOutputStream result = new ByteArrayOutputStream();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Fields headers = replyInfo.getHeaders();
                assertThat("custom header exists in response", headers.get(header), is(notNullValue()));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                result.write(dataInfo.asBytes(true), 0, dataInfo.length());
                if (dataInfo.isClose())
                {
                    assertThat("received data matches send data", data, is(result.toString()));
                    dataLatch.countDown();
                }
            }
        });

        stream.data(new StringDataInfo(data, true), new Callback.Adapter());

        assertThat("reply has been received", replyLatch.await(5, TimeUnit.SECONDS), is(true));
        assertThat("data has been received", dataLatch.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testSYNWithSplitRequestContentThenREPLYAndDATA() throws Exception
    {
        final String data = "0123456789ABCDEF";
        final String data2 = "ABCDEF";
        final String header = "foo";

        InetSocketAddress proxyAddress = startProxy(startServer(new TestServerHandler(header, null)), 30000, 30000);

        Session client = factory.newSPDYClient(version).connect(proxyAddress, null);

        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);

        Fields headers = SPDYTestUtils.createHeaders("localhost", proxyAddress.getPort(), version, "POST", "/");
        headers.put(header, "bar");
        headers.put("connection", "close");

        Stream stream = client.syn(new SynInfo(headers, false), new StreamFrameListener.Adapter()
        {
            private final ByteArrayOutputStream result = new ByteArrayOutputStream();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Fields headers = replyInfo.getHeaders();
                assertThat("custom header exists in response", headers.get(header), is(notNullValue()));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                result.write(dataInfo.asBytes(true), 0, dataInfo.length());
                if (dataInfo.isClose())
                {
                    assertThat("received data matches send data", result.toString(), is(data + data2));
                    dataLatch.countDown();
                }
            }
        });

        stream.data(new StringDataInfo(data, false), new Callback.Adapter());
        stream.data(new StringDataInfo(data2, true), new Callback.Adapter());

        assertThat("reply has been received", replyLatch.await(5, TimeUnit.SECONDS), is(true));
        assertThat("data has been received", dataLatch.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testClientTimeout() throws Exception
    {
        long timeout = 1000;

        InetSocketAddress proxyAddress = startProxy(startServer(new TestServerHandler(null, null)), timeout, 30000);

        final CountDownLatch goAwayLatch = new CountDownLatch(1);

        Session client = factory.newSPDYClient(version).connect(proxyAddress, new SessionFrameListener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayResultInfo goAwayReceivedInfo)
            {
                goAwayLatch.countDown();
            }
        });

        Fields headers = SPDYTestUtils.createHeaders("localhost", proxyAddress.getPort(), version, "POST", "/");
        ((StdErrLog)Log.getLogger(HttpChannel.class)).setHideStacks(true);
        client.syn(new SynInfo(headers, false), null);
        assertThat("goAway has been received by proxy", goAwayLatch.await(2 * timeout, TimeUnit.MILLISECONDS),
                is(true));
    }

    @Test
    public void testServerTimeout() throws Exception
    {
        final int timeout = 1000;
        final String header = "foo";

        InetSocketAddress proxyAddress = startProxy(startServer(new DefaultHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    Thread.sleep(2 * timeout);
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            }
        }), 30000, timeout);

        Session client = factory.newSPDYClient(version).connect(proxyAddress, null);

        final CountDownLatch replyLatch = new CountDownLatch(1);

        Fields headers = SPDYTestUtils.createHeaders("localhost", proxyAddress.getPort(), version, "POST", "/");
        headers.put(header, "bar");
        headers.put("connection", "close");

        client.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Fields headers = replyInfo.getHeaders();
                assertThat("status is 504", headers.get(HTTPSPDYHeader.STATUS.name(version)).getValue(), is("504"));
                replyLatch.countDown();
            }

        });

        assertThat("reply has been received", replyLatch.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testPING() throws Exception
    {
        // PING is per hop, and it does not carry the information to which server to ping to
        // We just verify that it works

        InetSocketAddress proxyAddress = startProxy(startServer(null), 30000, 30000);
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
            BufferedReader bufferedReader = request.getReader();
            int read;
            while ((read = bufferedReader.read()) != -1)
                response.getOutputStream().write(read);

            // add some hop header to be removed on the proxy
            response.addHeader("Trailer", "bla");
            if (responseHeader != null)
                response.addHeader(responseHeader, "bar");
            if (responseData != null)
                response.getOutputStream().write(responseData);
        }
    }
}
