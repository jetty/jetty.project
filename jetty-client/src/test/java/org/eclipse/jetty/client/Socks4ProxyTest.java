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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Socks4ProxyTest
{
    private ServerSocketChannel proxy;
    private HttpClient client;

    @BeforeEach
    public void prepare() throws Exception
    {
        proxy = ServerSocketChannel.open();
        proxy.bind(new InetSocketAddress("127.0.0.1", 0));

        ClientConnector connector = new ClientConnector();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        connector.setExecutor(clientThreads);
        connector.setSslContextFactory(new SslContextFactory.Client());
        client = new HttpClient(new HttpClientTransportOverHTTP(connector));
        client.setExecutor(clientThreads);
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        client.stop();
        proxy.close();
    }

    @Test
    public void testSocks4Proxy() throws Exception
    {
        int proxyPort = proxy.socket().getLocalPort();
        client.getProxyConfiguration().addProxy(new Socks4Proxy("127.0.0.1", proxyPort));

        CountDownLatch latch = new CountDownLatch(1);

        byte ip1 = 127;
        byte ip2 = 0;
        byte ip3 = 0;
        byte ip4 = 13;
        String serverHost = ip1 + "." + ip2 + "." + ip3 + "." + ip4;
        int serverPort = proxyPort + 1; // Any port will do
        String method = "GET";
        String path = "/path";
        client.newRequest(serverHost, serverPort)
            .method(method)
            .path(path)
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                if (result.isSucceeded())
                    latch.countDown();
            });

        try (SocketChannel channel = proxy.accept())
        {
            int socks4MessageLength = 9;
            ByteBuffer buffer = ByteBuffer.allocate(socks4MessageLength);
            int read = channel.read(buffer);
            assertEquals(socks4MessageLength, read);
            assertEquals(4, buffer.get(0) & 0xFF);
            assertEquals(1, buffer.get(1) & 0xFF);
            assertEquals(serverPort, buffer.getShort(2) & 0xFFFF);
            assertEquals(ip1, buffer.get(4) & 0xFF);
            assertEquals(ip2, buffer.get(5) & 0xFF);
            assertEquals(ip3, buffer.get(6) & 0xFF);
            assertEquals(ip4, buffer.get(7) & 0xFF);
            assertEquals(0, buffer.get(8) & 0xFF);

            // Socks4 response.
            channel.write(ByteBuffer.wrap(new byte[]{0, 0x5A, 0, 0, 0, 0, 0, 0}));

            buffer = ByteBuffer.allocate(method.length() + 1 + path.length());
            read = channel.read(buffer);
            assertEquals(buffer.capacity(), read);
            buffer.flip();
            assertEquals(method + " " + path, StandardCharsets.UTF_8.decode(buffer).toString());

            // Response
            String response =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
            channel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testSocks4ProxyWithSplitResponse() throws Exception
    {
        int proxyPort = proxy.socket().getLocalPort();
        client.getProxyConfiguration().addProxy(new Socks4Proxy("127.0.0.1", proxyPort));

        CountDownLatch latch = new CountDownLatch(1);

        String serverHost = "127.0.0.13"; // Test expects an IP address.
        int serverPort = proxyPort + 1; // Any port will do
        String method = "GET";
        client.newRequest(serverHost, serverPort)
            .method(method)
            .path("/path")
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                if (result.isSucceeded())
                    latch.countDown();
                else
                    result.getFailure().printStackTrace();
            });

        try (SocketChannel channel = proxy.accept())
        {
            int socks4MessageLength = 9;
            ByteBuffer buffer = ByteBuffer.allocate(socks4MessageLength);
            int read = channel.read(buffer);
            assertEquals(socks4MessageLength, read);

            // Socks4 response, with split bytes.
            byte[] chunk1 = new byte[]{0, 0x5A, 0};
            byte[] chunk2 = new byte[]{0, 0, 0, 0, 0};
            channel.write(ByteBuffer.wrap(chunk1));

            // Wait before sending the second chunk.
            Thread.sleep(1000);

            channel.write(ByteBuffer.wrap(chunk2));

            buffer = ByteBuffer.allocate(method.length());
            read = channel.read(buffer);
            assertEquals(buffer.capacity(), read);
            buffer.flip();
            assertEquals(method, StandardCharsets.UTF_8.decode(buffer).toString());

            // Response
            String response =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
            channel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testSocks4ProxyWithTLSServer() throws Exception
    {
        int proxyPort = proxy.socket().getLocalPort();

        String serverHost = "127.0.0.13"; // Server host different from proxy host.
        int serverPort = proxyPort + 1; // Any port will do.

        SslContextFactory clientTLS = client.getSslContextFactory();
        clientTLS.reload(ssl ->
        {
            // The client keystore contains the trustedCertEntry for the
            // self-signed server certificate, so it acts as a truststore.
            ssl.setTrustStorePath("src/test/resources/client_keystore.p12");
            ssl.setTrustStorePassword("storepwd");
            // Disable TLS hostname verification, but
            // enable application hostname verification.
            ssl.setEndpointIdentificationAlgorithm(null);
            // The hostname must be that of the server, not of the proxy.
            ssl.setHostnameVerifier((hostname, session) -> serverHost.equals(hostname));
        });
        client.getProxyConfiguration().addProxy(new Socks4Proxy("127.0.0.1", proxyPort));

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(serverHost, serverPort)
            .scheme(HttpScheme.HTTPS.asString())
            .path("/path")
            .send(result ->
            {
                if (result.isSucceeded())
                    latch.countDown();
                else
                    result.getFailure().printStackTrace();
            });

        try (SocketChannel channel = proxy.accept())
        {
            int socks4MessageLength = 9;
            ByteBuffer buffer = ByteBuffer.allocate(socks4MessageLength);
            int read = channel.read(buffer);
            assertEquals(socks4MessageLength, read);

            // Socks4 response.
            channel.write(ByteBuffer.wrap(new byte[]{0, 0x5A, 0, 0, 0, 0, 0, 0}));

            // Wrap the socket with TLS.
            SslContextFactory.Server serverTLS = new SslContextFactory.Server();
            serverTLS.setKeyStorePath("src/test/resources/keystore.p12");
            serverTLS.setKeyStorePassword("storepwd");
            serverTLS.start();
            SSLContext sslContext = serverTLS.getSslContext();
            SSLSocket sslSocket = (SSLSocket)sslContext.getSocketFactory().createSocket(channel.socket(), serverHost, serverPort, false);
            sslSocket.setUseClientMode(false);

            // Read the request.
            int crlfs = 0;
            InputStream input = sslSocket.getInputStream();
            while (true)
            {
                read = input.read();
                if (read < 0)
                    break;
                if (read == '\r' || read == '\n')
                    ++crlfs;
                else
                    crlfs = 0;
                if (crlfs == 4)
                    break;
            }

            // Send the response.
            String response =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
            OutputStream output = sslSocket.getOutputStream();
            output.write(response.getBytes(StandardCharsets.UTF_8));
            output.flush();

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testRequestTimeoutWhenSocksProxyDoesNotRespond() throws Exception
    {
        int proxyPort = proxy.socket().getLocalPort();
        client.getProxyConfiguration().addProxy(new Socks4Proxy("127.0.0.1", proxyPort));

        long timeout = 1000;

        // Use an address to avoid resolution of "localhost" to multiple addresses.
        String serverHost = "127.0.0.13";
        int serverPort = proxyPort + 1; // Any port will do
        Request request = client.newRequest(serverHost, serverPort)
            .timeout(timeout, TimeUnit.MILLISECONDS);
        FutureResponseListener listener = new FutureResponseListener(request);
        request.send(listener);

        try (SocketChannel ignored = proxy.accept())
        {
            // Accept the connection, but do not reply and don't close.

            ExecutionException x = assertThrows(ExecutionException.class, () -> listener.get(2 * timeout, TimeUnit.MILLISECONDS));
            assertThat(x.getCause(), instanceOf(TimeoutException.class));
        }
    }

    @Test
    public void testIdleTimeoutWhenSocksProxyDoesNotRespond() throws Exception
    {
        int proxyPort = proxy.socket().getLocalPort();
        client.getProxyConfiguration().addProxy(new Socks4Proxy("127.0.0.1", proxyPort));
        long idleTimeout = 1000;
        client.setIdleTimeout(idleTimeout);

        // Use an address to avoid resolution of "localhost" to multiple addresses.
        String serverHost = "127.0.0.13";
        int serverPort = proxyPort + 1; // Any port will do
        Request request = client.newRequest(serverHost, serverPort);
        FutureResponseListener listener = new FutureResponseListener(request);
        request.send(listener);

        try (SocketChannel ignored = proxy.accept())
        {
            // Accept the connection, but do not reply and don't close.

            ExecutionException x = assertThrows(ExecutionException.class, () -> listener.get(2 * idleTimeout, TimeUnit.MILLISECONDS));
            Class<?> expectedException = "ci".equals(System.getProperty("env")) ? ConnectException.class : TimeoutException.class;
            assertThat(x.getCause(), instanceOf(expectedException));
        }
    }

    @Test
    public void testSocksProxyClosesConnectionImmediately() throws Exception
    {
        int proxyPort = proxy.socket().getLocalPort();
        client.getProxyConfiguration().addProxy(new Socks4Proxy("127.0.0.1", proxyPort));

        // Use an address to avoid resolution of "localhost" to multiple addresses.
        String serverHost = "127.0.0.13";
        int serverPort = proxyPort + 1; // Any port will do
        Request request = client.newRequest(serverHost, serverPort);
        FutureResponseListener listener = new FutureResponseListener(request);
        request.send(listener);

        try (SocketChannel channel = proxy.accept())
        {
            // Immediately close the connection.
            channel.close();

            ExecutionException x = assertThrows(ExecutionException.class, () -> listener.get(5, TimeUnit.SECONDS));
            assertThat(x.getCause(), instanceOf(IOException.class));
        }
    }
}
