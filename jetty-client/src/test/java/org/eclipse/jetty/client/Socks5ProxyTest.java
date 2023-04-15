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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketException;
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
import org.eclipse.jetty.client.Socks5.AddrType;
import org.eclipse.jetty.client.Socks5.AuthType;
import org.eclipse.jetty.client.Socks5.Command;
import org.eclipse.jetty.client.Socks5.SockConst;
import org.eclipse.jetty.client.Socks5.UsernamePasswordAuthentication;
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
    public void testSocks5ProxyIpv4NoAuth() throws Exception
    {
        int proxyPort = proxy.socket().getLocalPort();
        client.getProxyConfiguration().addProxy(new Socks5Proxy("127.0.0.1", proxyPort));

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
            int initLen = 3;
            ByteBuffer buffer = ByteBuffer.allocate(initLen);
            int read = channel.read(buffer);
            assertEquals(initLen, read);
            assertEquals(SockConst.VER, buffer.get(0) & 0xFF);
            assertEquals(1, buffer.get(1) & 0xFF);
            assertEquals(AuthType.NO_AUTH, buffer.get(2) & 0xFF);

            // write acceptable methods
            channel.write(ByteBuffer.wrap(new byte[]{SockConst.VER, AuthType.NO_AUTH}));

            // read addr
            int addrLen = 10;
            buffer = ByteBuffer.allocate(addrLen);
            read = channel.read(buffer);
            assertEquals(addrLen, read);
            assertEquals(SockConst.VER, buffer.get(0) & 0xFF);
            assertEquals(Command.CONNECT, buffer.get(1) & 0xFF);
            assertEquals(SockConst.RSV, buffer.get(2) & 0xFF);
            assertEquals(AddrType.IPV4, buffer.get(3) & 0xFF);
            assertEquals(ip1, buffer.get(4) & 0xFF);
            assertEquals(ip2, buffer.get(5) & 0xFF);
            assertEquals(ip3, buffer.get(6) & 0xFF);
            assertEquals(ip4, buffer.get(7) & 0xFF);
            assertEquals(serverPort, buffer.getShort(8) & 0xFFFF);

            // Socks5 connect response.
            channel.write(ByteBuffer.wrap(new byte[]{SockConst.VER, SockConst.SUCCEEDED, SockConst.RSV, AddrType.IPV4, 0, 0, 0, 0, 0, 0}));

            buffer = ByteBuffer.allocate(method.length() + 1 + path.length());
            read = channel.read(buffer);
            assertEquals(buffer.capacity(), read);
            buffer.flip();
            assertEquals(method + " " + path, StandardCharsets.UTF_8.decode(buffer).toString());

            // http response
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
            assertEquals(initLen, read);
            assertEquals(SockConst.VER, buffer.get(0) & 0xFF);
            assertEquals(1, buffer.get(1) & 0xFF);
            assertEquals(AuthType.NO_AUTH, buffer.get(2) & 0xFF);

            // write acceptable methods
            channel.write(ByteBuffer.wrap(new byte[]{SockConst.VER, AuthType.NO_AUTH}));

            // read addr
            int addrLen = 7 + serverHost.length();
            buffer = ByteBuffer.allocate(addrLen);
            read = channel.read(buffer);
            assertEquals(addrLen, read);
            buffer.flip();
            byte[] bs = buffer.array();
            assertEquals(SockConst.VER, bs[0] & 0xFF);
            assertEquals(Command.CONNECT, bs[1] & 0xFF);
            assertEquals(SockConst.RSV, bs[2] & 0xFF);
            assertEquals(AddrType.DOMAIN_NAME, bs[3] & 0xFF);
            int hLen = bs[4] & 0xFF;
            assertEquals(serverHost.length(), hLen);
            assertEquals(serverHost, new String(bs, 5, hLen, StandardCharsets.UTF_8));
            assertEquals(serverPort, buffer.getShort(5 + hLen) & 0xFFFF);

            // Socks5 connect response.
            channel.write(ByteBuffer.wrap(new byte[]{SockConst.VER, SockConst.SUCCEEDED, SockConst.RSV, AddrType.IPV4, 0, 0, 0, 0, 0, 0}));

            buffer = ByteBuffer.allocate(method.length() + 1 + path.length());
            read = channel.read(buffer);
            assertEquals(buffer.capacity(), read);
            buffer.flip();
            assertEquals(method + " " + path, StandardCharsets.UTF_8.decode(buffer).toString());

            // http response
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
    public void testSocks5ProxyIpv4UsernamePasswordAuth() throws Exception
    {
        String username = "jetty";
        String password = "pass";
        int proxyPort = proxy.socket().getLocalPort();
        client.getProxyConfiguration().addProxy(new Socks5Proxy("127.0.0.1", proxyPort)
            .addAuthentication(new UsernamePasswordAuthentication(username, password)));

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
            assertEquals(initLen, read);
            assertEquals(SockConst.VER, buffer.get(0) & 0xFF);
            int authTypeLen = buffer.get(1) & 0xFF;
            assertTrue(authTypeLen > 0);

            buffer = ByteBuffer.allocate(authTypeLen);
            read = channel.read(buffer);

            // assert contains username password authorization
            assertEquals(authTypeLen, read);
            buffer.flip();
            byte[] authTypes = new byte[authTypeLen];
            buffer.get(authTypes);
            assertTrue(containAuthType(authTypes, AuthType.USER_PASS));

            // write acceptable methods
            channel.write(ByteBuffer.wrap(new byte[]{SockConst.VER, AuthType.USER_PASS}));

            // read username password
            buffer = ByteBuffer.allocate(3 + username.length() + password.length());
            read = channel.read(buffer);
            assertEquals(buffer.capacity(), read);
            buffer.flip();
            byte[] userPass = buffer.array();
            assertEquals(SockConst.USER_PASS_VER, userPass[0] & 0xFF);
            int uLen = userPass[1] & 0xFF;
            assertEquals(username.length(), uLen);
            assertEquals(username, new String(userPass, 2, uLen, StandardCharsets.UTF_8));
            int pLen = userPass[2 + uLen];
            assertEquals(password.length(), pLen);
            assertEquals(password, new String(userPass, 3 + uLen, pLen, StandardCharsets.UTF_8));

            // authorization success
            channel.write(ByteBuffer.wrap(new byte[]{SockConst.USER_PASS_VER, SockConst.SUCCEEDED}));

            // read addr
            int addrLen = 10;
            buffer = ByteBuffer.allocate(addrLen);
            read = channel.read(buffer);
            assertEquals(addrLen, read);
            assertEquals(SockConst.VER, buffer.get(0) & 0xFF);
            assertEquals(Command.CONNECT, buffer.get(1) & 0xFF);
            assertEquals(SockConst.RSV, buffer.get(2) & 0xFF);
            assertEquals(AddrType.IPV4, buffer.get(3) & 0xFF);
            assertEquals(ip1, buffer.get(4) & 0xFF);
            assertEquals(ip2, buffer.get(5) & 0xFF);
            assertEquals(ip3, buffer.get(6) & 0xFF);
            assertEquals(ip4, buffer.get(7) & 0xFF);
            assertEquals(serverPort, buffer.getShort(8) & 0xFFFF);

            // Socks5 connect response.
            channel.write(ByteBuffer.wrap(new byte[]{SockConst.VER, SockConst.SUCCEEDED, SockConst.RSV, AddrType.IPV4, 0, 0, 0, 0, 0, 0}));

            buffer = ByteBuffer.allocate(method.length() + 1 + path.length());
            read = channel.read(buffer);
            assertEquals(buffer.capacity(), read);
            buffer.flip();
            assertEquals(method + " " + path, StandardCharsets.UTF_8.decode(buffer).toString());

            // http response
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
    public void testSocks5ProxyIpv4AuthNoAcceptable() throws Exception
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

            // write acceptable methods
            channel.write(ByteBuffer.wrap(new byte[]{SockConst.VER, AuthType.NO_ACCEPTABLE}));

            ExecutionException x = assertThrows(ExecutionException.class, () -> listener.get(2 * timeout, TimeUnit.MILLISECONDS));
            assertThat(x.getCause(), instanceOf(SocketException.class));
        }
    }

    @Test
    public void testSocks5ProxyIpv4UsernamePasswordAuthFailed() throws Exception
    {
        String username = "jetty";
        String password = "pass";
        int proxyPort = proxy.socket().getLocalPort();
        client.getProxyConfiguration().addProxy(new Socks5Proxy("127.0.0.1", proxyPort)
            .addAuthentication(new UsernamePasswordAuthentication(username, password)));

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
            assertEquals(initLen, read);
            assertEquals(SockConst.VER, buffer.get(0) & 0xFF);
            int authTypeLen = buffer.get(1) & 0xFF;
            assertTrue(authTypeLen > 0);

            buffer = ByteBuffer.allocate(authTypeLen);
            read = channel.read(buffer);

            // assert contains username password authorization
            assertEquals(authTypeLen, read);
            buffer.flip();
            byte[] authTypes = new byte[authTypeLen];
            buffer.get(authTypes);
            assertTrue(containAuthType(authTypes, AuthType.USER_PASS));

            // write acceptable methods
            channel.write(ByteBuffer.wrap(new byte[]{SockConst.VER, AuthType.USER_PASS}));

            // read username password
            buffer = ByteBuffer.allocate(3 + username.length() + password.length());
            read = channel.read(buffer);
            assertEquals(buffer.capacity(), read);
            buffer.flip();
            byte[] userPass = buffer.array();
            assertEquals(SockConst.USER_PASS_VER, userPass[0] & 0xFF);
            int uLen = userPass[1] & 0xFF;
            assertEquals(username.length(), uLen);
            assertEquals(username, new String(userPass, 2, uLen, StandardCharsets.UTF_8));
            int pLen = userPass[2 + uLen];
            assertEquals(password.length(), pLen);
            assertEquals(password, new String(userPass, 3 + uLen, pLen, StandardCharsets.UTF_8));

            // authorization failed
            channel.write(ByteBuffer.wrap(new byte[]{SockConst.USER_PASS_VER, SockConst.AUTH_FAILED}));

            ExecutionException x = assertThrows(ExecutionException.class, () -> listener.get(2 * timeout, TimeUnit.MILLISECONDS));
            assertThat(x.getCause(), instanceOf(SocketException.class));
        }
    }

    @Test
    public void testSocks5ProxyDomainUsernamePasswordAuth() throws Exception
    {
        String username = "jetty";
        String password = "pass";
        int proxyPort = proxy.socket().getLocalPort();
        client.getProxyConfiguration().addProxy(new Socks5Proxy("127.0.0.1", proxyPort)
            .addAuthentication(new UsernamePasswordAuthentication(username, password)));

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
            assertEquals(initLen, read);
            assertEquals(SockConst.VER, buffer.get(0) & 0xFF);
            int authTypeLen = buffer.get(1) & 0xFF;
            assertTrue(authTypeLen > 0);

            buffer = ByteBuffer.allocate(authTypeLen);
            read = channel.read(buffer);

            // assert contains username password authorization
            assertEquals(authTypeLen, read);
            buffer.flip();
            byte[] authTypes = new byte[authTypeLen];
            buffer.get(authTypes);
            assertTrue(containAuthType(authTypes, AuthType.USER_PASS));

            // write acceptable methods
            channel.write(ByteBuffer.wrap(new byte[]{SockConst.VER, AuthType.USER_PASS}));

            // read username password
            buffer = ByteBuffer.allocate(3 + username.length() + password.length());
            read = channel.read(buffer);
            assertEquals(buffer.capacity(), read);
            buffer.flip();
            byte[] userPass = buffer.array();
            assertEquals(SockConst.USER_PASS_VER, userPass[0] & 0xFF);
            int uLen = userPass[1] & 0xFF;
            assertEquals(username.length(), uLen);
            assertEquals(username, new String(userPass, 2, uLen, StandardCharsets.UTF_8));
            int pLen = userPass[2 + uLen];
            assertEquals(password.length(), pLen);
            assertEquals(password, new String(userPass, 3 + uLen, pLen, StandardCharsets.UTF_8));

            // authorization success
            channel.write(ByteBuffer.wrap(new byte[]{SockConst.USER_PASS_VER, SockConst.SUCCEEDED}));

            // read addr
            int addrLen = 7 + serverHost.length();
            buffer = ByteBuffer.allocate(addrLen);
            read = channel.read(buffer);
            assertEquals(addrLen, read);
            buffer.flip();
            byte[] bs = buffer.array();
            assertEquals(SockConst.VER, bs[0] & 0xFF);
            assertEquals(Command.CONNECT, bs[1] & 0xFF);
            assertEquals(SockConst.RSV, bs[2] & 0xFF);
            assertEquals(AddrType.DOMAIN_NAME, bs[3] & 0xFF);
            int hLen = bs[4] & 0xFF;
            assertEquals(serverHost.length(), hLen);
            assertEquals(serverHost, new String(bs, 5, hLen, StandardCharsets.UTF_8));
            assertEquals(serverPort, buffer.getShort(5 + hLen) & 0xFFFF);

            // Socks5 connect response.
            channel.write(ByteBuffer.wrap(new byte[]{SockConst.VER, SockConst.SUCCEEDED, SockConst.RSV, AddrType.IPV4, 0, 0, 0, 0, 0, 0}));

            buffer = ByteBuffer.allocate(method.length() + 1 + path.length());
            read = channel.read(buffer);
            assertEquals(buffer.capacity(), read);
            buffer.flip();
            assertEquals(method + " " + path, StandardCharsets.UTF_8.decode(buffer).toString());

            // http response
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
    public void testSocks5ProxyDomainUsernamePasswordAuthWithSplitResponse() throws Exception
    {
        String username = "jetty";
        String password = "pass";
        int proxyPort = proxy.socket().getLocalPort();
        client.getProxyConfiguration().addProxy(new Socks5Proxy("127.0.0.1", proxyPort)
            .addAuthentication(new UsernamePasswordAuthentication(username, password)));

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
            assertEquals(initLen, read);
            assertEquals(SockConst.VER, buffer.get(0) & 0xFF);
            int authTypeLen = buffer.get(1) & 0xFF;
            assertTrue(authTypeLen > 0);

            buffer = ByteBuffer.allocate(authTypeLen);
            read = channel.read(buffer);

            // assert contains username password authorization
            assertEquals(authTypeLen, read);
            buffer.flip();
            byte[] authTypes = new byte[authTypeLen];
            buffer.get(authTypes);
            assertTrue(containAuthType(authTypes, AuthType.USER_PASS));

            // write acceptable methods
            channel.write(ByteBuffer.wrap(new byte[]{SockConst.VER, AuthType.USER_PASS}));

            // read username password
            buffer = ByteBuffer.allocate(3 + username.length() + password.length());
            read = channel.read(buffer);
            assertEquals(buffer.capacity(), read);
            buffer.flip();
            byte[] userPass = buffer.array();
            assertEquals(SockConst.USER_PASS_VER, userPass[0] & 0xFF);
            int uLen = userPass[1] & 0xFF;
            assertEquals(username.length(), uLen);
            assertEquals(username, new String(userPass, 2, uLen, StandardCharsets.UTF_8));
            int pLen = userPass[2 + uLen];
            assertEquals(password.length(), pLen);
            assertEquals(password, new String(userPass, 3 + uLen, pLen, StandardCharsets.UTF_8));

            // authorization success
            channel.write(ByteBuffer.wrap(new byte[]{SockConst.USER_PASS_VER, SockConst.SUCCEEDED}));

            // read addr
            int addrLen = 7 + serverHost.length();
            buffer = ByteBuffer.allocate(addrLen);
            read = channel.read(buffer);
            assertEquals(addrLen, read);
            buffer.flip();
            byte[] bs = buffer.array();
            assertEquals(SockConst.VER, bs[0] & 0xFF);
            assertEquals(Command.CONNECT, bs[1] & 0xFF);
            assertEquals(SockConst.RSV, bs[2] & 0xFF);
            assertEquals(AddrType.DOMAIN_NAME, bs[3] & 0xFF);
            int hLen = bs[4] & 0xFF;
            assertEquals(serverHost.length(), hLen);
            assertEquals(serverHost, new String(bs, 5, hLen, StandardCharsets.UTF_8));
            assertEquals(serverPort, buffer.getShort(5 + hLen) & 0xFFFF);

            // Socks5 connect response.
            byte[] chunk1 = new byte[]{SockConst.VER, SockConst.SUCCEEDED, SockConst.RSV, AddrType.IPV4};
            byte[] chunk2 = new byte[]{0, 0, 0, 0, 0, 0};
            channel.write(ByteBuffer.wrap(chunk1));
            // Wait before sending the second chunk.
            Thread.sleep(1000);
            channel.write(ByteBuffer.wrap(chunk2));

            buffer = ByteBuffer.allocate(method.length() + 1 + path.length());
            read = channel.read(buffer);
            assertEquals(buffer.capacity(), read);
            buffer.flip();
            assertEquals(method + " " + path, StandardCharsets.UTF_8.decode(buffer).toString());

            // http response
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
    public void testSocks5ProxyIpv4NoAuthWithTlsServer() throws Exception
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
            assertEquals(initLen, read);

            // write acceptable methods
            channel.write(ByteBuffer.wrap(new byte[]{SockConst.VER, AuthType.NO_AUTH}));

            // read addr
            int addrLen = 10;
            buffer = ByteBuffer.allocate(addrLen);
            read = channel.read(buffer);
            assertEquals(addrLen, read);

            // Socks5 connect response.
            channel.write(ByteBuffer.wrap(new byte[]{SockConst.VER, SockConst.SUCCEEDED, SockConst.RSV, AddrType.IPV4, 0, 0, 0, 0, 0, 0}));

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
    public void testSocksProxyClosesConnectionImmediately() throws Exception
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
            assertThat(x.getCause(), instanceOf(SocketException.class));
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
            assertThat(x.getCause(), instanceOf(SocketException.class));
        }
    }

    private boolean containAuthType(byte[] methods, byte method){
        for(byte m : methods){
            if(m == method){
                return true;
            }
        }
        return false;
    }
}
