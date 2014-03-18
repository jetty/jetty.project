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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.spdy.api.BytesDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.GoAwayResultInfo;
import org.eclipse.jetty.spdy.api.PushInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.client.SPDYClient;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;
import org.eclipse.jetty.spdy.server.SPDYServerConnectionFactory;
import org.eclipse.jetty.spdy.server.SPDYServerConnector;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

@RunWith(Parameterized.class)
public class ProxyHTTPToSPDYTest
{
    @Parameterized.Parameters
    public static Collection<Short[]> parameters()
    {
        return Arrays.asList(new Short[]{SPDY.V2}, new Short[]{SPDY.V3});
    }

    @Rule
    public final TestTracker tracker = new TestTracker();
    private final short version;
    private HttpClient httpClient;
    private HttpClient httpClient2;
    private SPDYClient.Factory factory;
    private Server server;
    private Server proxy;
    private ServerConnector proxyConnector;

    public ProxyHTTPToSPDYTest(short version)
    {
        this.version = version;
    }

    protected InetSocketAddress startServer(ServerSessionFrameListener listener) throws Exception
    {
        server = new Server();
        SPDYServerConnector serverConnector = new SPDYServerConnector(server, listener);
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
        proxyConnector = new HTTPSPDYProxyServerConnector(proxy, proxyEngineSelector);
        proxyConnector.setPort(9999);
        proxy.addConnector(proxyConnector);
        proxy.start();
        return new InetSocketAddress("localhost", proxyConnector.getLocalPort());
    }

    @Before
    public void init() throws Exception
    {
        factory = new SPDYClient.Factory();
        factory.start();
        httpClient = new HttpClient();
        httpClient.start();
        httpClient2 = new HttpClient();
        httpClient2.start();
    }

    @After
    public void destroy() throws Exception
    {
        httpClient.stop();
        httpClient2.stop();
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
    public void testClosingClientDoesNotCloseServer() throws Exception
    {
        final CountDownLatch closeLatch = new CountDownLatch(1);
        InetSocketAddress proxyAddress = startProxy(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Fields responseHeaders = new Fields();
                responseHeaders.put(HTTPSPDYHeader.VERSION.name(version), "HTTP/1.1");
                responseHeaders.put(HTTPSPDYHeader.STATUS.name(version), "200 OK");
                stream.reply(new ReplyInfo(responseHeaders, true), new Callback.Adapter());
                return null;
            }

            @Override
            public void onGoAway(Session session, GoAwayResultInfo goAwayInfo)
            {
                closeLatch.countDown();
            }
        }));

        Request request = httpClient.newRequest("localhost", proxyAddress.getPort()).method("GET");
        request.header("Connection", "close");
        ContentResponse response = request.send();

        assertThat("response status is 200 OK", response.getStatus(), is(200));

        // Must not close, other clients may still be connected
        Assert.assertFalse(closeLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testGETThenNoContentFromTwoClients() throws Exception
    {
        InetSocketAddress proxyAddress = startProxy(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertTrue(synInfo.isClose());
                Fields requestHeaders = synInfo.getHeaders();
                Assert.assertNotNull(requestHeaders.get("via"));

                Fields responseHeaders = new Fields();
                responseHeaders.put(HTTPSPDYHeader.VERSION.name(version), "HTTP/1.1");
                responseHeaders.put(HTTPSPDYHeader.STATUS.name(version), "200 OK");
                ReplyInfo replyInfo = new ReplyInfo(responseHeaders, true);
                stream.reply(replyInfo, new Callback.Adapter());
                return null;
            }
        }));

        ContentResponse response = httpClient.newRequest("localhost", proxyAddress.getPort()).method(HttpMethod.GET)
                .send();
        assertThat("response code is 200 OK", response.getStatus(), is(200));

        // Perform another request with another client
        ContentResponse response2 = httpClient2.newRequest("localhost", proxyAddress.getPort()).method(HttpMethod.GET)
                .send();
        assertThat("response2 code is 200 OK", response2.getStatus(), is(200));
    }

    @Test
    public void testHEADRequest() throws Exception
    {
        InetSocketAddress proxyAddress = startProxy(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertTrue(synInfo.isClose());
                Fields requestHeaders = synInfo.getHeaders();
                Assert.assertNotNull(requestHeaders.get("via"));

                Fields responseHeaders = new Fields();
                responseHeaders.put(HTTPSPDYHeader.VERSION.name(version), "HTTP/1.1");
                responseHeaders.put(HTTPSPDYHeader.STATUS.name(version), "200 OK");
                ReplyInfo replyInfo = new ReplyInfo(responseHeaders, true);
                stream.reply(replyInfo, new Callback.Adapter());

                return null;
            }
        }));
        ContentResponse response = httpClient.newRequest("localhost", proxyAddress.getPort()).method(HttpMethod.HEAD).send();
        assertThat("response code is 200 OK", response.getStatus(), is(200));
    }

    @Test
    public void testGETThenSmallResponseContent() throws Exception
    {
        final byte[] data = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);
        InetSocketAddress proxyAddress = startProxy(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertTrue(synInfo.isClose());
                Fields requestHeaders = synInfo.getHeaders();
                Assert.assertNotNull(requestHeaders.get("via"));

                Fields responseHeaders = new Fields();
                responseHeaders.put(HTTPSPDYHeader.VERSION.name(version), "HTTP/1.1");
                responseHeaders.put(HTTPSPDYHeader.STATUS.name(version), "200 OK");
                responseHeaders.put("content-length", String.valueOf(data.length));

                ReplyInfo replyInfo = new ReplyInfo(responseHeaders, false);
                stream.reply(replyInfo, new Callback.Adapter());
                stream.data(new BytesDataInfo(data, true), new Callback.Adapter());

                return null;
            }
        }));

        ContentResponse response = httpClient.newRequest("localhost", proxyAddress.getPort()).method(HttpMethod.GET)
                .send();
        assertThat("response code is 200 OK", response.getStatus(), is(200));
        assertThat(Arrays.equals(response.getContent(), data), is(true));

        // Perform another request so that we are sure we reset the states of parsers and generators
        ContentResponse response2 = httpClient.newRequest("localhost", proxyAddress.getPort()).method(HttpMethod.GET)
                .send();
        assertThat("response2 code is 200 OK", response2.getStatus(), is(200));
        assertThat(Arrays.equals(response2.getContent(), data), is(true));
    }

    @Test
    public void testPOSTWithSmallRequestContentThenRedirect() throws Exception
    {
        final String data = "0123456789ABCDEF";
        InetSocketAddress proxyAddress = startProxy(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        dataInfo.consume(dataInfo.length());
                        if (dataInfo.isClose())
                        {
                            Fields headers = new Fields();
                            headers.put(HTTPSPDYHeader.VERSION.name(version), "HTTP/1.1");
                            headers.put(HTTPSPDYHeader.STATUS.name(version), "303 See Other");
                            headers.put(HttpHeader.LOCATION.asString(),"http://other.location");
                            stream.reply(new ReplyInfo(headers, true), new Callback.Adapter());
                        }
                    }
                };
            }
        }));

        ContentResponse response = httpClient.newRequest("localhost", proxyAddress.getPort()).method(HttpMethod.POST).content(new
                StringContentProvider(data)).followRedirects(false).send();
        assertThat("response code is 303", response.getStatus(), is(303));

        // Perform another request so that we are sure we reset the states of parsers and generators
        ContentResponse response2 = httpClient.newRequest("localhost", proxyAddress.getPort()).method(HttpMethod
                .POST).content(new StringContentProvider(data)).followRedirects(false).send();
        assertThat("response2 code is 303", response2.getStatus(), is(303));
    }

    @Test
    public void testPOSTWithSmallRequestContentThenSmallResponseContent() throws Exception
    {
        String dataString = "0123456789ABCDEF";
        final byte[] data = dataString.getBytes(StandardCharsets.UTF_8);
        InetSocketAddress proxyAddress = startProxy(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        dataInfo.consume(dataInfo.length());
                        if (dataInfo.isClose())
                        {
                            Fields responseHeaders = new Fields();
                            responseHeaders.put(HTTPSPDYHeader.VERSION.name(version), "HTTP/1.1");
                            responseHeaders.put(HTTPSPDYHeader.STATUS.name(version), "200 OK");
                            responseHeaders.put("content-length", String.valueOf(data.length));
                            ReplyInfo replyInfo = new ReplyInfo(responseHeaders, false);
                            stream.reply(replyInfo, new Callback.Adapter());
                            stream.data(new BytesDataInfo(data, true), new Callback.Adapter());
                        }
                    }
                };
            }
        }));

        ContentResponse response = httpClient.POST("http://localhost:" + proxyAddress.getPort() + "/").content(new
                StringContentProvider(dataString)).send();
        assertThat("response status is 200 OK", response.getStatus(), is(200));
        assertThat("response content matches expected dataString", response.getContentAsString(), is(dataString));

        // Perform another request so that we are sure we reset the states of parsers and generators
        response = httpClient.POST("http://localhost:" + proxyAddress.getPort() + "/").content(new
                StringContentProvider(dataString)).send();
        assertThat("response status is 200 OK", response.getStatus(), is(200));
        assertThat("response content matches expected dataString", response.getContentAsString(), is(dataString));
    }

    @Test
    public void testGETThenSPDYPushIsIgnored() throws Exception
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

                stream.reply(new ReplyInfo(responseHeaders, true), new Callback.Adapter());
                return null;
            }
        }));

        ContentResponse response = httpClient.newRequest("localhost", proxyAddress.getPort()).method(HttpMethod.GET).send();
        assertThat("response code is 200 OK", response.getStatus(), is(200));
    }

    @Test
    public void testGETThenReset() throws Exception
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

        ContentResponse response = httpClient.newRequest("localhost", proxyAddress.getPort()).method(HttpMethod.GET).send();
        assertThat("response code is 502 Gateway Error", response.getStatus(), is(502));
    }
}
