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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.client.Socks5.UsernamePasswordAuthenticationFactory;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Socks5ProxyTest
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
        client = new HttpClient(new HttpClientTransportOverHTTP(connector));
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        client.stop();
        proxy.close();
    }

    @Test
    public void testSocks5ProxyIpv4NoAuth() throws Exception
    {
        int proxyPort = proxy.socket().getLocalPort();
        client.getProxyConfiguration().addProxy(new Socks5Proxy("127.0.0.1", proxyPort));

        CountDownLatch latch = new CountDownLatch(1);

        byte ip1 = 127;
        byte ip2 = 0;
        byte ip3 = 0;
        short ip4 = 255;
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
            int initLen = 3;
            ByteBuffer buffer = ByteBuffer.allocate(initLen);
            int read = channel.read(buffer);
            buffer.flip();
            assertEquals(initLen, read);
            assertEquals(Socks5.VERSION, buffer.get());
            assertEquals(1, buffer.get());
            byte authenticationMethod = Socks5.NoAuthenticationFactory.METHOD;
            assertEquals(authenticationMethod, buffer.get());

            // Write handshake response.
            channel.write(ByteBuffer.wrap(new byte[]{Socks5.VERSION, authenticationMethod}));

            // Read server address.
            int addrLen = 10;
            buffer = ByteBuffer.allocate(addrLen);
            read = channel.read(buffer);
            buffer.flip();
            assertEquals(addrLen, read);
            assertEquals(Socks5.VERSION, buffer.get());
            assertEquals(Socks5.COMMAND_CONNECT, buffer.get());
            assertEquals(Socks5.RESERVED, buffer.get());
            assertEquals(Socks5.ADDRESS_TYPE_IPV4, buffer.get());
            assertEquals(ip1, buffer.get());
            assertEquals(ip2, buffer.get());
            assertEquals(ip3, buffer.get());
            assertEquals((byte)ip4, buffer.get());
            assertEquals(serverPort, buffer.getShort() & 0xFFFF);

            // Write connect response.
            channel.write(ByteBuffer.wrap(new byte[]{
                Socks5.VERSION, 0, Socks5.RESERVED, Socks5.ADDRESS_TYPE_IPV4, 127, 0, 0, 2, 13, 13
            }));

            // Parse the HTTP request.
            HttpTester.Request request = HttpTester.parseRequest(channel);
            assertNotNull(request);
            assertEquals(method, request.getMethod());
            assertEquals(path, request.getUri());

            // Write the HTTP response.
            String response = "HTTP/1.1 200 OK\r\n" +
                              "Content-Length: 0\r\n" +
                              "Connection: close\r\n" +
                              "\r\n";
            channel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.US_ASCII)));

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testSocks5ProxyDomainNoAuth() throws Exception
    {
        int proxyPort = proxy.socket().getLocalPort();
        client.getProxyConfiguration().addProxy(new Socks5Proxy("127.0.0.1", proxyPort));

        CountDownLatch latch = new CountDownLatch(1);

        String serverHost = "example.com";
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
            int initLen = 3;
            ByteBuffer buffer = ByteBuffer.allocate(initLen);
            int read = channel.read(buffer);
            buffer.flip();
            assertEquals(initLen, read);
            assertEquals(Socks5.VERSION, buffer.get());
            assertEquals(1, buffer.get());
            byte authenticationMethod = Socks5.NoAuthenticationFactory.METHOD;
            assertEquals(authenticationMethod, buffer.get());

            // Write handshake response.
            channel.write(ByteBuffer.wrap(new byte[]{Socks5.VERSION, authenticationMethod}));

            // Read server address.
            int addrLen = 7 + serverHost.length();
            buffer = ByteBuffer.allocate(addrLen);
            read = channel.read(buffer);
            buffer.flip();
            assertEquals(addrLen, read);
            assertEquals(Socks5.VERSION, buffer.get());
            assertEquals(Socks5.COMMAND_CONNECT, buffer.get());
            assertEquals(Socks5.RESERVED, buffer.get());
            assertEquals(Socks5.ADDRESS_TYPE_DOMAIN, buffer.get());
            int hostLen = buffer.get() & 0xFF;
            assertEquals(serverHost.length(), hostLen);
            int limit = buffer.limit();
            buffer.limit(buffer.position() + hostLen);
            assertEquals(serverHost, StandardCharsets.US_ASCII.decode(buffer).toString());
            buffer.limit(limit);
            assertEquals(serverPort, buffer.getShort() & 0xFFFF);

            // Write connect response.
            channel.write(ByteBuffer.wrap(new byte[]{
                Socks5.VERSION, 0, Socks5.RESERVED, Socks5.ADDRESS_TYPE_IPV4, 127, 0, 0, 3, 11, 11
            }));

            // Parse the HTTP request.
            HttpTester.Request request = HttpTester.parseRequest(channel);
            assertNotNull(request);
            assertEquals(method, request.getMethod());
            assertEquals(path, request.getUri());

            // Write the HTTP response.
            String response = "HTTP/1.1 200 OK\r\n" +
                              "Content-Length: 0\r\n" +
                              "Connection: close\r\n" +
                              "\r\n";
            channel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.US_ASCII)));

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testSocks5ProxyIpv4UsernamePasswordAuth() throws Exception
    {
        String username = "jetty";
        String password = "pass";
        int proxyPort = proxy.socket().getLocalPort();
        Socks5Proxy socks5Proxy = new Socks5Proxy("127.0.0.1", proxyPort);
        socks5Proxy.putAuthenticationFactory(new UsernamePasswordAuthenticationFactory(username, password));
        client.getProxyConfiguration().addProxy(socks5Proxy);

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
            int initLen = 2;
            ByteBuffer buffer = ByteBuffer.allocate(initLen);
            int read = channel.read(buffer);
            buffer.flip();
            assertEquals(initLen, read);
            assertEquals(Socks5.VERSION, buffer.get());
            int authTypeLen = buffer.get() & 0xFF;
            assertTrue(authTypeLen > 0);

            buffer = ByteBuffer.allocate(authTypeLen);
            read = channel.read(buffer);
            buffer.flip();
            assertEquals(authTypeLen, read);
            byte[] authTypes = new byte[authTypeLen];
            buffer.get(authTypes);
            byte authenticationMethod = Socks5.UsernamePasswordAuthenticationFactory.METHOD;
            assertTrue(containsAuthType(authTypes, authenticationMethod));

            // Write handshake response.
            channel.write(ByteBuffer.wrap(new byte[]{Socks5.VERSION, authenticationMethod}));

            // Read authentication request.
            buffer = ByteBuffer.allocate(3 + username.length() + password.length());
            read = channel.read(buffer);
            buffer.flip();
            assertEquals(buffer.capacity(), read);
            assertEquals(1, buffer.get());
            int usernameLen = buffer.get() & 0xFF;
            assertEquals(username.length(), usernameLen);
            int limit = buffer.limit();
            buffer.limit(buffer.position() + usernameLen);
            assertEquals(username, StandardCharsets.US_ASCII.decode(buffer).toString());
            buffer.limit(limit);
            int passwordLen = buffer.get() & 0xFF;
            assertEquals(password.length(), passwordLen);
            assertEquals(password, StandardCharsets.US_ASCII.decode(buffer).toString());

            // Write authentication response.
            channel.write(ByteBuffer.wrap(new byte[]{1, 0}));

            // Read server address.
            int addrLen = 10;
            buffer = ByteBuffer.allocate(addrLen);
            read = channel.read(buffer);
            buffer.flip();
            assertEquals(addrLen, read);
            assertEquals(Socks5.VERSION, buffer.get());
            assertEquals(Socks5.COMMAND_CONNECT, buffer.get());
            assertEquals(Socks5.RESERVED, buffer.get());
            assertEquals(Socks5.ADDRESS_TYPE_IPV4, buffer.get());
            assertEquals(ip1, buffer.get());
            assertEquals(ip2, buffer.get());
            assertEquals(ip3, buffer.get());
            assertEquals(ip4, buffer.get());
            assertEquals(serverPort, buffer.getShort() & 0xFFFF);

            // Write connect response.
            channel.write(ByteBuffer.wrap(new byte[]{
                Socks5.VERSION, 0, Socks5.RESERVED, Socks5.ADDRESS_TYPE_IPV4, 127, 0, 0, 4, 17, 17
            }));

            // Parse the HTTP request.
            HttpTester.Request request = HttpTester.parseRequest(channel);
            assertNotNull(request);
            assertEquals(method, request.getMethod());
            assertEquals(path, request.getUri());

            // Write the HTTP response.
            String response = "HTTP/1.1 200 OK\r\n" +
                              "Content-Length: 0\r\n" +
                              "Connection: close\r\n" +
                              "\r\n";
            channel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.US_ASCII)));

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testSocks5ProxyAuthNoAcceptable() throws Exception
    {
        int proxyPort = proxy.socket().getLocalPort();
        client.getProxyConfiguration().addProxy(new Socks5Proxy("127.0.0.1", proxyPort));

        long timeout = 1000;
        String serverHost = "127.0.0.13";
        int serverPort = proxyPort + 1; // Any port will do
        String method = "GET";
        String path = "/path";
        Request request = client.newRequest(serverHost, serverPort)
            .method(method)
            .path(path)
            .timeout(timeout, TimeUnit.MILLISECONDS);

        FutureResponseListener listener = new FutureResponseListener(request);
        request.send(listener);

        try (SocketChannel channel = proxy.accept())
        {
            int initLen = 3;
            ByteBuffer buffer = ByteBuffer.allocate(initLen);
            int read = channel.read(buffer);
            assertEquals(initLen, read);

            // Deny authentication method.
            byte notAcceptable = -1;
            channel.write(ByteBuffer.wrap(new byte[]{Socks5.VERSION, notAcceptable}));

            ExecutionException x = assertThrows(ExecutionException.class, () -> listener.get(2 * timeout, TimeUnit.MILLISECONDS));
            assertThat(x.getCause(), instanceOf(IOException.class));
        }
    }

    @Test
    public void testSocks5ProxyUsernamePasswordAuthFailed() throws Exception
    {
        String username = "jetty";
        String password = "pass";
        int proxyPort = proxy.socket().getLocalPort();
        Socks5Proxy socks5Proxy = new Socks5Proxy("127.0.0.1", proxyPort);
        socks5Proxy.putAuthenticationFactory(new UsernamePasswordAuthenticationFactory(username, password));
        client.getProxyConfiguration().addProxy(socks5Proxy);

        long timeout = 1000;
        String serverHost = "127.0.0.13";
        int serverPort = proxyPort + 1; // Any port will do
        String method = "GET";
        String path = "/path";
        Request request = client.newRequest(serverHost, serverPort)
            .method(method)
            .path(path)
            .timeout(timeout, TimeUnit.MILLISECONDS);

        FutureResponseListener listener = new FutureResponseListener(request);
        request.send(listener);

        try (SocketChannel channel = proxy.accept())
        {
            int initLen = 2;
            ByteBuffer buffer = ByteBuffer.allocate(initLen);
            int read = channel.read(buffer);
            buffer.flip();
            assertEquals(initLen, read);
            assertEquals(Socks5.VERSION, buffer.get());
            int authTypeLen = buffer.get() & 0xFF;
            assertTrue(authTypeLen > 0);

            buffer = ByteBuffer.allocate(authTypeLen);
            read = channel.read(buffer);
            buffer.flip();
            assertEquals(authTypeLen, read);
            byte[] authTypes = new byte[authTypeLen];
            buffer.get(authTypes);
            byte authenticationMethod = Socks5.UsernamePasswordAuthenticationFactory.METHOD;
            assertTrue(containsAuthType(authTypes, authenticationMethod));

            // Write handshake response.
            channel.write(ByteBuffer.wrap(new byte[]{Socks5.VERSION, authenticationMethod}));

            // Read authentication request.
            buffer = ByteBuffer.allocate(3 + username.length() + password.length());
            read = channel.read(buffer);
            buffer.flip();
            assertEquals(buffer.capacity(), read);
            assertEquals(1, buffer.get());
            int usernameLen = buffer.get() & 0xFF;
            assertEquals(username.length(), usernameLen);
            int limit = buffer.limit();
            buffer.limit(buffer.position() + usernameLen);
            assertEquals(username, StandardCharsets.US_ASCII.decode(buffer).toString());
            buffer.limit(limit);
            int passwordLen = buffer.get() & 0xFF;
            assertEquals(password.length(), passwordLen);
            assertEquals(password, StandardCharsets.US_ASCII.decode(buffer).toString());

            // Fail authentication.
            byte authenticationFailed = 1; // Any non-zero.
            channel.write(ByteBuffer.wrap(new byte[]{1, authenticationFailed}));

            ExecutionException x = assertThrows(ExecutionException.class, () -> listener.get(2 * timeout, TimeUnit.MILLISECONDS));
            assertThat(x.getCause(), instanceOf(IOException.class));
        }
    }

    @Test
    public void testSocks5ProxyDomainUsernamePasswordAuth() throws Exception
    {
        String username = "jetty";
        String password = "pass";
        int proxyPort = proxy.socket().getLocalPort();
        Socks5Proxy socks5Proxy = new Socks5Proxy("127.0.0.1", proxyPort);
        socks5Proxy.putAuthenticationFactory(new UsernamePasswordAuthenticationFactory(username, password));
        client.getProxyConfiguration().addProxy(socks5Proxy);

        CountDownLatch latch = new CountDownLatch(1);

        String serverHost = "example.com";
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
            int initLen = 2;
            ByteBuffer buffer = ByteBuffer.allocate(initLen);
            int read = channel.read(buffer);
            buffer.flip();
            assertEquals(initLen, read);
            assertEquals(Socks5.VERSION, buffer.get());
            int authTypeLen = buffer.get() & 0xFF;
            assertTrue(authTypeLen > 0);

            buffer = ByteBuffer.allocate(authTypeLen);
            read = channel.read(buffer);
            buffer.flip();
            assertEquals(authTypeLen, read);
            byte[] authTypes = new byte[authTypeLen];
            buffer.get(authTypes);
            byte authenticationMethod = Socks5.UsernamePasswordAuthenticationFactory.METHOD;
            assertTrue(containsAuthType(authTypes, authenticationMethod));

            // Write handshake response.
            channel.write(ByteBuffer.wrap(new byte[]{Socks5.VERSION, authenticationMethod}));

            // Read authentication request.
            buffer = ByteBuffer.allocate(3 + username.length() + password.length());
            read = channel.read(buffer);
            buffer.flip();
            assertEquals(buffer.capacity(), read);
            assertEquals(1, buffer.get());
            int usernameLen = buffer.get() & 0xFF;
            assertEquals(username.length(), usernameLen);
            int limit = buffer.limit();
            buffer.limit(buffer.position() + usernameLen);
            assertEquals(username, StandardCharsets.US_ASCII.decode(buffer).toString());
            buffer.limit(limit);
            int passwordLen = buffer.get() & 0xFF;
            assertEquals(password.length(), passwordLen);
            assertEquals(password, StandardCharsets.US_ASCII.decode(buffer).toString());

            // Write authentication response.
            channel.write(ByteBuffer.wrap(new byte[]{1, 0}));

            // Read server address.
            int addrLen = 7 + serverHost.length();
            buffer = ByteBuffer.allocate(addrLen);
            read = channel.read(buffer);
            buffer.flip();
            assertEquals(addrLen, read);
            assertEquals(Socks5.VERSION, buffer.get());
            assertEquals(Socks5.COMMAND_CONNECT, buffer.get());
            assertEquals(Socks5.RESERVED, buffer.get());
            assertEquals(Socks5.ADDRESS_TYPE_DOMAIN, buffer.get());
            int domainLen = buffer.get() & 0xFF;
            assertEquals(serverHost.length(), domainLen);
            limit = buffer.limit();
            buffer.limit(buffer.position() + domainLen);
            assertEquals(serverHost, StandardCharsets.US_ASCII.decode(buffer).toString());
            buffer.limit(limit);
            assertEquals(serverPort, buffer.getShort() & 0xFFFF);

            // Write connect response.
            channel.write(ByteBuffer.wrap(new byte[]{
                Socks5.VERSION, 0, Socks5.RESERVED, Socks5.ADDRESS_TYPE_IPV4, 127, 0, 0, 5, 19, 19
            }));

            // Parse the HTTP request.
            HttpTester.Request request = HttpTester.parseRequest(channel);
            assertNotNull(request);
            assertEquals(method, request.getMethod());
            assertEquals(path, request.getUri());

            // Write the HTTP response.
            String response = "HTTP/1.1 200 OK\r\n" +
                              "Content-Length: 0\r\n" +
                              "Connection: close\r\n" +
                              "\r\n";
            channel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.US_ASCII)));

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testSocks5ProxyDomainUsernamePasswordAuthWithSplitResponse() throws Exception
    {
        String username = "jetty";
        String password = "pass";
        int proxyPort = proxy.socket().getLocalPort();
        Socks5Proxy socks5Proxy = new Socks5Proxy("127.0.0.1", proxyPort);
        socks5Proxy.putAuthenticationFactory(new UsernamePasswordAuthenticationFactory(username, password));
        client.getProxyConfiguration().addProxy(socks5Proxy);

        CountDownLatch latch = new CountDownLatch(1);

        String serverHost = "example.com";
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
            int initLen = 2;
            ByteBuffer buffer = ByteBuffer.allocate(initLen);
            int read = channel.read(buffer);
            buffer.flip();
            assertEquals(initLen, read);
            assertEquals(Socks5.VERSION, buffer.get());
            int authTypeLen = buffer.get() & 0xFF;
            assertTrue(authTypeLen > 0);

            buffer = ByteBuffer.allocate(authTypeLen);
            read = channel.read(buffer);
            buffer.flip();
            assertEquals(authTypeLen, read);
            byte[] authTypes = new byte[authTypeLen];
            buffer.get(authTypes);
            byte authenticationMethod = Socks5.UsernamePasswordAuthenticationFactory.METHOD;
            assertTrue(containsAuthType(authTypes, authenticationMethod));

            // Write handshake response.
            channel.write(ByteBuffer.wrap(new byte[]{Socks5.VERSION, authenticationMethod}));

            // Read authentication request.
            buffer = ByteBuffer.allocate(3 + username.length() + password.length());
            read = channel.read(buffer);
            buffer.flip();
            assertEquals(buffer.capacity(), read);
            assertEquals(1, buffer.get());
            int usernameLen = buffer.get() & 0xFF;
            assertEquals(username.length(), usernameLen);
            int limit = buffer.limit();
            buffer.limit(buffer.position() + usernameLen);
            assertEquals(username, StandardCharsets.US_ASCII.decode(buffer).toString());
            buffer.limit(limit);
            int passwordLen = buffer.get() & 0xFF;
            assertEquals(password.length(), passwordLen);
            assertEquals(password, StandardCharsets.US_ASCII.decode(buffer).toString());

            // Write authentication response.
            channel.write(ByteBuffer.wrap(new byte[]{1, 0}));

            // Read server address.
            int addrLen = 7 + serverHost.length();
            buffer = ByteBuffer.allocate(addrLen);
            read = channel.read(buffer);
            buffer.flip();
            assertEquals(addrLen, read);
            assertEquals(Socks5.VERSION, buffer.get());
            assertEquals(Socks5.COMMAND_CONNECT, buffer.get());
            assertEquals(Socks5.RESERVED, buffer.get());
            assertEquals(Socks5.ADDRESS_TYPE_DOMAIN, buffer.get());
            int domainLen = buffer.get() & 0xFF;
            assertEquals(serverHost.length(), domainLen);
            limit = buffer.limit();
            buffer.limit(buffer.position() + domainLen);
            assertEquals(serverHost, StandardCharsets.US_ASCII.decode(buffer).toString());
            buffer.limit(limit);
            assertEquals(serverPort, buffer.getShort() & 0xFFFF);

            // Write connect response.
            byte[] chunk1 = new byte[]{Socks5.VERSION, 0, Socks5.RESERVED, Socks5.ADDRESS_TYPE_IPV4};
            channel.write(ByteBuffer.wrap(chunk1));
            // Wait before sending the second chunk.
            Thread.sleep(1000);
            byte[] chunk2 = new byte[]{127, 0, 0, 6, 21, 21};
            channel.write(ByteBuffer.wrap(chunk2));

            // Parse the HTTP request.
            HttpTester.Request request = HttpTester.parseRequest(channel);
            assertNotNull(request);
            assertEquals(method, request.getMethod());
            assertEquals(path, request.getUri());

            // Write the HTTP response.
            String response = "HTTP/1.1 200 OK\r\n" +
                              "Content-Length: 0\r\n" +
                              "Connection: close\r\n" +
                              "\r\n";
            channel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.US_ASCII)));

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testSocks5ProxyIpv4NoAuthWithTlsServer() throws Exception
    {
        int proxyPort = proxy.socket().getLocalPort();
        String serverHost = "127.0.0.13"; // Server host different from proxy host.
        int serverPort = proxyPort + 1; // Any port will do.

        SslContextFactory.Client clientTLS = client.getSslContextFactory();
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
        client.getProxyConfiguration().addProxy(new Socks5Proxy("127.0.0.1", proxyPort));

        CountDownLatch latch = new CountDownLatch(1);

        String method = "GET";
        String path = "/path";
        client.newRequest(serverHost, serverPort)
            .scheme(HttpScheme.HTTPS.asString())
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
            int initLen = 3;
            ByteBuffer buffer = ByteBuffer.allocate(initLen);
            int read = channel.read(buffer);
            buffer.flip();
            assertEquals(initLen, read);

            // Write handshake response.
            channel.write(ByteBuffer.wrap(new byte[]{Socks5.VERSION, Socks5.NoAuthenticationFactory.METHOD}));

            // Read server address.
            int addrLen = 10;
            buffer = ByteBuffer.allocate(addrLen);
            read = channel.read(buffer);
            buffer.flip();
            assertEquals(addrLen, read);

            // Write connect response.
            channel.write(ByteBuffer.wrap(new byte[]{
                Socks5.VERSION, 0, Socks5.RESERVED, Socks5.ADDRESS_TYPE_IPV4, 127, 0, 0, 7, 23, 23
            }));

            // Wrap the socket with TLS.
            SslContextFactory.Server serverTLS = new SslContextFactory.Server();
            serverTLS.setKeyStorePath("src/test/resources/keystore.p12");
            serverTLS.setKeyStorePassword("storepwd");
            serverTLS.start();
            SSLContext sslContext = serverTLS.getSslContext();
            SSLSocket sslSocket = (SSLSocket)sslContext.getSocketFactory().createSocket(channel.socket(), serverHost, serverPort, false);
            sslSocket.setUseClientMode(false);

            // Parse the HTTP request.
            HttpTester.Request request = HttpTester.parseRequest(sslSocket.getInputStream());
            assertNotNull(request);
            assertEquals(method, request.getMethod());
            assertEquals(path, request.getUri());

            // Write the HTTP response.
            String response = "HTTP/1.1 200 OK\r\n" +
                              "Content-Length: 0\r\n" +
                              "Connection: close\r\n" +
                              "\r\n";
            OutputStream output = sslSocket.getOutputStream();
            output.write(response.getBytes(StandardCharsets.US_ASCII));
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
    public void testSocksProxyClosesConnectionInHandshake() throws Exception
    {
        int proxyPort = proxy.socket().getLocalPort();
        client.getProxyConfiguration().addProxy(new Socks5Proxy("127.0.0.1", proxyPort));

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
            assertThat(x.getCause(), instanceOf(ClosedChannelException.class));
        }
    }

    @Test
    public void testSocksProxyClosesConnectionInAuthentication() throws Exception
    {
        String username = "jetty";
        String password = "pass";
        int proxyPort = proxy.socket().getLocalPort();
        Socks5Proxy socks5Proxy = new Socks5Proxy("127.0.0.1", proxyPort);
        socks5Proxy.putAuthenticationFactory(new UsernamePasswordAuthenticationFactory(username, password));
        client.getProxyConfiguration().addProxy(socks5Proxy);

        // Use an address to avoid resolution of "localhost" to multiple addresses.
        String serverHost = "127.0.0.13";
        int serverPort = proxyPort + 1; // Any port will do
        Request request = client.newRequest(serverHost, serverPort);
        FutureResponseListener listener = new FutureResponseListener(request);
        request.send(listener);

        try (SocketChannel channel = proxy.accept())
        {
            int initLen = 2;
            ByteBuffer buffer = ByteBuffer.allocate(initLen);
            int read = channel.read(buffer);
            buffer.flip();
            assertEquals(initLen, read);
            assertEquals(Socks5.VERSION, buffer.get());
            int authTypeLen = buffer.get() & 0xFF;
            assertTrue(authTypeLen > 0);

            buffer = ByteBuffer.allocate(authTypeLen);
            read = channel.read(buffer);
            buffer.flip();
            assertEquals(authTypeLen, read);
            byte[] authTypes = new byte[authTypeLen];
            buffer.get(authTypes);
            byte authenticationMethod = Socks5.UsernamePasswordAuthenticationFactory.METHOD;
            assertTrue(containsAuthType(authTypes, authenticationMethod));

            // Write handshake response.
            channel.write(ByteBuffer.wrap(new byte[]{Socks5.VERSION, authenticationMethod}));

            // Read authentication request.
            buffer = ByteBuffer.allocate(3 + username.length() + password.length());
            read = channel.read(buffer);
            buffer.flip();
            assertEquals(buffer.capacity(), read);
            assertEquals(1, buffer.get());
            int usernameLen = buffer.get() & 0xFF;
            assertEquals(username.length(), usernameLen);
            int limit = buffer.limit();
            buffer.limit(buffer.position() + usernameLen);
            assertEquals(username, StandardCharsets.US_ASCII.decode(buffer).toString());
            buffer.limit(limit);
            int passwordLen = buffer.get() & 0xFF;
            assertEquals(password.length(), passwordLen);
            assertEquals(password, StandardCharsets.US_ASCII.decode(buffer).toString());

            channel.close();

            ExecutionException x = assertThrows(ExecutionException.class, () -> listener.get(5, TimeUnit.SECONDS));
            assertThat(x.getCause(), instanceOf(ClosedChannelException.class));
        }
    }

    @Test
    public void testSocksProxyClosesConnectionInConnect() throws Exception
    {
        int proxyPort = proxy.socket().getLocalPort();
        client.getProxyConfiguration().addProxy(new Socks5Proxy("127.0.0.1", proxyPort));

        // Use an address to avoid resolution of "localhost" to multiple addresses.
        String serverHost = "127.0.0.13";
        int serverPort = proxyPort + 1; // Any port will do
        Request request = client.newRequest(serverHost, serverPort);
        FutureResponseListener listener = new FutureResponseListener(request);
        request.send(listener);

        try (SocketChannel channel = proxy.accept())
        {
            int initLen = 3;
            ByteBuffer buffer = ByteBuffer.allocate(initLen);
            int read = channel.read(buffer);
            buffer.flip();
            assertEquals(initLen, read);
            assertEquals(Socks5.VERSION, buffer.get());
            assertEquals(1, buffer.get());
            byte authenticationMethod = Socks5.NoAuthenticationFactory.METHOD;
            assertEquals(authenticationMethod, buffer.get());

            // Write handshake response.
            channel.write(ByteBuffer.wrap(new byte[]{Socks5.VERSION, authenticationMethod}));

            // Read server address.
            int addrLen = 10;
            buffer = ByteBuffer.allocate(addrLen);
            read = channel.read(buffer);
            buffer.flip();
            assertEquals(addrLen, read);

            channel.close();

            ExecutionException x = assertThrows(ExecutionException.class, () -> listener.get(5, TimeUnit.SECONDS));
            assertThat(x.getCause(), instanceOf(ClosedChannelException.class));
        }
    }

    @Test
    public void testSocksProxyResponseGarbageBytes() throws Exception
    {
        int proxyPort = proxy.socket().getLocalPort();
        client.getProxyConfiguration().addProxy(new Socks5Proxy("127.0.0.1", proxyPort));

        // Use an address to avoid resolution of "localhost" to multiple addresses.
        String serverHost = "127.0.0.13";
        int serverPort = proxyPort + 1; // Any port will do
        Request request = client.newRequest(serverHost, serverPort);
        FutureResponseListener listener = new FutureResponseListener(request);
        request.send(listener);

        try (SocketChannel channel = proxy.accept())
        {
            channel.write(ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5}));

            ExecutionException x = assertThrows(ExecutionException.class, () -> listener.get(5, TimeUnit.SECONDS));
            assertThat(x.getCause(), instanceOf(IOException.class));
        }
    }

    @Test
    public void testSocks5ProxyConnectFailed() throws Exception
    {
        int proxyPort = proxy.socket().getLocalPort();
        client.getProxyConfiguration().addProxy(new Socks5Proxy("127.0.0.1", proxyPort));

        String serverHost = "127.0.0.13";
        int serverPort = proxyPort + 1; // Any port will do
        Request request = client.newRequest(serverHost, serverPort);
        FutureResponseListener listener = new FutureResponseListener(request);
        request.send(listener);

        try (SocketChannel channel = proxy.accept())
        {
            int initLen = 3;
            ByteBuffer buffer = ByteBuffer.allocate(initLen);
            int read = channel.read(buffer);
            buffer.flip();
            assertEquals(initLen, read);

            // Write handshake response.
            channel.write(ByteBuffer.wrap(new byte[]{Socks5.VERSION, Socks5.NoAuthenticationFactory.METHOD}));

            // Read server address.
            int addrLen = 10;
            buffer = ByteBuffer.allocate(addrLen);
            read = channel.read(buffer);
            buffer.flip();
            assertEquals(addrLen, read);

            // Write connect response failure.
            channel.write(ByteBuffer.wrap(new byte[]{
                Socks5.VERSION, 1, Socks5.RESERVED, Socks5.ADDRESS_TYPE_IPV4, 127, 0, 0, 8, 29, 29
            }));

            ExecutionException x = assertThrows(ExecutionException.class, () -> listener.get(5, TimeUnit.SECONDS));
            assertThat(x.getCause(), instanceOf(IOException.class));
        }
    }

    @Test
    public void testSocks5ProxyIPv6NoAuth() throws Exception
    {
        int proxyPort = proxy.socket().getLocalPort();
        client.getProxyConfiguration().addProxy(new Socks5Proxy("127.0.0.1", proxyPort));

        CountDownLatch latch = new CountDownLatch(1);

        String serverHost = "::13";
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
            int initLen = 3;
            ByteBuffer buffer = ByteBuffer.allocate(initLen);
            int read = channel.read(buffer);
            buffer.flip();
            assertEquals(initLen, read);

            // Write handshake response.
            channel.write(ByteBuffer.wrap(new byte[]{Socks5.VERSION, Socks5.NoAuthenticationFactory.METHOD}));

            // Read server address.
            int addrLen = 22;
            buffer = ByteBuffer.allocate(addrLen);
            read = channel.read(buffer);
            buffer.flip();
            assertEquals(addrLen, read);
            assertEquals(Socks5.VERSION, buffer.get());
            assertEquals(Socks5.COMMAND_CONNECT, buffer.get());
            assertEquals(Socks5.RESERVED, buffer.get());
            assertEquals(Socks5.ADDRESS_TYPE_IPV6, buffer.get());
            for (int i = 0; i < 15; ++i)
            {
                assertEquals(0, buffer.get());
            }
            assertEquals(0x13, buffer.get());
            assertEquals(serverPort, buffer.getShort() & 0xFFFF);

            // Write connect response.
            ByteBuffer byteBuffer = ByteBuffer.allocate(22)
                .put(Socks5.VERSION)
                .put((byte)0)
                .put(Socks5.RESERVED)
                .put(Socks5.ADDRESS_TYPE_IPV6)
                .put(InetAddress.getByName(serverHost).getAddress())
                .putShort((short)3131)
                .flip();
            // Write slowly 1 byte at a time.
            for (int limit = 1; limit <= buffer.capacity(); ++limit)
            {
                byteBuffer.limit(limit);
                channel.write(byteBuffer);
                Thread.sleep(100);
            }

            // Parse the HTTP request.
            HttpTester.Request request = HttpTester.parseRequest(channel);
            assertNotNull(request);
            assertEquals(method, request.getMethod());
            assertEquals(path, request.getUri());

            // Write the HTTP response.
            String response = "HTTP/1.1 200 OK\r\n" +
                              "Content-Length: 0\r\n" +
                              "Connection: close\r\n" +
                              "\r\n";
            channel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.US_ASCII)));

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    private boolean containsAuthType(byte[] methods, byte method)
    {
        for (byte m : methods)
        {
            if (m == method)
                return true;
        }
        return false;
    }
}
