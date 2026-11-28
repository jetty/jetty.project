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

package org.eclipse.jetty.client.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.client.ByteBufferRequestContent;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.InputStreamResponseListener;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.OutputStreamRequestContent;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.transport.HttpDestination;
import org.eclipse.jetty.client.transport.internal.HttpConnectionOverHTTP;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpSenderOverHTTPTest
{
    private HttpClient client;

    @BeforeEach
    public void init() throws Exception
    {
        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        client.stop();
    }

    @Test
    public void testSendNoRequestContent() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise<>() {});
        Request request = client.newRequest(URI.create("http://localhost/"));
        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(1);
        request.onRequestListener(new Request.Listener()
        {
            @Override
            public void onHeaders(Request request)
            {
                headersLatch.countDown();
            }

            @Override
            public void onSuccess(Request request)
            {
                successLatch.countDown();
            }
        });
        connection.send(request, null);

        String requestString = endPoint.takeOutputString();
        assertTrue(requestString.startsWith("GET "));
        assertTrue(requestString.endsWith("\r\n\r\n"));
        assertTrue(headersLatch.await(5, TimeUnit.SECONDS));
        assertTrue(successLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSendNoRequestContentIncompleteFlush() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint("", 16);
        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise<>() {});
        Request request = client.newRequest(URI.create("http://localhost/"));
        connection.send(request, null);

        // This take will free space in the buffer and allow for the write to complete
        StringBuilder builder = new StringBuilder(endPoint.takeOutputString());

        // Wait for the write to complete
        await().atMost(5, TimeUnit.SECONDS).until(() -> endPoint.toEndPointString().contains(",flush=P,"));

        String chunk = endPoint.takeOutputString();
        while (chunk.length() > 0)
        {
            builder.append(chunk);
            chunk = endPoint.takeOutputString();
        }

        String requestString = builder.toString();
        assertTrue(requestString.startsWith("GET "));
        assertTrue(requestString.endsWith("\r\n\r\n"));
    }

    @Test
    public void testSendNoRequestContentException() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        // Shutdown output to trigger the exception on write
        endPoint.shutdownOutput();
        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise<>() {});
        Request request = client.newRequest(URI.create("http://localhost/"));
        final CountDownLatch failureLatch = new CountDownLatch(2);
        request.onRequestListener(new Request.Listener()
        {
            @Override
            public void onFailure(Request request, Throwable x)
            {
                failureLatch.countDown();
            }
        });
        connection.send(request, new Response.Listener()
        {
            @Override
            public void onComplete(Result result)
            {
                assertTrue(result.isFailed());
                failureLatch.countDown();
            }
        });

        assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSendNoRequestContentIncompleteFlushException() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint("", 16);
        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise<>() {});
        Request request = client.newRequest(URI.create("http://localhost/"));
        final CountDownLatch failureLatch = new CountDownLatch(2);
        request.onRequestListener(new Request.Listener()
        {
            @Override
            public void onFailure(Request request, Throwable x)
            {
                failureLatch.countDown();
            }
        });
        connection.send(request, new Response.Listener()
        {
            @Override
            public void onComplete(Result result)
            {
                assertTrue(result.isFailed());
                failureLatch.countDown();
            }
        });

        // Shutdown output to trigger the exception on write
        endPoint.shutdownOutput();
        // This take will free space in the buffer and allow for the write to complete
        // although it will fail because we shut down the output
        endPoint.takeOutputString();

        assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(10)
    public void testReceiveResponseAfterRequestWriteFailure(boolean receiveBeforeFailure) throws Exception
    {
        EofException failure = new EofException("write failure");
        AtomicBoolean failWrites = new AtomicBoolean();
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint()
        {
            @Override
            public boolean flush(ByteBuffer... buffers) throws IOException
            {
                if (failWrites.get())
                    throw failure;
                return super.flush(buffers);
            }
        };
        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise<>() {});
        endPoint.setConnection(connection);
        OutputStreamRequestContent content = new OutputStreamRequestContent();
        InputStreamResponseListener listener = new InputStreamResponseListener();
        Request request = client.newRequest("http://localhost/").body(content);
        connection.send(request, listener);

        endPoint.addInput("HTTP/1.1 500 Server Error\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "\r\n" +
            "5\r\nnope!\r\n0\r\n\r\n");
        if (receiveBeforeFailure)
        {
            // Buffer the response without consuming its content.
            connection.getHttpChannel().receive();
            assertEquals(500, listener.get(5, TimeUnit.SECONDS).getStatus());
        }

        failWrites.set(true);
        assertThrows(IOException.class, () ->
        {
            content.getOutputStream().write(1);
            content.getOutputStream().flush();
        });

        // The response may still be unread in the endpoint when the write fails.
        if (!receiveBeforeFailure)
            connection.getHttpChannel().receive();
        assertEquals(500, listener.get(5, TimeUnit.SECONDS).getStatus());
        try (var input = listener.getInputStream())
        {
            assertEquals("nope!", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        Result result = listener.await(5, TimeUnit.SECONDS);
        assertSame(failure, result.getRequestFailure());
        assertNull(result.getResponseFailure());
        assertTrue(connection.isClosed());
    }

    @Test
    @Timeout(20)
    public void testReceiveResponseOverTlsAfterRequestWriteFailure() throws Exception
    {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress()))
        {
            int port = server.getLocalPort();

            SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
            sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
            sslContextFactory.setKeyStorePassword("storepwd");
            sslContextFactory.setEndpointIdentificationAlgorithm(null);
            ClientConnector connector = new ClientConnector();
            connector.setSslContextFactory(sslContextFactory);
            HttpClient tlsClient = new HttpClient(new HttpClientTransportOverHTTP(connector));
            tlsClient.start();
            try
            {
                Thread acceptor = new Thread(() -> serveTls(server, tlsClient.getSslContextFactory().getSslContext(), port));
                acceptor.setDaemon(true);
                acceptor.start();

                OutputStreamRequestContent content = new OutputStreamRequestContent("text/plain");
                InputStreamResponseListener listener = new InputStreamResponseListener();
                tlsClient.newRequest("localhost", port).scheme("https").method(HttpMethod.POST).body(content).timeout(10, TimeUnit.SECONDS).send(listener);
                OutputStream output = content.getOutputStream();
                output.write('x');
                output.flush();
                assertEquals(500, listener.get(5, TimeUnit.SECONDS).getStatus());

                // The server has sent the whole response and closed the connection.
                assertThrows(IOException.class, () ->
                {
                    byte[] chunk = new byte[8192];
                    for (int i = 0; i < 100_000; ++i)
                    {
                        output.write(chunk);
                        output.flush();
                    }
                });

                try (InputStream input = listener.getInputStream())
                {
                    assertEquals("nope!", new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
                Result result = listener.await(5, TimeUnit.SECONDS);
                assertNotNull(result.getRequestFailure());
                assertNull(result.getResponseFailure());
            }
            finally
            {
                tlsClient.stop();
            }
        }
    }

    private static void serveTls(ServerSocket server, SSLContext sslContext, int port)
    {
        String response = "HTTP/1.1 500 Server Error\r\n" +
            "Content-Type: text/plain\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "5\r\nnope!\r\n" +
            "0\r\n" +
            "\r\n";
        while (true)
        {
            try (Socket socket = server.accept())
            {
                SSLSocket sslSocket = (SSLSocket)sslContext.getSocketFactory().createSocket(socket, "localhost", port, false);
                sslSocket.setUseClientMode(false);
                sslSocket.startHandshake();
                InputStream input = sslSocket.getInputStream();
                int crlfs = 0;
                while (crlfs < 4)
                {
                    int read = input.read();
                    if (read < 0)
                        return;
                    if (read == '\r' || read == '\n')
                        ++crlfs;
                    else
                        crlfs = 0;
                }
                OutputStream output = sslSocket.getOutputStream();
                output.write(response.getBytes(StandardCharsets.US_ASCII));
                output.flush();
                sslSocket.close();
            }
            catch (IOException x)
            {
                if (server.isClosed())
                    return;
            }
        }
    }

    @Test
    public void testSendSmallRequestContentInOneBuffer() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise<>() {});
        Request request = client.newRequest(URI.create("http://localhost/"));
        String content = "abcdef";
        request.body(new ByteBufferRequestContent(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8))));
        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(1);
        request.onRequestListener(new Request.Listener()
        {
            @Override
            public void onHeaders(Request request)
            {
                headersLatch.countDown();
            }

            @Override
            public void onSuccess(Request request)
            {
                successLatch.countDown();
            }
        });
        connection.send(request, null);

        String requestString = endPoint.takeOutputString();
        assertTrue(requestString.startsWith("GET "));
        assertTrue(requestString.endsWith("\r\n\r\n" + content));
        assertTrue(headersLatch.await(5, TimeUnit.SECONDS));
        assertTrue(successLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSendSmallRequestContentInTwoBuffers() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise<>() {});
        Request request = client.newRequest(URI.create("http://localhost/"));
        String content1 = "0123456789";
        String content2 = "abcdef";
        request.body(new ByteBufferRequestContent(ByteBuffer.wrap(content1.getBytes(StandardCharsets.UTF_8)), ByteBuffer.wrap(content2.getBytes(StandardCharsets.UTF_8))));
        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(1);
        request.onRequestListener(new Request.Listener()
        {
            @Override
            public void onHeaders(Request request)
            {
                headersLatch.countDown();
            }

            @Override
            public void onSuccess(Request request)
            {
                successLatch.countDown();
            }
        });
        connection.send(request, null);

        String requestString = endPoint.takeOutputString();
        assertTrue(requestString.startsWith("GET "));
        assertThat(requestString, Matchers.endsWith("\r\n\r\n" + content1 + content2));
        assertTrue(headersLatch.await(5, TimeUnit.SECONDS));
        assertTrue(successLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSendSmallRequestContentChunkedInTwoChunks() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise<>() {});
        Request request = client.newRequest(URI.create("http://localhost/"));
        String content1 = "0123456789";
        String content2 = "ABCDEF";
        request.body(new ByteBufferRequestContent(ByteBuffer.wrap(content1.getBytes(StandardCharsets.UTF_8)), ByteBuffer.wrap(content2.getBytes(StandardCharsets.UTF_8)))
        {
            @Override
            public long getLength()
            {
                return -1;
            }
        });
        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(1);
        request.onRequestListener(new Request.Listener()
        {
            @Override
            public void onHeaders(Request request)
            {
                headersLatch.countDown();
            }

            @Override
            public void onSuccess(Request request)
            {
                successLatch.countDown();
            }
        });
        connection.send(request, null);

        String requestString = endPoint.takeOutputString();
        assertTrue(requestString.startsWith("GET "));
        String content = Integer.toHexString(content1.length()).toUpperCase(Locale.ENGLISH) + "\r\n" + content1 + "\r\n";
        content += Integer.toHexString(content2.length()).toUpperCase(Locale.ENGLISH) + "\r\n" + content2 + "\r\n";
        content += "0\r\n\r\n";
        assertTrue(requestString.endsWith("\r\n\r\n" + content));
        assertTrue(headersLatch.await(5, TimeUnit.SECONDS));
        assertTrue(successLatch.await(5, TimeUnit.SECONDS));
    }
}
