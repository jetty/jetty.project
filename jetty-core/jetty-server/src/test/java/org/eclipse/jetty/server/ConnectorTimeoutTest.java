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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLHandshakeException;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ByteBufferAccumulator;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.handler.EchoHandler;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.NanoTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.time.Duration.ofSeconds;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
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
        startServer(new HelloWorldHandler());

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
                    "\r\n").getBytes(StandardCharsets.UTF_8));
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
        startServer(new EchoHandler());
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);

        assertFalse(client.isClosed());

        OutputStream os = client.getOutputStream();
        InputStream is = client.getInputStream();

        long start = NanoTime.now();

        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            String content = "Wibble";
            byte[] contentB = content.getBytes(StandardCharsets.UTF_8);
            os.write((
                "POST /echo HTTP/1.1\r\n" +
                    "host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "content-type: text/plain; charset=utf-8\r\n" +
                    "content-length: " + contentB.length + "\r\n" +
                    "\r\n").getBytes(StandardCharsets.UTF_8));
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
        startServer(new HelloWorldHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                try
                {
                    exchanger.exchange(request.getConnectionMetaData().getConnection().getEndPoint());
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                return super.handle(request, response, callback);
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
                "\r\n").getBytes(StandardCharsets.UTF_8));
        os.flush();

        // Get the server side endpoint
        EndPoint endPoint = exchanger.exchange(null, 10, TimeUnit.SECONDS);
        if (endPoint instanceof SslConnection.SslEndPoint sslEndPoint)
            endPoint = sslEndPoint.getSslConnection().getEndPoint();

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
        startServer(new EchoHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                try
                {
                    exchanger.exchange(request.getConnectionMetaData().getConnection().getEndPoint());
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                return super.handle(request, response, callback);
            }
        });
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);

        assertFalse(client.isClosed());

        OutputStream os = client.getOutputStream();
        InputStream is = client.getInputStream();

        String content = "Wibble";
        byte[] contentB = content.getBytes(StandardCharsets.UTF_8);
        os.write((
            "POST /echo HTTP/1.1\r\n" +
                "host: localhost:" + _serverURI.getPort() + "\r\n" +
                "content-type: text/plain; charset=utf-8\r\n" +
                "content-length: " + contentB.length + "\r\n" +
                "connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(contentB);
        os.flush();

        // Get the server side endpoint
        EndPoint endPoint = exchanger.exchange(null, 10, TimeUnit.SECONDS);
        if (endPoint instanceof SslConnection.SslEndPoint sslEndPoint)
            endPoint = sslEndPoint.getSslConnection().getEndPoint();

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
    public void testMaxIdleNoRequest() throws Exception
    {
        startServer(new EchoHandler());
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);
        InputStream is = client.getInputStream();
        assertFalse(client.isClosed());

        OutputStream os = client.getOutputStream();
        long start = NanoTime.now();
        os.write("GET ".getBytes(StandardCharsets.UTF_8));
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
        startServer(new EchoHandler());
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
        startServer(new EchoHandler());

        try (StacklessLogging ignore = new StacklessLogging(Response.class);
             Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
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
                    "\r\n").getBytes(StandardCharsets.UTF_8));
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
    }

    @Test
    public void testMaxIdleDispatch() throws Exception
    {
        startServer(new EchoWholeHandler());

        try (StacklessLogging ignore = new StacklessLogging(Response.class);
             Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            client.setSoTimeout(10000);

            try (InputStream is = client.getInputStream();
                 OutputStream os = client.getOutputStream())
            {
                assertFalse(client.isClosed());
                long start = NanoTime.now();

                byte[] requestBody = "1234567890".getBytes(StandardCharsets.UTF_8);
                // We want a situation where the request says it has a body,
                // but the request hasn't sent all of it.
                int requestBodyLength = requestBody.length * 2;

                String rawRequest = ("""
                    GET / HTTP/1.1\r
                    host: localhost:%d\r
                    connection: keep-alive\r
                    Content-Length: %d\r
                    Content-Type: text/plain\r
                    Connection: close\r
                    \r
                    """).formatted(_serverURI.getPort(), requestBodyLength);

                os.write(rawRequest.getBytes(StandardCharsets.UTF_8));
                os.write(requestBody);
                os.flush();

                assertTimeoutPreemptively(ofSeconds(10), () ->
                {
                    // We expect a 500 response to occur due to the idle timeout triggering.
                    // See: ServerConnector.setIdleTimeout(long ms);
                    String response = IO.toString(is);
                    assertThat(response, containsString("500"));
                    assertEquals(-1, is.read());
                });

                long duration = NanoTime.millisSince(start);
                assertThat(duration + 100, greaterThanOrEqualTo(MAX_IDLE_TIME));
                assertThat(duration - 100, lessThan(maximumTestRuntime));
            }
        }
    }

    @Test
    public void testMaxIdleWithSlowRequest() throws Exception
    {
        startServer(new EchoHandler());
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);

        assertFalse(client.isClosed());

        OutputStream os = client.getOutputStream();
        InputStream is = client.getInputStream();

        String content = "Wibble\r\n";
        byte[] contentB = content.getBytes(StandardCharsets.UTF_8);
        os.write((
            "GET / HTTP/1.0\r\n" +
                "host: localhost:" + _serverURI.getPort() + "\r\n" +
                "connection: keep-alive\r\n" +
                "Content-Length: " + (contentB.length * 20) + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
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
        startServer(new SlowResponseHandler());
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
                "\r\n").getBytes(StandardCharsets.UTF_8));
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
        startServer(new WaitHandler());
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
                "\r\n").getBytes(StandardCharsets.UTF_8));
        os.flush();

        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            String in = IO.toString(is);
            assertThat(in, containsString("Hello World"));
        });
    }

    protected static class SlowResponseHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.setStatus(200);

            Blocker.Shared blocker = new Blocker.Shared();

            for (int i = 0; i < 20; i++)
            {
                try (Blocker.Callback block = blocker.callback())
                {
                    Thread.sleep(50);
                    Content.Sink.write(response, false, "Hello World\r\n", block);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
            response.write(true, null, callback);
            return true;
        }
    }

    protected static class HugeResponseHandler extends Handler.Abstract
    {
        private final int iterations;

        public HugeResponseHandler(int iterations)
        {
            this.iterations = iterations;
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.setStatus(200);
            // Create a big single buffer
            byte[] buffer = new byte[iterations * 1024 * 1024];
            Arrays.fill(buffer, (byte)'x');
            // Toss in an LF after every iteration
            for (int i = 0; i < iterations * 1024; i++)
            {
                buffer[i * 1024 + 1023] = '\n';
            }
            // Write it as a single buffer
            response.write(true, ByteBuffer.wrap(buffer), callback);
            return true;
        }
    }

    protected static class WaitHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.setStatus(200);
            try
            {
                Thread.sleep(2000);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            Content.Sink.write(response, true, "Hello World\r\n", callback);
            return true;
        }
    }

    /**
     * A handler that will echo the request body to the response body, but only
     * once the entire body content has been received.
     */
    public static class EchoWholeHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            long expectedContentLength = request.getHeaders().getLongField(HttpHeader.CONTENT_LENGTH);
            if (expectedContentLength <= 0)
            {
                callback.succeeded();
                return true;
            }

            request.demand(new WholeProcess(request, response, callback));
            return true;
        }

        /**
         * Accumulate the Request body until it's entirely received,
         * then write the body back to the response body.
         */
        private static class WholeProcess implements Runnable
        {
            Request request;
            Response response;
            Callback callback;
            ByteBufferAccumulator bufferAccumulator;

            public WholeProcess(Request request, Response response, Callback callback)
            {
                this.request = request;
                this.response = response;
                this.callback = callback;
                this.bufferAccumulator = new ByteBufferAccumulator();
            }

            @Override
            public void run()
            {
                while (true)
                {
                    Content.Chunk chunk = request.read();
                    if (chunk == null)
                    {
                        request.demand(this);
                        return;
                    }
                    if (chunk instanceof Content.Chunk.Error error)
                    {
                        callback.failed(error.getCause());
                        return;
                    }
                    // copy buffer
                    bufferAccumulator.copyBuffer(chunk.getByteBuffer().slice());
                    chunk.release();
                    if (chunk.isLast())
                    {
                        // write accumulated buffers
                        RetainableByteBuffer buffer = bufferAccumulator.toRetainableByteBuffer();
                        response.write(true, buffer.getByteBuffer(), Callback.from(buffer::release, callback));
                        return;
                    }
                }
            }
        }
    }
}
