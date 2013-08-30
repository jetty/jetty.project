//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
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
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

@RunWith(Parameterized.class)
public class ProxyHTTPToSPDYTest
{
    private static final Logger LOG = Log.getLogger(ProxyHTTPToSPDYTest.class);
    @Rule
    public final TestWatcher testName = new TestWatcher()
    {

        @Override
        public void starting(Description description)
        {
            super.starting(description);
            System.err.printf("Running %s.%s()%n",
                    description.getClassName(),
                    description.getMethodName());
        }
    };

    private final short version;
    private HttpClient httpClient;

    @Parameterized.Parameters
    public static Collection<Short[]> parameters()
    {
        return Arrays.asList(new Short[]{SPDY.V2}, new Short[]{SPDY.V3});
    }

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

        Socket client1 = new Socket();
        client1.connect(proxyAddress);
        OutputStream output1 = client1.getOutputStream();

        String request = "" +
                "GET / HTTP/1.1\r\n" +
                "Host: localhost:" + proxyAddress.getPort() + "\r\n" +
                "\r\n";
        output1.write(request.getBytes("UTF-8"));
        output1.flush();

        InputStream input1 = client1.getInputStream();
        BufferedReader reader1 = new BufferedReader(new InputStreamReader(input1, "UTF-8"));
        String line = reader1.readLine();
        Assert.assertTrue(line.contains(" 200"));
        while (line.length() > 0)
            line = reader1.readLine();
        Assert.assertFalse(reader1.ready());

        // Perform another request with another client
        Socket client2 = new Socket();
        client2.connect(proxyAddress);
        OutputStream output2 = client2.getOutputStream();

        output2.write(request.getBytes("UTF-8"));
        output2.flush();

        InputStream input2 = client2.getInputStream();
        BufferedReader reader2 = new BufferedReader(new InputStreamReader(input2, "UTF-8"));
        line = reader2.readLine();
        Assert.assertTrue(line.contains(" 200"));
        while (line.length() > 0)
            line = reader2.readLine();
        Assert.assertFalse(reader2.ready());

        client1.close();
        client2.close();
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
        Socket client = new Socket();
        client.connect(proxyAddress);
        OutputStream output = client.getOutputStream();

        String request = "" +
                "HEAD / HTTP/1.1\r\n" +
                "Host: localhost:" + proxyAddress.getPort() + "\r\n" +
                "\r\n";
        output.write(request.getBytes("UTF-8"));
        output.flush();

        InputStream input = client.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
        String line = reader.readLine();
        Assert.assertTrue(line.contains(" 200 "));

        client.close();
    }

    @Test
    public void testGETThenSmallResponseContent() throws Exception
    {
        final byte[] data = "0123456789ABCDEF".getBytes("UTF-8");
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

        Socket client = new Socket();
        client.connect(proxyAddress);
        OutputStream output = client.getOutputStream();

        String request = "" +
                "GET / HTTP/1.1\r\n" +
                "Host: localhost:" + proxyAddress.getPort() + "\r\n" +
                "\r\n";
        output.write(request.getBytes("UTF-8"));
        output.flush();

        InputStream input = client.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
        String line = reader.readLine();
        Assert.assertTrue(line.contains(" 200 "));
        while (line.length() > 0)
            line = reader.readLine();
        for (byte datum : data)
            Assert.assertEquals(datum, reader.read());

        // Perform another request so that we are sure we reset the states of parsers and generators
        output.write(request.getBytes("UTF-8"));
        output.flush();

        line = reader.readLine();
        Assert.assertTrue(line.contains(" 200"));
        while (line.length() > 0)
            line = reader.readLine();
        for (byte datum : data)
            Assert.assertEquals(datum, reader.read());
        Assert.assertFalse(reader.ready());

        client.close();
    }

    @Test
    public void testPOSTWithSmallRequestContentThenRedirect() throws Exception
    {
        final byte[] data = "0123456789ABCDEF".getBytes("UTF-8");
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
                            stream.reply(new ReplyInfo(headers, true), new Callback.Adapter());
                        }
                    }
                };
            }
        }));

        Socket client = new Socket();
        client.connect(proxyAddress);
        OutputStream output = client.getOutputStream();

        String request = "" +
                "POST / HTTP/1.1\r\n" +
                "Host: localhost:" + proxyAddress.getPort() + "\r\n" +
                "Content-Length: " + data.length + "\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "\r\n";
        output.write(request.getBytes("UTF-8"));
        output.write(data);
        output.flush();

        InputStream input = client.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
        String line = reader.readLine();
        Assert.assertTrue(line.contains(" 303"));
        while (line.length() > 0)
            line = reader.readLine();
        Assert.assertFalse(reader.ready());

        // Perform another request so that we are sure we reset the states of parsers and generators
        output.write(request.getBytes("UTF-8"));
        output.write(data);
        output.flush();

        line = reader.readLine();
        Assert.assertTrue(line.contains(" 303"));
        while (line.length() > 0)
            line = reader.readLine();
        Assert.assertFalse(reader.ready());

        client.close();
    }

    @Test
    public void testPOSTWithSmallRequestContentThenSmallResponseContent() throws Exception
    {
        String dataString = "0123456789ABCDEF";
        final byte[] data = dataString.getBytes("UTF-8");
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
        final byte[] data = "0123456789ABCDEF".getBytes("UTF-8");
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

        Socket client = new Socket();
        client.connect(proxyAddress);
        OutputStream output = client.getOutputStream();

        String request = "" +
                "GET / HTTP/1.1\r\n" +
                "Host: localhost:" + proxyAddress.getPort() + "\r\n" +
                "\r\n";
        output.write(request.getBytes("UTF-8"));
        output.flush();

        client.setSoTimeout(1000);
        InputStream input = client.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
        String line = reader.readLine();
        Assert.assertTrue(line.contains(" 200"));
        while (line.length() > 0)
            line = reader.readLine();
        Assert.assertFalse(reader.ready());

        client.close();
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

        Socket client = new Socket();
        client.connect(proxyAddress);
        OutputStream output = client.getOutputStream();

        String request = "" +
                "GET / HTTP/1.1\r\n" +
                "Host: localhost:" + proxyAddress.getPort() + "\r\n" +
                "\r\n";
        output.write(request.getBytes("UTF-8"));
        output.flush();

        InputStream input = client.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
        Assert.assertNull(reader.readLine());

        client.close();
    }
}
