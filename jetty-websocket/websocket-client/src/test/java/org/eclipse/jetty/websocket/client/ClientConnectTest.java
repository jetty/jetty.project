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

package org.eclipse.jetty.websocket.client;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.common.AcceptHash;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.IBlockheadServerConnection;
import org.eclipse.jetty.websocket.common.test.LeakTrackingBufferPoolRule;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Various connect condition testing
 */
public class ClientConnectTest
{
    @Rule
    public TestTracker tt = new TestTracker();

    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("Test");

    private final int timeout = 500;
    private BlockheadServer server;
    private WebSocketClient client;

    @SuppressWarnings("unchecked")
    private <E extends Throwable> E assertExpectedError(ExecutionException e, JettyTrackingSocket wsocket, Class<E> errorClass) throws IOException
    {
        // Validate thrown cause
        Throwable cause = e.getCause();
        if(!errorClass.isInstance(cause)) 
        {
                cause.printStackTrace(System.err);
                Assert.assertThat("ExecutionException.cause",cause,instanceOf(errorClass));
        }

        // Validate websocket captured cause
        Assert.assertThat("Error Queue Length",wsocket.errorQueue.size(),greaterThanOrEqualTo(1));
        Throwable capcause = wsocket.errorQueue.poll();
        Assert.assertThat("Error Queue[0]",capcause,notNullValue());
        Assert.assertThat("Error Queue[0]",capcause,instanceOf(errorClass));

        // Validate that websocket didn't see an open event
        wsocket.assertNotOpened();

        // Return the captured cause
        return (E)capcause;
    }

    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.setBufferPool(bufferPool);
        client.setConnectTimeout(timeout);
        client.start();
    }

    @Before
    public void startServer() throws Exception
    {
        server = new BlockheadServer();
        server.start();
    }

    @After
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testUpgradeRequest() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        IBlockheadServerConnection connection = server.accept();
        connection.upgrade();

        Session sess = future.get(30,TimeUnit.SECONDS);
        
        wsocket.waitForConnected(1, TimeUnit.SECONDS);
        
        assertThat("Connect.UpgradeRequest", wsocket.connectUpgradeRequest, notNullValue());
        assertThat("Connect.UpgradeResponse", wsocket.connectUpgradeResponse, notNullValue());
        
        sess.close();
    }
    
    @Test
    public void testAltConnect() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();
        URI wsUri = server.getWsUri();
        
        HttpClient httpClient = new HttpClient();
        httpClient.start();
        
        WebSocketUpgradeRequest req = new WebSocketUpgradeRequest(new WebSocketClient(), httpClient, wsUri, wsocket);
        req.header("X-Foo","Req");
        CompletableFuture<Session> sess = req.sendAsync();

        sess.thenAccept((s) -> {
            System.out.printf("Session: %s%n",s);
            s.close();
            assertThat("Connect.UpgradeRequest",wsocket.connectUpgradeRequest,notNullValue());
            assertThat("Connect.UpgradeResponse",wsocket.connectUpgradeResponse,notNullValue());
        });
        
        IBlockheadServerConnection connection = server.accept();
        connection.upgrade();
    }

    @Test
    public void testBadHandshake() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        IBlockheadServerConnection connection = server.accept();
        connection.readRequest();
        // no upgrade, just fail with a 404 error
        connection.respond("HTTP/1.1 404 NOT FOUND\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n");

        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(30,TimeUnit.SECONDS);
            Assert.fail("Expected ExecutionException -> UpgradeException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            UpgradeException ue = assertExpectedError(e,wsocket,UpgradeException.class);
            Assert.assertThat("UpgradeException.requestURI",ue.getRequestURI(),notNullValue());
            Assert.assertThat("UpgradeException.requestURI",ue.getRequestURI().toASCIIString(),is(wsUri.toASCIIString()));
            Assert.assertThat("UpgradeException.responseStatusCode",ue.getResponseStatusCode(),is(404));
        }
    }

    @Test
    public void testBadHandshake_GetOK() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        IBlockheadServerConnection connection = server.accept();
        connection.readRequest();
        // Send OK to GET but not upgrade
        connection.respond("HTTP/1.1 200 OK\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n");

        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(30,TimeUnit.SECONDS);
            Assert.fail("Expected ExecutionException -> UpgradeException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            UpgradeException ue = assertExpectedError(e,wsocket,UpgradeException.class);
            Assert.assertThat("UpgradeException.requestURI",ue.getRequestURI(),notNullValue());
            Assert.assertThat("UpgradeException.requestURI",ue.getRequestURI().toASCIIString(),is(wsUri.toASCIIString()));
            Assert.assertThat("UpgradeException.responseStatusCode",ue.getResponseStatusCode(),is(200));
        }
    }

    @Test
    public void testBadHandshake_GetOK_WithSecWebSocketAccept() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        IBlockheadServerConnection connection = server.accept();
        List<String> requestLines = connection.readRequestLines();
        String key = connection.parseWebSocketKey(requestLines);

        // Send OK to GET but not upgrade
        StringBuilder resp = new StringBuilder();
        resp.append("HTTP/1.1 200 OK\r\n"); // intentionally 200 (not 101)
        // Include a value accept key
        resp.append("Sec-WebSocket-Accept: ").append(AcceptHash.hashKey(key)).append("\r\n");
        resp.append("Content-Length: 0\r\n");
        resp.append("\r\n");
        connection.respond(resp.toString());

        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(30,TimeUnit.SECONDS);
            Assert.fail("Expected ExecutionException -> UpgradeException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            UpgradeException ue = assertExpectedError(e,wsocket,UpgradeException.class);
            Assert.assertThat("UpgradeException.requestURI",ue.getRequestURI(),notNullValue());
            Assert.assertThat("UpgradeException.requestURI",ue.getRequestURI().toASCIIString(),is(wsUri.toASCIIString()));
            Assert.assertThat("UpgradeException.responseStatusCode",ue.getResponseStatusCode(),is(200));
        }
    }

    @Test
    public void testBadHandshake_SwitchingProtocols_InvalidConnectionHeader() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        IBlockheadServerConnection connection = server.accept();
        List<String> requestLines = connection.readRequestLines();
        String key = connection.parseWebSocketKey(requestLines);

        // Send Switching Protocols 101, but invalid 'Connection' header
        StringBuilder resp = new StringBuilder();
        resp.append("HTTP/1.1 101 Switching Protocols\r\n");
        resp.append("Sec-WebSocket-Accept: ").append(AcceptHash.hashKey(key)).append("\r\n");
        resp.append("Connection: close\r\n");
        resp.append("\r\n");
        connection.respond(resp.toString());

        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(30,TimeUnit.SECONDS);
            Assert.fail("Expected ExecutionException -> UpgradeException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            UpgradeException ue = assertExpectedError(e,wsocket,UpgradeException.class);
            Assert.assertThat("UpgradeException.requestURI",ue.getRequestURI(),notNullValue());
            Assert.assertThat("UpgradeException.requestURI",ue.getRequestURI().toASCIIString(),is(wsUri.toASCIIString()));
            Assert.assertThat("UpgradeException.responseStatusCode",ue.getResponseStatusCode(),is(101));
        }
    }

    @Test
    public void testBadHandshake_SwitchingProtocols_NoConnectionHeader() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        IBlockheadServerConnection connection = server.accept();
        List<String> requestLines = connection.readRequestLines();
        String key = connection.parseWebSocketKey(requestLines);

        // Send Switching Protocols 101, but no 'Connection' header
        StringBuilder resp = new StringBuilder();
        resp.append("HTTP/1.1 101 Switching Protocols\r\n");
        resp.append("Sec-WebSocket-Accept: ").append(AcceptHash.hashKey(key)).append("\r\n");
        // Intentionally leave out Connection header
        resp.append("\r\n");
        connection.respond(resp.toString());

        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(30,TimeUnit.SECONDS);
            Assert.fail("Expected ExecutionException -> UpgradeException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            UpgradeException ue = assertExpectedError(e,wsocket,UpgradeException.class);
            Assert.assertThat("UpgradeException.requestURI",ue.getRequestURI(),notNullValue());
            Assert.assertThat("UpgradeException.requestURI",ue.getRequestURI().toASCIIString(),is(wsUri.toASCIIString()));
            Assert.assertThat("UpgradeException.responseStatusCode",ue.getResponseStatusCode(),is(101));
        }
    }

    @Test
    public void testBadUpgrade() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        IBlockheadServerConnection connection = server.accept();
        connection.readRequest();
        // Upgrade badly
        connection.respond("HTTP/1.1 101 Upgrade\r\n" + "Sec-WebSocket-Accept: rubbish\r\n" + "\r\n");

        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(30,TimeUnit.SECONDS);
            Assert.fail("Expected ExecutionException -> UpgradeException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            UpgradeException ue = assertExpectedError(e,wsocket,UpgradeException.class);
            Assert.assertThat("UpgradeException.requestURI",ue.getRequestURI(),notNullValue());
            Assert.assertThat("UpgradeException.requestURI",ue.getRequestURI().toASCIIString(),is(wsUri.toASCIIString()));
            Assert.assertThat("UpgradeException.responseStatusCode",ue.getResponseStatusCode(),is(101));
        }
    }

    @Test
    public void testConnectionNotAccepted() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        // Intentionally not accept incoming socket.
        // server.accept();

        try
        {
            future.get(3,TimeUnit.SECONDS);
            Assert.fail("Should have Timed Out");
        }
        catch (ExecutionException e)
        {
            assertExpectedError(e,wsocket,UpgradeException.class);
            // Possible Passing Path (active session wait timeout)
            wsocket.assertNotOpened();
        }
        catch (TimeoutException e)
        {
            // Possible Passing Path (concurrency timeout)
            wsocket.assertNotOpened();
        }
    }

    @Test
    public void testConnectionRefused() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        // Intentionally bad port with nothing listening on it
        URI wsUri = new URI("ws://127.0.0.1:1");

        try
        {
            Future<Session> future = client.connect(wsocket,wsUri);

            // The attempt to get upgrade response future should throw error
            future.get(3,TimeUnit.SECONDS);
            Assert.fail("Expected ExecutionException -> ConnectException");
        }
        catch (ConnectException e)
        {
            Throwable t = wsocket.errorQueue.remove();
            Assert.assertThat("Error Queue[0]",t,instanceOf(ConnectException.class));
            wsocket.assertNotOpened();
        }
        catch (ExecutionException e)
        {
                if(OS.IS_WINDOWS) 
                {
                        // On windows, this is a SocketTimeoutException
                        assertExpectedError(e, wsocket, SocketTimeoutException.class);
                } else
                {
                    // Expected path - java.net.ConnectException
                    assertExpectedError(e,wsocket,ConnectException.class);
                }
        }
    }

    @Test(expected = TimeoutException.class)
    public void testConnectionTimeout_Concurrent() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        IBlockheadServerConnection ssocket = server.accept();
        Assert.assertNotNull(ssocket);
        // Intentionally don't upgrade
        // ssocket.upgrade();

        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(3,TimeUnit.SECONDS);
            Assert.fail("Expected ExecutionException -> TimeoutException");
        }
        catch (ExecutionException e)
        {
            // Expected path - java.net.ConnectException ?
            assertExpectedError(e,wsocket,ConnectException.class);
        }
    }
}
