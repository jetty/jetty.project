//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.servlet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TODO need  these on HTTP2 as well!
public class AsyncServletIOTest
{
    private static final Logger LOG = LoggerFactory.getLogger(AsyncServletIOTest.class);
    protected AsyncIOServlet _servlet0 = new AsyncIOServlet();
    protected AsyncIOServlet2 _servlet2 = new AsyncIOServlet2();
    protected AsyncIOServlet3 _servlet3 = new AsyncIOServlet3();
    protected AsyncIOServlet4 _servlet4 = new AsyncIOServlet4();
    protected StolenAsyncReadServlet _servletStolenAsyncRead = new StolenAsyncReadServlet();
    protected AsyncReadServlet _servletAsyncRead = new AsyncReadServlet();
    protected int _port;
    protected Server _server;
    protected ServletHandler _servletHandler;
    protected ServerConnector _connector;

    @BeforeEach
    public void setUp() throws Exception
    {
        _server = new Server();

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setOutputBufferSize(4096);
        _connector = new ServerConnector(_server, new HttpConnectionFactory(httpConfig));

        _server.setConnectors(new Connector[]{_connector});
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/ctx");
        context.addEventListener(new DebugListener());
        _server.setHandler(context);
        _servletHandler = context.getServletHandler();

        ServletHolder holder = new ServletHolder(_servlet0);
        holder.setAsyncSupported(true);
        _servletHandler.addServletWithMapping(holder, "/path/*");

        ServletHolder holder2 = new ServletHolder(_servlet2);
        holder2.setAsyncSupported(true);
        _servletHandler.addServletWithMapping(holder2, "/path2/*");

        ServletHolder holder3 = new ServletHolder(_servlet3);
        holder3.setAsyncSupported(true);
        _servletHandler.addServletWithMapping(holder3, "/path3/*");

        ServletHolder holder4 = new ServletHolder(_servlet4);
        holder4.setAsyncSupported(true);
        _servletHandler.addServletWithMapping(holder4, "/path4/*");

        ServletHolder holder5 = new ServletHolder(_servletStolenAsyncRead);
        holder5.setAsyncSupported(true);
        _servletHandler.addServletWithMapping(holder5, "/stolen/*");

        ServletHolder holder6 = new ServletHolder(_servletAsyncRead);
        holder6.setAsyncSupported(true);
        _servletHandler.addServletWithMapping(holder6, "/asyncRead/*");

        _server.start();
        _port = _connector.getLocalPort();

        _owp.set(0);
        _oda.set(0);
        _read.set(0);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testEmpty() throws Exception
    {
        process();
    }

    @Test
    public void testWrite() throws Exception
    {
        process(10);
    }

    @Test
    public void testWrites() throws Exception
    {
        process(10, 1, 20, 10);
    }

    @Test
    public void testWritesFlushWrites() throws Exception
    {
        process(10, 1, 0, 20, 10);
    }

    @Test
    public void testBigWrite() throws Exception
    {
        process(102400);
    }

    @Test
    public void testBigWrites() throws Exception
    {
        process(102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400, 102400);
        assertThat("On Write Possible", _owp.get(), greaterThanOrEqualTo(1));
    }

    @Test
    public void testRead() throws Exception
    {
        process("Hello!!!\r\n");
    }

    @Test
    public void testBigRead() throws Exception
    {
        process("Now is the time for all good men to come to the aid of the party. How now Brown Cow. The quick brown fox jumped over the lazy dog. The moon is blue to a fish in love.\r\n");
    }

    @Test
    public void testReadWrite() throws Exception
    {
        process("Hello!!!\r\n", 10);
    }

    @Test
    public void testAsync2() throws Exception
    {
        StringBuilder request = new StringBuilder(512);
        request.append("GET /ctx/path2/info HTTP/1.1\r\n")
            .append("Host: localhost\r\n")
            .append("Connection: close\r\n")
            .append("\r\n");

        int port = _port;
        List<String> list = new ArrayList<>();
        try (Socket socket = new Socket("localhost", port))
        {
            socket.setSoTimeout(1000000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()), 102400);

            // response line
            String line = in.readLine();
            LOG.debug("response-line: " + line);
            assertThat(line, startsWith("HTTP/1.1 200 OK"));

            // Skip headers
            while (line != null)
            {
                line = in.readLine();
                LOG.debug("header-line: " + line);
                if (line.length() == 0)
                    break;
            }

            // Get body slowly
            while (true)
            {
                line = in.readLine();
                LOG.debug("body: " + line);
                if (line == null)
                    break;
                list.add(line);
            }
        }

        assertEquals(list.get(0), "data");
        assertTrue(_servlet2.completed.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testEarlyEofDuringClientAsyncRequestBlockingRead() throws Exception
    {
        int port = _port;
        try (Socket socket = new Socket("localhost", port))
        {
            String request =
                "GET /ctx/asyncRead HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Length: 10\r\n" +
                    "\r\n" +
                    "01234";

            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Close.
        }

        // Assert that the blocking read throws EofException.
        await().atMost(5, TimeUnit.SECONDS).until(() -> _servletAsyncRead.exceptionRef.get() instanceof EofException);

        // Assert that no async event was generated as the blocking read got notified of the error.
        await().during(3, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(_servletAsyncRead.listener.events, empty()));
    }

    @Test
    public void testAsyncConsumeAvailable() throws Exception
    {
        StringBuilder request = new StringBuilder(512);
        request.append("GET /ctx/path3/info HTTP/1.1\r\n")
            .append("Host: localhost\r\n")
            .append("Content-Type: text/plain\r\n")
            .append("Content-Length: 10\r\n")
            .append("\r\n")
            .append("0");

        int port = _port;
        try (Socket socket = new Socket("localhost", port))
        {
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()), 102400);

            // response line
            String line = in.readLine();
            LOG.debug("response-line: " + line);
            assertThat(line, startsWith("HTTP/1.1 200 OK"));

            // Skip headers
            while (line != null)
            {
                line = in.readLine();
                LOG.debug("header-line: " + line);
                if (line.length() == 0)
                    break;
            }

            // Get body
            line = in.readLine();
            LOG.debug("body: " + line);
            assertEquals("DONE", line);

            // The connection should be aborted
            line = in.readLine();
            assertNull(line);
        }
    }

    public synchronized List<String> process(String content, int... writes) throws Exception
    {
        return process(content.getBytes(StandardCharsets.ISO_8859_1), writes);
    }

    public synchronized List<String> process(int... writes) throws Exception
    {
        return process((byte[])null, writes);
    }

    public synchronized List<String> process(byte[] content, int... writes) throws Exception
    {
        StringBuilder request = new StringBuilder(512);
        request.append("GET /ctx/path/info");
        char s = '?';
        for (int w : writes)
        {
            request.append(s).append("w=").append(w);
            s = '&';
        }
        LOG.debug("process {} {}", request.toString(), BufferUtil.toDetailString(BufferUtil.toBuffer(content)));

        request.append(" HTTP/1.1\r\n")
            .append("Host: localhost\r\n")
            .append("Connection: close\r\n");

        if (content != null)
            request.append("Content-Length: ").append(content.length).append("\r\n")
                .append("Content-Type: text/plain\r\n");

        request.append("\r\n");

        int port = _port;
        List<String> list = new ArrayList<>();
        try (Socket socket = new Socket("localhost", port))
        {
            socket.setSoTimeout(1000000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));

            if (content != null && content.length > 0)
            {
                Thread.sleep(100);
                out.write(content[0]);
                Thread.sleep(100);
                int half = (content.length - 1) / 2;
                out.write(content, 1, half);
                Thread.sleep(100);
                out.write(content, 1 + half, content.length - half - 1);
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()), 102400);

            // response line
            String line = in.readLine();
            LOG.debug("response-line: " + line);
            assertThat(line, startsWith("HTTP/1.1 200 OK"));

            // Skip headers
            while (line != null)
            {
                line = in.readLine();
                LOG.debug("header-line:  " + line);
                if (line.length() == 0)
                    break;
            }

            // Get body slowly
            while (true)
            {
                line = in.readLine();
                if (line == null)
                    break;
                LOG.debug("body:  " + brief(line));
                list.add(line);
                Thread.sleep(50);
            }
        }

        // check lines
        int w = 0;
        for (String line : list)
        {
            LOG.debug("line:  " + brief(line));
            if ("-".equals(line))
                continue;
            assertEquals(writes[w], line.length(), "Line Length");
            assertEquals(line.charAt(0), '0' + (w % 10), "Line Contents");

            w++;
            if (w < writes.length && writes[w] <= 0)
                w++;
        }

        if (content != null)
            assertEquals(content.length, _read.get(), "Content Length");

        return list;
    }

    private static String brief(String line)
    {
        return line.length() + "\t" + (line.length() > 40 ? (line.substring(0, 40) + "...") : line);
    }

    static AtomicInteger _owp = new AtomicInteger();
    static AtomicInteger _oda = new AtomicInteger();
    static AtomicInteger _read = new AtomicInteger();

    private static class AsyncIOServlet extends HttpServlet
    {
        private static final long serialVersionUID = -8161977157098646562L;

        public AsyncIOServlet()
        {
        }

        @Override
        public void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
        {
            final AsyncContext async = request.startAsync();
            final AtomicInteger complete = new AtomicInteger(2);
            final AtomicBoolean onDataAvailable = new AtomicBoolean(false);

            // Asynchronous Read
            if (request.getContentLength() > 0)
            {
                // System.err.println("reading "+request.getContentLength());
                final ServletInputStream in = request.getInputStream();
                in.setReadListener(new ReadListener()
                {
                    byte[] _buf = new byte[32];

                    @Override
                    public void onError(Throwable t)
                    {
                        if (complete.decrementAndGet() == 0)
                            async.complete();
                    }

                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        if (!onDataAvailable.compareAndSet(false, true))
                            throw new IllegalStateException();

                        //System.err.println("ODA");
                        while (in.isReady() && !in.isFinished())
                        {
                            _oda.incrementAndGet();
                            int len = in.read(_buf);
                            //System.err.println("read "+len);
                            if (len > 0)
                                _read.addAndGet(len);
                        }

                        if (!onDataAvailable.compareAndSet(true, false))
                            throw new IllegalStateException();
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                        if (onDataAvailable.get())
                        {
                            LOG.warn("OADR too early!");
                            _read.set(-1);
                        }

                        // System.err.println("OADR");
                        if (complete.decrementAndGet() == 0)
                            async.complete();
                    }
                });
            }
            else
                complete.decrementAndGet();

            // Asynchronous Write
            final String[] writes = request.getParameterValues("w");
            final ServletOutputStream out = response.getOutputStream();
            out.setWriteListener(new WriteListener()
            {
                int _w = 0;

                @Override
                public void onWritePossible() throws IOException
                {
                    LOG.debug("OWP");
                    _owp.incrementAndGet();

                    while (writes != null && _w < writes.length)
                    {
                        int write = Integer.parseInt(writes[_w++]);

                        if (write == 0)
                            out.flush();
                        else
                        {
                            byte[] buf = new byte[write + 1];
                            Arrays.fill(buf, (byte)('0' + ((_w - 1) % 10)));
                            buf[write] = '\n';
                            out.write(buf);
                        }

                        if (!out.isReady())
                            return;
                    }

                    if (complete.decrementAndGet() == 0)
                        async.complete();
                }

                @Override
                public void onError(Throwable t)
                {
                    async.complete();
                }
            });
        }
    }

    @SuppressWarnings("serial")
    public class AsyncIOServlet2 extends HttpServlet
    {
        public CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void doGet(final HttpServletRequest request, final HttpServletResponse response) throws IOException
        {
            new SampleAsycListener(request, response);
        }

        class SampleAsycListener implements WriteListener, AsyncListener
        {
            final ServletResponse response;
            final ServletOutputStream servletOutputStream;
            final AsyncContext asyncContext;
            volatile boolean written = false;

            SampleAsycListener(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                asyncContext = request.startAsync();
                asyncContext.setTimeout(10000L);
                asyncContext.addListener(this);
                servletOutputStream = response.getOutputStream();
                servletOutputStream.setWriteListener(this);
                this.response = response;
            }

            @Override
            public void onWritePossible() throws IOException
            {
                if (!written)
                {
                    written = true;
                    response.setContentLength(5);
                    servletOutputStream.write("data\n".getBytes());
                }

                if (servletOutputStream.isReady())
                {
                    asyncContext.complete();
                }
            }

            @Override
            public void onError(final Throwable t)
            {
                t.printStackTrace();
                asyncContext.complete();
            }

            @Override
            public void onComplete(final AsyncEvent event) throws IOException
            {
                completed.countDown();
            }

            @Override
            public void onTimeout(final AsyncEvent event) throws IOException
            {
                asyncContext.complete();
            }

            @Override
            public void onError(final AsyncEvent event) throws IOException
            {
                asyncContext.complete();
            }

            @Override
            public void onStartAsync(AsyncEvent event) throws IOException
            {

            }
        }
    }

    @SuppressWarnings("serial")
    public class AsyncIOServlet3 extends HttpServlet
    {
        public CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void doGet(final HttpServletRequest request, final HttpServletResponse response) throws IOException
        {
            AsyncContext async = request.startAsync();

            request.getInputStream().setReadListener(new ReadListener()
            {

                @Override
                public void onError(Throwable t)
                {
                }

                @Override
                public void onDataAvailable() throws IOException
                {
                }

                @Override
                public void onAllDataRead() throws IOException
                {
                }
            });

            response.setStatus(200);
            response.getOutputStream().print("DONE");
            async.complete();
        }
    }

    @Test
    public void testCompleteWhilePending() throws Exception
    {
        _servlet4.onDA.set(0);
        _servlet4.onWP.set(0);

        StringBuilder request = new StringBuilder(512);
        request.append("POST /ctx/path4/info HTTP/1.1\r\n")
            .append("Host: localhost\r\n")
            .append("Content-Type: text/plain\r\n")
            .append("Content-Length: 20\r\n")
            .append("\r\n")
            .append("12345678\r\n");
        int port = _port;
        List<String> list = new ArrayList<>();
        try (Socket socket = new Socket("localhost", port))
        {
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(ISO_8859_1));
            out.flush();
            Thread.sleep(100);
            out.write("ABC".getBytes(ISO_8859_1));
            out.flush();
            Thread.sleep(100);
            out.write("DEF".getBytes(ISO_8859_1));
            out.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // response line
            String line = in.readLine();
            LOG.debug("response-line: " + line);
            assertThat(line, startsWith("HTTP/1.1 200 OK"));

            boolean chunked = false;
            // Skip headers
            while (line != null)
            {
                line = in.readLine();
                LOG.debug("header-line: " + line);
                chunked |= "Transfer-Encoding: chunked".equals(line);
                if (line.length() == 0)
                    break;
            }

            assertTrue(chunked);

            // Get body slowly
            String last = null;
            try
            {
                while (true)
                {
                    last = line;
                    //Thread.sleep(1000);
                    line = in.readLine();
                    LOG.debug("body: " + line);
                    if (line == null)
                        break;
                    list.add(line);
                }
            }
            catch (IOException e)
            {
                // ignored
            }

            LOG.debug("last: " + last);

            // last non empty line should not contain end chunk
            assertThat(last, notNullValue());
            assertThat(last.trim(), not(startsWith("0")));
        }

        assertTrue(_servlet4.completed.await(5, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(0, _servlet4.onDA.get());
        assertEquals(0, _servlet4.onWP.get());
    }

    @SuppressWarnings("serial")
    public class AsyncIOServlet4 extends HttpServlet
    {
        public CountDownLatch completed = new CountDownLatch(1);
        public AtomicInteger onDA = new AtomicInteger();
        public AtomicInteger onWP = new AtomicInteger();

        @Override
        public void doPost(final HttpServletRequest request, final HttpServletResponse response) throws IOException
        {
            final AsyncContext async = request.startAsync();
            final ServletInputStream in = request.getInputStream();
            final ServletOutputStream out = response.getOutputStream();

            in.setReadListener(new ReadListener()
            {
                @Override
                public void onError(Throwable t)
                {
                    t.printStackTrace();
                }

                @Override
                public void onDataAvailable() throws IOException
                {
                    onDA.incrementAndGet();

                    boolean readF = false;
                    // Read all available content
                    while (in.isReady())
                    {
                        int c = in.read();
                        if (c < 0)
                            throw new IllegalStateException();
                        if (c == 'F')
                            readF = true;
                    }

                    if (readF)
                    {
                        onDA.set(0);

                        final byte[] buffer = new byte[64 * 1024];
                        Arrays.fill(buffer, (byte)'X');
                        for (int i = 199; i < buffer.length; i += 200)
                        {
                            buffer[i] = (byte)'\n';
                        }

                        // Once we read block, let's make ourselves write blocked
                        out.setWriteListener(new WriteListener()
                        {
                            @Override
                            public void onWritePossible() throws IOException
                            {
                                onWP.incrementAndGet();

                                while (out.isReady())
                                {
                                    out.write(buffer);
                                }

                                try
                                {
                                    // As soon as we are write blocked, complete
                                    onWP.set(0);
                                    async.complete();
                                }
                                catch (Exception e)
                                {
                                    e.printStackTrace();
                                }
                                finally
                                {
                                    completed.countDown();
                                }
                            }

                            @Override
                            public void onError(Throwable t)
                            {
                                t.printStackTrace();
                            }
                        });
                    }
                }

                @Override
                public void onAllDataRead() throws IOException
                {
                    throw new IllegalStateException();
                }
            });
        }
    }

    @Test
    public void testStolenAsyncRead() throws Exception
    {
        String request = """
            POST /ctx/stolen/info HTTP/1.1
            Host: localhost
            Content-Type: text/plain
            Content-Length: 2
            
            1""";

        try (Socket socket = new Socket("localhost", _port))
        {
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(ISO_8859_1));
            out.flush();

            // Because the read was stolen, onDataAvailable() is not called.
            // The wait guarantees that the Servlet thread is out of doPost().
            assertFalse(_servletStolenAsyncRead.oda.await(500, TimeUnit.MILLISECONDS));

            // Send some more data.
            out.write('2');
            out.flush();

            // onDataAvailable() should now be called.
            assertTrue(_servletStolenAsyncRead.oda.await(500, TimeUnit.MILLISECONDS));

            ServletInputStream in = _servletStolenAsyncRead.listener.in;

            // We can now read some more.
            assertTrue(in.isReady());
            assertEquals('2', in.read());

            // All content has been sent, must read EOF.
            assertTrue(in.isReady());
            assertEquals(-1, in.read());

            HttpTester.Response response = HttpTester.parseResponse(socket.getInputStream());
            assertNotNull(response);
            assertEquals(200, response.getStatus());
        }
    }

    public static class StolenAsyncReadServlet extends HttpServlet
    {
        private final CountDownLatch oda = new CountDownLatch(1);
        private volatile StealingListener listener;

        @Override
        public void doPost(final HttpServletRequest request, final HttpServletResponse response) throws IOException
        {
            listener = new StealingListener(request);

            // Steal the read.
            assertEquals('1', listener.in.read());

            // Make sure the ReadListener is called when more content is available.
            assertFalse(listener.in.isReady());

            // Exit from doPost() so that ReadListener methods can now be invoked.
        }

        public class StealingListener implements ReadListener
        {
            private final ServletInputStream in;
            private final AsyncContext asyncContext;

            public StealingListener(HttpServletRequest request) throws IOException
            {
                asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                in = request.getInputStream();
                in.setReadListener(this);
            }

            @Override
            public void onDataAvailable()
            {
                oda.countDown();
            }

            @Override
            public void onAllDataRead()
            {
                asyncContext.complete();
            }

            @Override
            public void onError(Throwable t)
            {
                t.printStackTrace();
                asyncContext.complete();
            }
        }
    }

    public static class AsyncReadServlet extends HttpServlet
    {
        final AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
        final TestAsyncListener listener = new TestAsyncListener();

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            AsyncContext asyncContext = req.startAsync();
            asyncContext.addListener(listener);
            new Thread(() ->
            {
                try
                {
                    IO.readBytes(req.getInputStream());
                    throw new AssertionError("expected IOException");
                }
                catch (Throwable x)
                {
                    exceptionRef.set(x);
                }
            }).start();
        }

        static class TestAsyncListener implements AsyncListener
        {
            final List<String> events = new CopyOnWriteArrayList<>();

            @Override
            public void onComplete(AsyncEvent event)
            {
                events.add("complete");
            }

            @Override
            public void onTimeout(AsyncEvent event)
            {
                events.add("timeout");
            }

            @Override
            public void onError(AsyncEvent event)
            {
                events.add("error");
            }

            @Override
            public void onStartAsync(AsyncEvent event)
            {
                events.add("startAsync");
            }
        }
    }
}
