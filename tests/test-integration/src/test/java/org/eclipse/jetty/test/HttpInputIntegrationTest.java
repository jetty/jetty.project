//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.net.ssl.SSLSocket;
import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannelState;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.LocalConnector.LocalEndPoint;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class HttpInputIntegrationTest
{
    enum Mode
    {
        BLOCKING, ASYNC_DISPATCHED, ASYNC_OTHER_DISPATCHED, ASYNC_OTHER_WAIT
    }

    public static final String EOF = "__EOF__";
    public static final String DELAY = "__DELAY__";
    public static final String ABORT = "__ABORT__";

    private static Server __server;
    private static HttpConfiguration __config;
    private static HttpConfiguration __sslConfig;
    private static SslContextFactory __sslContextFactory;

    @BeforeAll
    public static void beforeClass() throws Exception
    {
        __config = new HttpConfiguration();

        __server = new Server();
        LocalConnector local = new LocalConnector(__server, new HttpConnectionFactory(__config));
        local.setIdleTimeout(4000);
        __server.addConnector(local);

        ServerConnector http = new ServerConnector(__server, new HttpConnectionFactory(__config), new HTTP2CServerConnectionFactory(__config));
        http.setIdleTimeout(4000);
        __server.addConnector(http);

        // SSL Context Factory for HTTPS and HTTP/2
        String jettyDistro = System.getProperty("jetty.distro", "../../jetty-distribution/target/distribution");
        __sslContextFactory = new SslContextFactory.Server();
        __sslContextFactory.setKeyStorePath(jettyDistro + "/../../../jetty-server/src/test/config/etc/keystore");
        __sslContextFactory.setKeyStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        __sslContextFactory.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");

        // HTTPS Configuration
        __sslConfig = new HttpConfiguration(__config);
        __sslConfig.addCustomizer(new SecureRequestCustomizer());

        // HTTP/1 Connection Factory
        HttpConnectionFactory h1 = new HttpConnectionFactory(__sslConfig);
        /* TODO
        // HTTP/2 Connection Factory
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(__sslConfig);
        
        NegotiatingServerConnectionFactory.checkProtocolNegotiationAvailable();
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1.getProtocol());
        */

        // SSL Connection Factory
        SslConnectionFactory ssl = new SslConnectionFactory(__sslContextFactory, h1.getProtocol() /*TODO alpn.getProtocol()*/);

        // HTTP/2 Connector
        ServerConnector http2 = new ServerConnector(__server, ssl, /*TODO alpn,h2,*/ h1);
        http2.setIdleTimeout(4000);
        __server.addConnector(http2);

        ServletContextHandler context = new ServletContextHandler(__server, "/ctx");
        ServletHolder holder = new ServletHolder(new TestServlet());
        holder.setAsyncSupported(true);
        context.addServlet(holder, "/*");

        __server.start();
    }

    @AfterAll
    public static void afterClass() throws Exception
    {
        __server.stop();
    }

    interface TestClient
    {

        /**
         * @param uri The URI to test, typically /ctx/test?mode=THE_MODE
         * @param delayMs the delay in MS to use.
         * @param delayInFrame If null, send the request with no delays, if FALSE then send with delays between frames, if TRUE send with delays within frames
         * @param contentLength The content length header to send.
         * @param content The content to send, with each string to be converted to a chunk or a frame
         * @return The response received in HTTP/1 format
         */
        String send(String uri, int delayMs, Boolean delayInFrame, int contentLength, List<String> content) throws Exception;
    }

    public static Stream<Arguments> scenarios()
    {
        List<Scenario> tests = new ArrayList<>();

        // TODO other client types!
        // test with the following clients/protocols:
        //   + Local
        //   + HTTP/1
        //   + SSL + HTTP/1
        //   + HTTP/2
        //   + SSL + HTTP/2
        //   + FASTCGI
        for (Class<? extends TestClient> client : new Class[]{LocalClient.class, H1Client.class, H1SClient.class})
        {

            // test async actions that are run:
            //   + By a thread in a container callback
            //   + By another thread while a container callback is active
            //   + By another thread while no container callback is active
            for (Mode mode : Mode.values())
            {

                // test servlet dispatch with:
                //   + Delayed dispatch on
                //   + Delayed dispatch off
                for (Boolean dispatch : new Boolean[]{false, true})
                {
                    // test send with 
                    //   + No delays between frames
                    //   + Delays between frames
                    //   + Delays within frames!
                    for (Boolean delayWithinFrame : new Boolean[]{null, false, true})
                    {
                        // test content 
                        // + unknown length + EOF
                        // + unknown length + content + EOF
                        // + unknown length + content + content + EOF

                        // + known length + EOF
                        // + known length + content + EOF
                        // + known length + content + content + EOF

                        tests.add(new Scenario(client, mode, dispatch, delayWithinFrame, 200, 0, -1));
                        tests.add(new Scenario(client, mode, dispatch, delayWithinFrame, 200, 8, -1, "content0"));
                        tests.add(new Scenario(client, mode, dispatch, delayWithinFrame, 200, 16, -1, "content0", "CONTENT1"));

                        tests.add(new Scenario(client, mode, dispatch, delayWithinFrame, 200, 0, 0));
                        tests.add(new Scenario(client, mode, dispatch, delayWithinFrame, 200, 8, 8, "content0"));
                        tests.add(new Scenario(client, mode, dispatch, delayWithinFrame, 200, 16, 16, "content0", "CONTENT1"));
                    }
                }
            }
        }
        return tests.stream().map(Arguments::of);
    }

    private static void runmode(Mode mode, final Request request, final Runnable test)
    {
        switch (mode)
        {
            case ASYNC_DISPATCHED:
            {
                test.run();
                break;
            }
            case ASYNC_OTHER_DISPATCHED:
            {
                final CountDownLatch latch = new CountDownLatch(1);
                new Thread()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            test.run();
                        }
                        finally
                        {
                            latch.countDown();
                        }
                    }
                }.start();
                // prevent caller returning until other thread complete
                try
                {
                    if (!latch.await(5, TimeUnit.SECONDS))
                        fail("latch expired");
                }
                catch (Exception e)
                {
                    fail(e);
                }
                break;
            }
            case ASYNC_OTHER_WAIT:
            {
                final CountDownLatch latch = new CountDownLatch(1);
                final HttpChannelState.State S = request.getHttpChannelState().getState();
                new Thread()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            if (!latch.await(5, TimeUnit.SECONDS))
                                fail("latch expired");

                            // Spin until state change
                            HttpChannelState.State s = request.getHttpChannelState().getState();
                            while (request.getHttpChannelState().getState() == S)
                            {
                                Thread.yield();
                                s = request.getHttpChannelState().getState();
                            }
                            test.run();
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                }.start();
                // ensure other thread running before trying to return
                latch.countDown();
                break;
            }

            default:
                throw new IllegalStateException();
        }
    }

    @ParameterizedTest(name = "[{index}] TEST {0}")
    @MethodSource("scenarios")
    public void testOne(Scenario scenario) throws Exception
    {
        TestClient client = scenario._client.getDeclaredConstructor().newInstance();
        String response = client.send("/ctx/test?mode=" + scenario._mode, 50, scenario._delay, scenario._length, scenario._send);

        int sum = 0;
        for (String s : scenario._send)
        {
            for (char c : s.toCharArray())
            {
                sum += c;
            }
        }

        assertTrue(response.startsWith("HTTP"));
        assertTrue(response.contains(" " + scenario._status + " "));
        assertTrue(response.contains("read=" + scenario._read));
        assertTrue(response.contains("sum=" + sum));
    }

    @ParameterizedTest(name = "[{index}] STRESS {0}")
    @MethodSource("scenarios")
    // JDK 11's SSLSocket is not reliable enough to run this test.
    @DisabledOnJre(JRE.JAVA_11)
    public void testStress(Scenario scenario) throws Exception
    {
        int sum = 0;
        for (String s : scenario._send)
        {
            for (char c : s.toCharArray())
            {
                sum += c;
            }
        }
        final int summation = sum;

        final int threads = 10;
        final int loops = 10;

        final AtomicInteger count = new AtomicInteger(0);
        Thread[] t = new Thread[threads];

        Runnable run = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    TestClient client = scenario._client.getDeclaredConstructor().newInstance();
                    for (int j = 0; j < loops; j++)
                    {
                        String response = client.send("/ctx/test?mode=" + scenario._mode, 10, scenario._delay, scenario._length, scenario._send);
                        assertTrue(response.startsWith("HTTP"));
                        assertTrue(response.contains(" " + scenario._status + " "));
                        assertTrue(response.contains("read=" + scenario._read));
                        assertTrue(response.contains("sum=" + summation));
                        count.incrementAndGet();
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        };

        for (int i = 0; i < threads; i++)
        {
            t[i] = new Thread(run);
            t[i].start();
        }
        for (int i = 0; i < threads; i++)
        {
            t[i].join();
        }

        assertEquals(count.get(), threads * loops);
    }

    public static class TestServlet extends HttpServlet
    {
        String expected = "content0CONTENT1";

        @Override
        protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException
        {
            final Mode mode = Mode.valueOf(req.getParameter("mode"));
            resp.setContentType("text/plain");

            if (mode == Mode.BLOCKING)
            {
                try
                {
                    String content = IO.toString(req.getInputStream());
                    resp.setStatus(200);
                    resp.setContentType("text/plain");
                    resp.getWriter().println("read=" + content.length());
                    int sum = 0;
                    for (char c : content.toCharArray())
                    {
                        sum += c;
                    }
                    resp.getWriter().println("sum=" + sum);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    resp.setStatus(500);
                    resp.getWriter().println("read=" + e);
                    resp.getWriter().println("sum=-1");
                }
            }
            else
            {
                // we are asynchronous
                final AsyncContext context = req.startAsync();
                context.setTimeout(10000);
                final ServletInputStream in = req.getInputStream();
                final Request request = Request.getBaseRequest(req);
                final AtomicInteger read = new AtomicInteger(0);
                final AtomicInteger sum = new AtomicInteger(0);

                runmode(mode, request, new Runnable()
                {
                    @Override
                    public void run()
                    {
                        in.setReadListener(new ReadListener()
                        {
                            @Override
                            public void onError(Throwable t)
                            {
                                t.printStackTrace();
                                try
                                {
                                    resp.sendError(500);
                                }
                                catch (IOException e)
                                {
                                    e.printStackTrace();
                                    throw new RuntimeException(e);
                                }
                                context.complete();
                            }

                            @Override
                            public void onDataAvailable() throws IOException
                            {
                                runmode(mode, request, new Runnable()
                                {
                                    @Override
                                    public void run()
                                    {
                                        while (in.isReady() && !in.isFinished())
                                        {
                                            try
                                            {
                                                int b = in.read();
                                                if (b < 0)
                                                    return;
                                                sum.addAndGet(b);
                                                int i = read.getAndIncrement();
                                                if (b != expected.charAt(i))
                                                {
                                                    System.err.printf("XXX '%c'!='%c' at %d%n", expected.charAt(i), (char)b, i);
                                                    System.err.println("    " + request.getHttpChannel());
                                                    System.err.println("    " + request.getHttpChannel().getHttpTransport());
                                                }
                                            }
                                            catch (IOException e)
                                            {
                                                onError(e);
                                            }
                                        }
                                    }
                                });
                            }

                            @Override
                            public void onAllDataRead() throws IOException
                            {
                                resp.setStatus(200);
                                resp.setContentType("text/plain");
                                resp.getWriter().println("read=" + read.get());
                                resp.getWriter().println("sum=" + sum.get());
                                context.complete();
                            }
                        });
                    }
                });
            }
        }
    }

    public static class LocalClient implements TestClient
    {
        StringBuilder flushed = new StringBuilder();

        @Override
        public String send(String uri, int delayMs, Boolean delayInFrame, int contentLength, List<String> content) throws Exception
        {
            LocalConnector connector = __server.getBean(LocalConnector.class);

            StringBuilder buffer = new StringBuilder();
            buffer.append("GET ").append(uri).append(" HTTP/1.1\r\n");
            buffer.append("Host: localhost\r\n");
            buffer.append("Connection: close\r\n");

            LocalEndPoint local = connector.executeRequest("");

            flush(local, buffer, delayMs, delayInFrame, true);

            boolean chunked = contentLength < 0;
            if (chunked)
                buffer.append("Transfer-Encoding: chunked\r\n");
            else
                buffer.append("Content-Length: ").append(contentLength).append("\r\n");

            if (contentLength > 0)
                buffer.append("Content-Type: text/plain\r\n");
            buffer.append("\r\n");

            flush(local, buffer, delayMs, delayInFrame, false);

            for (String c : content)
            {
                if (chunked)
                {
                    buffer.append(Integer.toHexString(c.length())).append("\r\n");
                    flush(local, buffer, delayMs, delayInFrame, true);
                }

                buffer.append(c.substring(0, 1));
                flush(local, buffer, delayMs, delayInFrame, true);
                buffer.append(c.substring(1));
                if (chunked)
                    buffer.append("\r\n");
                flush(local, buffer, delayMs, delayInFrame, false);
            }

            if (chunked)
            {
                buffer.append("0");
                flush(local, buffer, delayMs, delayInFrame, true);
                buffer.append("\r\n\r\n");
            }

            flush(local, buffer);
            local.waitUntilClosed();
            return local.takeOutputString();
        }

        private void flush(LocalEndPoint local, StringBuilder buffer, int delayMs, Boolean delayInFrame, boolean inFrame) throws Exception
        {
            // Flush now if we should delay
            if (delayInFrame != null && delayInFrame.equals(inFrame))
            {
                flush(local, buffer);
                Thread.sleep(delayMs);
            }
        }

        private void flush(final LocalEndPoint local, StringBuilder buffer) throws Exception
        {
            final String flush = buffer.toString();
            buffer.setLength(0);
            flushed.append(flush);
            local.addInputAndExecute(BufferUtil.toBuffer(flush));
        }
    }

    public static class H1Client implements TestClient
    {
        NetworkConnector _connector;

        public H1Client()
        {
            for (Connector c : __server.getConnectors())
            {
                if (c instanceof NetworkConnector && c.getDefaultConnectionFactory().getProtocol().equals(HttpVersion.HTTP_1_1.asString()))
                {
                    _connector = (NetworkConnector)c;
                    break;
                }
            }
        }

        @Override
        public String send(String uri, int delayMs, Boolean delayInFrame, int contentLength, List<String> content) throws Exception
        {
            int port = _connector.getLocalPort();

            try (Socket client = newSocket("localhost", port))
            {
                client.setSoTimeout(5000);
                client.setTcpNoDelay(true);
                OutputStream out = client.getOutputStream();

                StringBuilder buffer = new StringBuilder();
                buffer.append("GET ").append(uri).append(" HTTP/1.1\r\n");
                buffer.append("Host: localhost:").append(port).append("\r\n");
                buffer.append("Connection: close\r\n");

                flush(out, buffer, delayMs, delayInFrame, true);

                boolean chunked = contentLength < 0;
                if (chunked)
                    buffer.append("Transfer-Encoding: chunked\r\n");
                else
                    buffer.append("Content-Length: ").append(contentLength).append("\r\n");

                if (contentLength > 0)
                    buffer.append("Content-Type: text/plain\r\n");
                buffer.append("\r\n");

                flush(out, buffer, delayMs, delayInFrame, false);

                for (String c : content)
                {
                    if (chunked)
                    {
                        buffer.append(Integer.toHexString(c.length())).append("\r\n");
                        flush(out, buffer, delayMs, delayInFrame, true);
                    }

                    buffer.append(c.substring(0, 1));
                    flush(out, buffer, delayMs, delayInFrame, true);
                    buffer.append(c.substring(1));
                    flush(out, buffer, delayMs, delayInFrame, false);
                    if (chunked)
                        buffer.append("\r\n");
                }

                if (chunked)
                {
                    buffer.append("0");
                    flush(out, buffer, delayMs, delayInFrame, true);
                    buffer.append("\r\n\r\n");
                }

                flush(out, buffer);

                return IO.toString(client.getInputStream());
            }
        }

        private void flush(OutputStream out, StringBuilder buffer, int delayMs, Boolean delayInFrame, boolean inFrame) throws Exception
        {
            // Flush now if we should delay
            if (delayInFrame != null && delayInFrame.equals(inFrame))
            {
                flush(out, buffer);
                Thread.sleep(delayMs);
            }
        }

        private void flush(OutputStream out, StringBuilder buffer) throws Exception
        {
            String flush = buffer.toString();
            buffer.setLength(0);
            out.write(flush.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        public Socket newSocket(String host, int port) throws IOException
        {
            return new Socket(host, port);
        }
    }

    public static class H1SClient extends H1Client
    {
        public H1SClient()
        {
            for (Connector c : __server.getConnectors())
            {
                if (c instanceof NetworkConnector && c.getDefaultConnectionFactory().getProtocol().equals("SSL"))
                {
                    _connector = (NetworkConnector)c;
                    break;
                }
            }
        }

        @Override
        public Socket newSocket(String host, int port) throws IOException
        {
            SSLSocket socket = __sslContextFactory.newSslSocket();
            socket.connect(new InetSocketAddress(Inet4Address.getByName(host), port));
            return socket;
        }
    }

    public static class Scenario
    {
        final Class<? extends TestClient> _client;
        final Mode _mode;
        final Boolean _delay;
        final int _status;
        final int _read;
        final int _length;
        final List<String> _send;

        public Scenario(Class<? extends TestClient> client, Mode mode, boolean dispatch, Boolean delay, int status, int read, int length, String... send)
        {
            _client = client;
            _mode = mode;
            __config.setDelayDispatchUntilContent(dispatch);
            _delay = delay;
            _status = status;
            _read = read;
            _length = length;
            _send = Arrays.asList(send);
        }

        @Override
        public String toString()
        {
            return String.format("c=%s, m=%s, delayDispatch=%b delayInFrame=%s content-length:%d expect=%d read=%d content:%s%n",
                _client.getSimpleName(), _mode, __config.isDelayDispatchUntilContent(), _delay, _length, _status, _read, _send);
        }
    }
}
