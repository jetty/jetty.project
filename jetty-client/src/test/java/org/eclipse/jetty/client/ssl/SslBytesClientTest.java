//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.ssl;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SslBytesClientTest extends SslBytesTest
{
    private ExecutorService threadPool;
    private HttpClient client;
    private SslContextFactory sslContextFactory;
    private SSLServerSocket acceptor;
    private SimpleProxy proxy;

    @Before
    public void init() throws Exception
    {
        threadPool = Executors.newCachedThreadPool();

        client = new HttpClient(new SslContextFactory(true));
        client.setMaxConnectionsPerDestination(1);
        File keyStore = MavenTestingUtils.getTestResourceFile("keystore.jks");
        sslContextFactory = client.getSslContextFactory();
        sslContextFactory.setKeyStorePath(keyStore.getAbsolutePath());
        sslContextFactory.setKeyStorePassword("storepwd");
        client.start();

        SSLContext sslContext = sslContextFactory.getSslContext();
        acceptor = (SSLServerSocket)sslContext.getServerSocketFactory().createServerSocket(0);

        int serverPort = acceptor.getLocalPort();

        proxy = new SimpleProxy(threadPool, "localhost", serverPort);
        proxy.start();
        logger.info(":{} <==> :{}", proxy.getPort(), serverPort);
    }

    @After
    public void destroy() throws Exception
    {
        if (acceptor != null)
            acceptor.close();
        if (proxy != null)
            proxy.stop();
        if (client != null)
            client.stop();
        if (threadPool != null)
            threadPool.shutdownNow();
    }

    @Test
    public void testHandshake() throws Exception
    {
        Request request = client.newRequest("localhost", proxy.getPort());
        FutureResponseListener listener = new FutureResponseListener(request);
        request.scheme(HttpScheme.HTTPS.asString()).send(listener);

        Assert.assertTrue(proxy.awaitClient(5, TimeUnit.SECONDS));

        final SSLSocket server = (SSLSocket)acceptor.accept();
        server.setUseClientMode(false);

        Future<Object> handshake = threadPool.submit(() ->
        {
            server.startHandshake();
            return null;
        });

        // Client Hello
        TLSRecord record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToServer(record);

        // Server Hello + Certificate + Server Done
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToClient(record);

        // Client Key Exchange
        record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToServer(record);

        // Change Cipher Spec
        record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.CHANGE_CIPHER_SPEC, record.getType());
        proxy.flushToServer(record);

        // Client Done
        record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToServer(record);

        // Change Cipher Spec
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.CHANGE_CIPHER_SPEC, record.getType());
        proxy.flushToClient(record);

        // Server Done
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToClient(record);

        Assert.assertNull(handshake.get(5, TimeUnit.SECONDS));

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        // Read request
        BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        Assert.assertTrue(line.startsWith("GET"));
        while (line.length() > 0)
            line = reader.readLine();

        // Write response
        OutputStream output = server.getOutputStream();
        output.write(("HTTP/1.1 200 OK\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        ContentResponse response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        server.close();
    }

    @Test
    public void testServerRenegotiation() throws Exception
    {
        Request request = client.newRequest("localhost", proxy.getPort());
        FutureResponseListener listener = new FutureResponseListener(request);
        request.scheme(HttpScheme.HTTPS.asString()).send(listener);

        Assert.assertTrue(proxy.awaitClient(5, TimeUnit.SECONDS));

        final SSLSocket server = (SSLSocket)acceptor.accept();
        server.setUseClientMode(false);

        Future<Object> handshake = threadPool.submit(() ->
        {
            server.startHandshake();
            return null;
        });

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        Assert.assertNull(handshake.get(5, TimeUnit.SECONDS));

        // Read request
        InputStream serverInput = server.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(serverInput, StandardCharsets.UTF_8));
        String line = reader.readLine();
        Assert.assertTrue(line.startsWith("GET"));
        while (line.length() > 0)
            line = reader.readLine();

        OutputStream serverOutput = server.getOutputStream();
        byte[] data1 = new byte[1024];
        Arrays.fill(data1, (byte)'X');
        String content1 = new String(data1, StandardCharsets.UTF_8);
        byte[] data2 = new byte[1024];
        Arrays.fill(data2, (byte)'Y');
        final String content2 = new String(data2, StandardCharsets.UTF_8);
        // Write first part of the response
        serverOutput.write(("HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + (content1.length() + content2.length()) + "\r\n" +
                "\r\n" +
                content1).getBytes(StandardCharsets.UTF_8));
        serverOutput.flush();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        // Renegotiate
        Future<Object> renegotiation = threadPool.submit(() ->
        {
            server.startHandshake();
            return null;
        });

        // Renegotiation Handshake
        TLSRecord record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToClient(record);

        // Renegotiation Handshake
        record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToServer(record);

        // Trigger a read to have the server write the final renegotiation steps
        server.setSoTimeout(100);
        try
        {
            serverInput.read();
            Assert.fail();
        }
        catch (SocketTimeoutException x)
        {
            // Expected
        }

        // Renegotiation Handshake
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToClient(record);

        // Renegotiation Change Cipher
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.CHANGE_CIPHER_SPEC, record.getType());
        proxy.flushToClient(record);

        // Renegotiation Handshake
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToClient(record);

        // Renegotiation Change Cipher
        record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.CHANGE_CIPHER_SPEC, record.getType());
        proxy.flushToServer(record);

        // Renegotiation Handshake
        record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToServer(record);

        Assert.assertNull(renegotiation.get(5, TimeUnit.SECONDS));

        // Complete the response
        automaticProxyFlow = proxy.startAutomaticFlow();
        serverOutput.write(data2);
        serverOutput.flush();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        ContentResponse response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        Assert.assertEquals(data1.length + data2.length, response.getContent().length);

        server.close();
    }

    @Test
    public void testServerRenegotiationWhenRenegotiationIsForbidden() throws Exception
    {
        sslContextFactory.setRenegotiationAllowed(false);

        Request request = client.newRequest("localhost", proxy.getPort());
        FutureResponseListener listener = new FutureResponseListener(request);
        request.scheme(HttpScheme.HTTPS.asString()).send(listener);

        Assert.assertTrue(proxy.awaitClient(5, TimeUnit.SECONDS));

        final SSLSocket server = (SSLSocket)acceptor.accept();
        server.setUseClientMode(false);

        Future<Object> handshake = threadPool.submit(() ->
        {
            server.startHandshake();
            return null;
        });

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        Assert.assertNull(handshake.get(5, TimeUnit.SECONDS));

        // Read request
        InputStream serverInput = server.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(serverInput, StandardCharsets.UTF_8));
        String line = reader.readLine();
        Assert.assertTrue(line.startsWith("GET"));
        while (line.length() > 0)
            line = reader.readLine();

        OutputStream serverOutput = server.getOutputStream();
        byte[] data1 = new byte[1024];
        Arrays.fill(data1, (byte)'X');
        String content1 = new String(data1, StandardCharsets.UTF_8);
        byte[] data2 = new byte[1024];
        Arrays.fill(data2, (byte)'Y');
        final String content2 = new String(data2, StandardCharsets.UTF_8);
        // Write first part of the response
        serverOutput.write(("HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + (content1.length() + content2.length()) + "\r\n" +
                "\r\n" +
                content1).getBytes(StandardCharsets.UTF_8));
        serverOutput.flush();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        // Renegotiate
        threadPool.submit(() ->
        {
            server.startHandshake();
            return null;
        });

        // Renegotiation Handshake
        TLSRecord record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToClient(record);

        // Client sends close alert.
        record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.ALERT, record.getType());
        record = proxy.readFromClient();
        Assert.assertNull(record);

        server.close();
    }
}
