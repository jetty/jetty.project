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

package org.eclipse.jetty.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLHandshakeException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.NanoTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.time.Duration.ofSeconds;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class ConnectorTimeoutTest extends HttpServerTestFixture
{
    protected static final Logger LOG = LoggerFactory.getLogger(ConnectorTimeoutTest.class);

    protected static final long MAX_IDLE_TIME = 2000;
    private final long sleepTime = MAX_IDLE_TIME + MAX_IDLE_TIME / 5;
    private final long minimumTestRuntime = MAX_IDLE_TIME - MAX_IDLE_TIME / 5;
    private final long maximumTestRuntime = MAX_IDLE_TIME * 10;

    static
    {
        System.setProperty("org.eclipse.jetty.io.nio.IDLE_TICK", "500");
    }

    @BeforeEach
    @Override
    public void before()
    {
        super.before();
        if (_httpConfiguration != null)
        {
            _httpConfiguration.setMinRequestDataRate(-1);
            _httpConfiguration.setIdleTimeout(-1);
        }
    }

    @Test
    public void testMaxIdleWithRequest10() throws Exception
    {
        configureServer(new HelloWorldHandler());

        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);

        assertFalse(client.isClosed());

        OutputStream os = client.getOutputStream();
        InputStream is = client.getInputStream();

        long start = NanoTime.now();

        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            os.write((
                "GET / HTTP/1.0\r\n" +
                    "host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "connection: keep-alive\r\n" +
                    "\r\n").getBytes("utf-8"));
            os.flush();

            IO.toString(is);

            Thread.sleep(sleepTime);
            assertEquals(-1, is.read());
        });

        long elapsedMs = NanoTime.millisSince(start);
        assertTrue(elapsedMs > minimumTestRuntime);
        assertTrue(elapsedMs < maximumTestRuntime);
    }

    @Test
    public void testMaxIdleWithRequest11() throws Exception
    {
        configureServer(new EchoHandler());
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);

        assertFalse(client.isClosed());

        OutputStream os = client.getOutputStream();
        InputStream is = client.getInputStream();

        long start = NanoTime.now();

        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            String content = "Wibble";
            byte[] contentB = content.getBytes("utf-8");
            os.write((
                "POST /echo HTTP/1.1\r\n" +
                    "host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "content-type: text/plain; charset=utf-8\r\n" +
                    "content-length: " + contentB.length + "\r\n" +
                    "\r\n").getBytes("utf-8"));
            os.write(contentB);
            os.flush();

            IO.toString(is);

            Thread.sleep(sleepTime);
            assertEquals(-1, is.read());
        });

        long elapsedMs = NanoTime.millisSince(start);
        assertTrue(elapsedMs > minimumTestRuntime);
        assertTrue(elapsedMs < maximumTestRuntime);
    }

    @Test
    public void testMaxIdleWithRequest10NoClientClose() throws Exception
    {
        final Exchanger<EndPoint> exchanger = new Exchanger<>();
        configureServer(new HelloWorldHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException,
                ServletException
            {
                try
                {
                    exchanger.exchange(baseRequest.getHttpChannel().getEndPoint());
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                super.handle(target, baseRequest, request, response);
            }
        });
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);

        assertFalse(client.isClosed());

        OutputStream os = client.getOutputStream();
        InputStream is = client.getInputStream();

        os.write((
            "GET / HTTP/1.0\r\n" +
                "host: localhost:" + _serverURI.getPort() + "\r\n" +
                "connection: close\r\n" +
                "\r\n").getBytes("utf-8"));
        os.flush();

        // Get the server side endpoint
        EndPoint endPoint = exchanger.exchange(null, 10, TimeUnit.SECONDS);
        if (endPoint instanceof SslConnection.DecryptedEndPoint)
            endPoint = ((SslConnection.DecryptedEndPoint)endPoint).getSslConnection().getEndPoint();

        // read the response
        String result = IO.toString(is);
        assertThat("OK", result, containsString("200 OK"));

        // check client reads EOF
        assertEquals(-1, is.read());
        assertTrue(endPoint.isOutputShutdown());

        // wait for idle timeout
        TimeUnit.MILLISECONDS.sleep(2 * MAX_IDLE_TIME);

        // check the server side is closed
        assertFalse(endPoint.isOpen());
        Object transport = endPoint.getTransport();
        if (transport instanceof Channel)
            assertFalse(((Channel)transport).isOpen());
    }

    @Test
    public void testMaxIdleWithRequest11NoClientClose() throws Exception
    {
        final Exchanger<EndPoint> exchanger = new Exchanger<>();
        configureServer(new EchoHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException,
                ServletException
            {
                try
                {
                    exchanger.exchange(baseRequest.getHttpChannel().getEndPoint());
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                super.handle(target, baseRequest, request, response);
            }
        });
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);

        assertFalse(client.isClosed());

        OutputStream os = client.getOutputStream();
        InputStream is = client.getInputStream();

        String content = "Wibble";
        byte[] contentB = content.getBytes("utf-8");
        os.write((
            "POST /echo HTTP/1.1\r\n" +
                "host: localhost:" + _serverURI.getPort() + "\r\n" +
                "content-type: text/plain; charset=utf-8\r\n" +
                "content-length: " + contentB.length + "\r\n" +
                "connection: close\r\n" +
                "\r\n").getBytes("utf-8"));
        os.write(contentB);
        os.flush();

        // Get the server side endpoint
        EndPoint endPoint = exchanger.exchange(null, 10, TimeUnit.SECONDS);
        if (endPoint instanceof SslConnection.DecryptedEndPoint)
            endPoint = ((SslConnection.DecryptedEndPoint)endPoint).getSslConnection().getEndPoint();

        // read the response
        IO.toString(is);

        // check client reads EOF
        assertEquals(-1, is.read());
        assertTrue(endPoint.isOutputShutdown());

        // The server has shutdown the output, the client does not close,
        // the server should idle timeout and close the connection.
        TimeUnit.MILLISECONDS.sleep(2 * MAX_IDLE_TIME);

        assertFalse(endPoint.isOpen());
        Object transport = endPoint.getTransport();
        if (transport instanceof Channel)
            assertFalse(((Channel)transport).isOpen());
    }

    @Test
    @Tag("Unstable")
    @Disabled // TODO make more stable
    public void testNoBlockingTimeoutRead() throws Exception
    {
        configureServer(new EchoHandler());
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);
        InputStream is = client.getInputStream();
        assertFalse(client.isClosed());

        long start = NanoTime.now();

        OutputStream os = client.getOutputStream();
        os.write(("GET / HTTP/1.1\r\n" +
            "host: localhost:" + _serverURI.getPort() + "\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Content-Type: text/plain\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "5\r\n" +
            "LMNOP\r\n")
            .getBytes("utf-8"));
        os.flush();

        try
        {
            Thread.sleep(250);
            os.write("1".getBytes("utf-8"));
            os.flush();
            Thread.sleep(250);
            os.write("0".getBytes("utf-8"));
            os.flush();
            Thread.sleep(250);
            os.write("\r".getBytes("utf-8"));
            os.flush();
            Thread.sleep(250);
            os.write("\n".getBytes("utf-8"));
            os.flush();
            Thread.sleep(250);
            os.write("0123456789ABCDEF\r\n".getBytes("utf-8"));
            os.write("0\r\n".getBytes("utf-8"));
            os.write("\r\n".getBytes("utf-8"));
            os.flush();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        long duration = NanoTime.millisSince(start);
        assertThat(duration, greaterThan(500L));

        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            // read the response
            String response = IO.toString(is);
            assertThat(response, startsWith("HTTP/1.1 200 OK"));
            assertThat(response, containsString("LMNOP0123456789ABCDEF"));
        });
    }

    @Test
    @Tag("Unstable")
    @Disabled // TODO make more stable
    public void testBlockingTimeoutRead() throws Exception
    {
        configureServer(new EchoHandler());
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);
        InputStream is = client.getInputStream();
        assertFalse(client.isClosed());

        OutputStream os = client.getOutputStream();

        long start = NanoTime.now();
        os.write(("GET / HTTP/1.1\r\n" +
            "host: localhost:" + _serverURI.getPort() + "\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Content-Type: text/plain\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "5\r\n" +
            "LMNOP\r\n")
            .getBytes("utf-8"));
        os.flush();

        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            Thread.sleep(300);
            os.write("1".getBytes("utf-8"));
            os.flush();
            Thread.sleep(300);
            os.write("0".getBytes("utf-8"));
            os.flush();
            Thread.sleep(300);
            os.write("\r".getBytes("utf-8"));
            os.flush();
            Thread.sleep(300);
            os.write("\n".getBytes("utf-8"));
            os.flush();
            Thread.sleep(300);
            os.write("0123456789ABCDEF\r\n".getBytes("utf-8"));
            os.write("0\r\n".getBytes("utf-8"));
            os.write("\r\n".getBytes("utf-8"));
            os.flush();
        }

        long duration = NanoTime.millisSince(start);
        assertThat(duration, greaterThan(500L));

        // read the response
        String response = IO.toString(is);
        assertThat(response, startsWith("HTTP/1.1 500 "));
        assertThat(response, containsString("InterruptedIOException"));

    }

    @Test
    @Tag("Unstable")
    @Disabled // TODO make more stable
    public void testNoBlockingTimeoutWrite() throws Exception
    {
        configureServer(new HugeResponseHandler());
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);

        assertFalse(client.isClosed());

        OutputStream os = client.getOutputStream();
        BufferedReader is = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1), 2048);

        os.write((
            "GET / HTTP/1.0\r\n" +
                "host: localhost:" + _serverURI.getPort() + "\r\n" +
                "connection: keep-alive\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes("utf-8"));
        os.flush();

        // read the header
        String line = is.readLine();
        assertThat(line, startsWith("HTTP/1.1 200 OK"));
        while (line.length() != 0)
        {
            line = is.readLine();
        }

        for (int i = 0; i < (128 * 1024); i++)
        {
            if (i % 1028 == 0)
            {
                Thread.sleep(20);
            }
            line = is.readLine();
            assertThat(line, notNullValue());
            assertEquals(1022, line.length());
        }
    }

    @Test
    @Tag("Unstable")
    @Disabled // TODO make more stable
    public void testBlockingTimeoutWrite() throws Exception
    {
        configureServer(new HugeResponseHandler());
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);

        assertFalse(client.isClosed());

        OutputStream os = client.getOutputStream();
        BufferedReader is = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1), 2048);

        os.write((
            "GET / HTTP/1.0\r\n" +
                "host: localhost:" + _serverURI.getPort() + "\r\n" +
                "connection: keep-alive\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes("utf-8"));
        os.flush();

        // read the header
        String line = is.readLine();
        assertThat(line, startsWith("HTTP/1.1 200 OK"));
        while (line.length() != 0)
        {
            line = is.readLine();
        }

        long start = NanoTime.now();
        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class, AbstractConnection.class))
        {
            for (int i = 0; i < (128 * 1024); i++)
            {
                if (i % 1028 == 0)
                {
                    Thread.sleep(20);
                }
                line = is.readLine();
                if (line == null)
                    break;
            }
        }
        assertThat(NanoTime.millisSince(start), lessThan(20L * 128L));
    }

    @Test
    public void testMaxIdleNoRequest() throws Exception
    {
        configureServer(new EchoHandler());
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);
        InputStream is = client.getInputStream();
        assertFalse(client.isClosed());

        OutputStream os = client.getOutputStream();
        long start = NanoTime.now();
        os.write("GET ".getBytes("utf-8"));
        os.flush();

        Thread.sleep(sleepTime);
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            try
            {
                String response = IO.toString(is);
                assertThat(response, is(""));
                assertEquals(-1, is.read());
            }
            catch (Exception e)
            {
                LOG.warn(e.getMessage());
            }
        });
        assertTrue(NanoTime.millisSince(start) < maximumTestRuntime);
    }

    @Test
    public void testMaxIdleNothingSent() throws Exception
    {
        configureServer(new EchoHandler());
        long start = NanoTime.now();
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);
        InputStream is = client.getInputStream();
        assertFalse(client.isClosed());

        Thread.sleep(sleepTime);
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            try
            {
                String response = IO.toString(is);
                assertThat(response, is(""));
                assertEquals(-1, is.read());
            }
            catch (SSLHandshakeException e)
            {
                LOG.debug("Legit possible SSL result", e);
            }
            catch (IOException e)
            {
                LOG.warn("Unable to read stream", e);
            }
        });
        assertTrue(NanoTime.millisSince(start) < maximumTestRuntime);
    }

    @Test
    public void testMaxIdleDelayedDispatch() throws Exception
    {
        configureServer(new EchoHandler());
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);
        InputStream is = client.getInputStream();
        assertFalse(client.isClosed());

        OutputStream os = client.getOutputStream();
        long start = NanoTime.now();
        os.write((
            "GET / HTTP/1.1\r\n" +
                "host: localhost:" + _serverURI.getPort() + "\r\n" +
                "connection: keep-alive\r\n" +
                "Content-Length: 20\r\n" +
                "Content-Type: text/plain\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes("utf-8"));
        os.flush();

        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            try
            {
                String response = IO.toString(is);
                assertThat(response, containsString("500"));
                assertEquals(-1, is.read());
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        });
        long duration = NanoTime.millisSince(start);
        assertThat(duration, greaterThanOrEqualTo(MAX_IDLE_TIME));
        assertThat(duration, lessThan(maximumTestRuntime));
    }

    @Test
    public void testMaxIdleDispatch() throws Exception
    {
        configureServer(new EchoHandler());
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);
        InputStream is = client.getInputStream();
        assertFalse(client.isClosed());

        OutputStream os = client.getOutputStream();
        long start = NanoTime.now();
        os.write((
            "GET / HTTP/1.1\r\n" +
                "host: localhost:" + _serverURI.getPort() + "\r\n" +
                "connection: keep-alive\r\n" +
                "Content-Length: 20\r\n" +
                "Content-Type: text/plain\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "1234567890").getBytes("utf-8"));
        os.flush();

        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            try
            {
                String response = IO.toString(is);
                assertThat(response, containsString("500"));
                assertEquals(-1, is.read());
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        });
        long duration = NanoTime.millisSince(start);
        assertThat(duration + 100, greaterThanOrEqualTo(MAX_IDLE_TIME));
        assertThat(duration - 100, lessThan(maximumTestRuntime));
    }

    @Test
    public void testMaxIdleWithSlowRequest() throws Exception
    {
        configureServer(new EchoHandler());
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);

        assertFalse(client.isClosed());

        OutputStream os = client.getOutputStream();
        InputStream is = client.getInputStream();

        String content = "Wibble\r\n";
        byte[] contentB = content.getBytes("utf-8");
        os.write((
            "GET / HTTP/1.0\r\n" +
                "host: localhost:" + _serverURI.getPort() + "\r\n" +
                "connection: keep-alive\r\n" +
                "Content-Length: " + (contentB.length * 20) + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes("utf-8"));
        os.flush();

        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            for (int i = 0; i < 20; i++)
            {
                Thread.sleep(50);
                os.write(contentB);
                os.flush();
            }

            String in = IO.toString(is);
            int offset = 0;
            for (int i = 0; i < 20; i++)
            {
                offset = in.indexOf("Wibble", offset + 1);
                assertTrue(offset > 0, "" + i);
            }
        });
    }

    @Test
    public void testMaxIdleWithSlowResponse() throws Exception
    {
        configureServer(new SlowResponseHandler());
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);

        assertFalse(client.isClosed());

        OutputStream os = client.getOutputStream();
        InputStream is = client.getInputStream();

        os.write((
            "GET / HTTP/1.0\r\n" +
                "host: localhost:" + _serverURI.getPort() + "\r\n" +
                "connection: keep-alive\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes("utf-8"));
        os.flush();

        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            String in = IO.toString(is);
            int offset = 0;
            for (int i = 0; i < 20; i++)
            {
                offset = in.indexOf("Hello World", offset + 1);
                assertTrue(offset > 0, "" + i);
            }
        });
    }

    @Test
    public void testMaxIdleWithWait() throws Exception
    {
        configureServer(new WaitHandler());
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);

        assertFalse(client.isClosed());

        OutputStream os = client.getOutputStream();
        InputStream is = client.getInputStream();

        os.write((
            "GET / HTTP/1.0\r\n" +
                "host: localhost:" + _serverURI.getPort() + "\r\n" +
                "connection: keep-alive\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes("utf-8"));
        os.flush();

        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            String in = IO.toString(is);
            assertThat(in, containsString("Hello World"));
        });
    }

    protected static class SlowResponseHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);
            OutputStream out = response.getOutputStream();

            for (int i = 0; i < 20; i++)
            {
                out.write("Hello World\r\n".getBytes());
                out.flush();
                try
                {
                    Thread.sleep(50);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
            out.close();
        }
    }

    protected static class HugeResponseHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);
            OutputStream out = response.getOutputStream();
            byte[] buffer = new byte[128 * 1024 * 1024];
            Arrays.fill(buffer, (byte)'x');
            for (int i = 0; i < 128 * 1024; i++)
            {
                buffer[i * 1024 + 1022] = '\r';
                buffer[i * 1024 + 1023] = '\n';
            }
            ByteBuffer bb = ByteBuffer.wrap(buffer);
            ((HttpOutput)out).sendContent(bb);
            out.close();
        }
    }

    protected static class WaitHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);
            OutputStream out = response.getOutputStream();
            try
            {
                Thread.sleep(2000);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            out.write("Hello World\r\n".getBytes());
            out.flush();
        }
    }
}
