//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.common.AcceptHash;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Various connect condition testing
 */
@SuppressWarnings("Duplicates")
public class ClientConnectTest
{
    public ByteBufferPool bufferPool = new MappedByteBufferPool();

    private static BlockheadServer server;
    private WebSocketClient client;

    @SuppressWarnings("unchecked")
    private <E extends Throwable> E assertExpectedError(ExecutionException e, JettyTrackingSocket wsocket, Matcher<Throwable> errorMatcher)
    {
        // Validate thrown cause
        Throwable cause = e.getCause();
    
        assertThat("ExecutionException.cause",cause,errorMatcher);

        // Validate websocket captured cause
        assertThat("Error Queue Length",wsocket.errorQueue.size(),greaterThanOrEqualTo(1));
        Throwable capcause = wsocket.errorQueue.poll();
        assertThat("Error Queue[0]",capcause,notNullValue());
        assertThat("Error Queue[0]",capcause,errorMatcher);

        // Validate that websocket didn't see an open event
        wsocket.assertNotOpened();

        // Return the captured cause
        return (E)capcause;
    }

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.setBufferPool(bufferPool);
        client.setConnectTimeout(Timeouts.CONNECT_UNIT.toMillis(Timeouts.CONNECT));
        client.start();
    }

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new BlockheadServer();
        server.start();
    }

    @BeforeEach
    public void resetServerHandler()
    {
        // for each test, reset the server request handling to default
        server.resetRequestHandling();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testUpgradeRequest() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        Session sess = future.get(30,TimeUnit.SECONDS);
        
        wsocket.waitForConnected();
        
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
        try
        {
            httpClient.start();

            WebSocketUpgradeRequest req = new WebSocketUpgradeRequest(new WebSocketClient(), httpClient, wsUri, wsocket);
            req.header("X-Foo", "Req");
            CompletableFuture<Session> sess = req.sendAsync();

            sess.thenAccept((s) -> {
                System.out.printf("Session: %s%n", s);
                s.close();
                assertThat("Connect.UpgradeRequest", wsocket.connectUpgradeRequest, notNullValue());
                assertThat("Connect.UpgradeResponse", wsocket.connectUpgradeResponse, notNullValue());
            });
        }
        finally
        {
            httpClient.stop();
        }
    }
    
    @Test
    public void testUpgradeWithAuthorizationHeader() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        // Hook into server connection creation
        CompletableFuture<BlockheadConnection> serverConnFut = new CompletableFuture<>();
        server.addConnectFuture(serverConnFut);

        URI wsUri = server.getWsUri();
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        // actual value for this test is irrelevant, its important that this
        // header actually be sent with a value (the value specified)
        upgradeRequest.setHeader("Authorization", "Basic YWxhZGRpbjpvcGVuc2VzYW1l");
        Future<Session> future = client.connect(wsocket,wsUri,upgradeRequest);

        try (BlockheadConnection serverConn = serverConnFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            HttpFields upgradeRequestHeaders = serverConn.getUpgradeRequestHeaders();

            Session sess = future.get(30, TimeUnit.SECONDS);

            HttpField authHeader = upgradeRequestHeaders.getField(HttpHeader.AUTHORIZATION);
            assertThat("Server Request Authorization Header", authHeader, is(notNullValue()));
            assertThat("Server Request Authorization Value", authHeader.getValue(), is("Basic YWxhZGRpbjpvcGVuc2VzYW1l"));
            assertThat("Connect.UpgradeRequest", wsocket.connectUpgradeRequest, notNullValue());
            assertThat("Connect.UpgradeResponse", wsocket.connectUpgradeResponse, notNullValue());

            sess.close();
        }
    }
    
    @Test
    public void testBadHandshake() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        // Force 404 response, no upgrade for this test
        server.setRequestHandling((req, resp) -> {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return true;
        });

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        // The attempt to get upgrade response future should throw error
        ExecutionException e = assertThrows(ExecutionException.class,
                ()-> future.get(30,TimeUnit.SECONDS));

        UpgradeException ue = assertExpectedError(e,wsocket,instanceOf(UpgradeException.class));
        assertThat("UpgradeException.requestURI",ue.getRequestURI(),notNullValue());
        assertThat("UpgradeException.requestURI",ue.getRequestURI().toASCIIString(),is(wsUri.toASCIIString()));
        assertThat("UpgradeException.responseStatusCode",ue.getResponseStatusCode(),is(404));
    }

    @Test
    public void testBadHandshake_GetOK() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        // Force 200 response, no response body content, no upgrade for this test
        server.setRequestHandling((req, resp) -> {
            resp.setStatus(HttpServletResponse.SC_OK);
            return true;
        });

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        // The attempt to get upgrade response future should throw error
        ExecutionException e = assertThrows(ExecutionException.class,
                ()-> future.get(30,TimeUnit.SECONDS));

        UpgradeException ue = assertExpectedError(e,wsocket,instanceOf(UpgradeException.class));
        assertThat("UpgradeException.requestURI",ue.getRequestURI(),notNullValue());
        assertThat("UpgradeException.requestURI",ue.getRequestURI().toASCIIString(),is(wsUri.toASCIIString()));
        assertThat("UpgradeException.responseStatusCode",ue.getResponseStatusCode(),is(200));
    }

    @Test
    public void testBadHandshake_GetOK_WithSecWebSocketAccept() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        // Force 200 response, no response body content, incomplete websocket response headers, no actual upgrade for this test
        server.setRequestHandling((req, resp) -> {
            String key = req.getHeader(HttpHeader.SEC_WEBSOCKET_KEY.toString());
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setHeader(HttpHeader.SEC_WEBSOCKET_ACCEPT.toString(), AcceptHash.hashKey(key));
            return true;
        });

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        // The attempt to get upgrade response future should throw error
        ExecutionException e = assertThrows(ExecutionException.class,
                ()-> future.get(30,TimeUnit.SECONDS));

        UpgradeException ue = assertExpectedError(e,wsocket,instanceOf(UpgradeException.class));
        assertThat("UpgradeException.requestURI",ue.getRequestURI(),notNullValue());
        assertThat("UpgradeException.requestURI",ue.getRequestURI().toASCIIString(),is(wsUri.toASCIIString()));
        assertThat("UpgradeException.responseStatusCode",ue.getResponseStatusCode(),is(200));
    }

    @Test
    public void testBadHandshake_SwitchingProtocols_InvalidConnectionHeader() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        // Force 101 response, with invalid Connection header, invalid handshake
        server.setRequestHandling((req, resp) -> {
            String key = req.getHeader(HttpHeader.SEC_WEBSOCKET_KEY.toString());
            resp.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
            resp.setHeader(HttpHeader.CONNECTION.toString(), "close");
            resp.setHeader(HttpHeader.SEC_WEBSOCKET_ACCEPT.toString(), AcceptHash.hashKey(key));
            return true;
        });

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        // The attempt to get upgrade response future should throw error
        ExecutionException e = assertThrows(ExecutionException.class,
                ()-> future.get(30,TimeUnit.SECONDS));

        UpgradeException ue = assertExpectedError(e,wsocket,instanceOf(UpgradeException.class));
        assertThat("UpgradeException.requestURI",ue.getRequestURI(),notNullValue());
        assertThat("UpgradeException.requestURI",ue.getRequestURI().toASCIIString(),is(wsUri.toASCIIString()));
        assertThat("UpgradeException.responseStatusCode",ue.getResponseStatusCode(),is(101));
    }

    @Test
    public void testBadHandshake_SwitchingProtocols_NoConnectionHeader() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        // Force 101 response, with no Connection header, invalid handshake
        server.setRequestHandling((req, resp) -> {
            String key = req.getHeader(HttpHeader.SEC_WEBSOCKET_KEY.toString());
            resp.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
            // Intentionally leave out Connection header
            resp.setHeader(HttpHeader.SEC_WEBSOCKET_ACCEPT.toString(), AcceptHash.hashKey(key));
            return true;
        });

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        // The attempt to get upgrade response future should throw error
        ExecutionException e = assertThrows(ExecutionException.class,
                ()-> future.get(30,TimeUnit.SECONDS));

        UpgradeException ue = assertExpectedError(e,wsocket,instanceOf(UpgradeException.class));
        assertThat("UpgradeException.requestURI",ue.getRequestURI(),notNullValue());
        assertThat("UpgradeException.requestURI",ue.getRequestURI().toASCIIString(),is(wsUri.toASCIIString()));
        assertThat("UpgradeException.responseStatusCode",ue.getResponseStatusCode(),is(101));
    }

    @Test
    public void testBadUpgrade() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        // Force 101 response, with invalid response accept header
        server.setRequestHandling((req, resp) -> {
            resp.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
            resp.setHeader(HttpHeader.SEC_WEBSOCKET_ACCEPT.toString(), "rubbish");
            return true;
        });

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        // The attempt to get upgrade response future should throw error
        ExecutionException e = assertThrows(ExecutionException.class,
                ()-> future.get(30,TimeUnit.SECONDS));

        UpgradeException ue = assertExpectedError(e,wsocket,instanceOf(UpgradeException.class));
        assertThat("UpgradeException.requestURI",ue.getRequestURI(),notNullValue());
        assertThat("UpgradeException.requestURI",ue.getRequestURI().toASCIIString(),is(wsUri.toASCIIString()));
        assertThat("UpgradeException.responseStatusCode",ue.getResponseStatusCode(),is(101));
    }

    @Test
    public void testConnectionNotAccepted() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        try(ServerSocket serverSocket = new ServerSocket())
        {
            InetAddress addr = InetAddress.getByName("localhost");
            InetSocketAddress endpoint = new InetSocketAddress(addr, 0);
            serverSocket.bind(endpoint, 1);
            int port = serverSocket.getLocalPort();
            URI wsUri = URI.create(String.format("ws://%s:%d/", addr.getHostAddress(), port));
            Future<Session> future = client.connect(wsocket, wsUri);

            // Intentionally not accept incoming socket.
            // serverSocket.accept();

            try
            {
                future.get(3, TimeUnit.SECONDS);
                fail("Should have Timed Out");
            }
            catch (ExecutionException e)
            {
                assertExpectedError(e, wsocket, instanceOf(UpgradeException.class));
                // Possible Passing Path (active session wait timeout)
                wsocket.assertNotOpened();
            }
            catch (TimeoutException e)
            {
                // Possible Passing Path (concurrency timeout)
                wsocket.assertNotOpened();
            }
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
            fail("Expected ExecutionException -> ConnectException");
        }
        catch (ConnectException e)
        {
            Throwable t = wsocket.errorQueue.remove();
            assertThat("Error Queue[0]",t,instanceOf(ConnectException.class));
            wsocket.assertNotOpened();
        }
        catch (ExecutionException e)
        {
            assertExpectedError(e, wsocket,
                    anyOf(
                            instanceOf(UpgradeException.class),
                            instanceOf(SocketTimeoutException.class),
                            instanceOf(ConnectException.class)));
        }
    }

    @Test
    public void testConnectionTimeout_Concurrent() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        try(ServerSocket serverSocket = new ServerSocket())
        {
            InetAddress addr = InetAddress.getByName("localhost");
            InetSocketAddress endpoint = new InetSocketAddress(addr, 0);
            serverSocket.bind(endpoint, 1);
            int port = serverSocket.getLocalPort();
            URI wsUri = URI.create(String.format("ws://%s:%d/", addr.getHostAddress(), port));
            Future<Session> future = client.connect(wsocket, wsUri);

            // Accept the connection, but do nothing on it (no response, no upgrade, etc)
            serverSocket.accept();

            // The attempt to get upgrade response future should throw error
            Exception e = assertThrows(Exception.class,
                    ()-> future.get(3, TimeUnit.SECONDS));

            if (e instanceof ExecutionException)
            {
                assertExpectedError((ExecutionException) e, wsocket, anyOf(
                        instanceOf(ConnectException.class),
                        instanceOf(UpgradeException.class)
                ));
            }
            else
            {
                assertThat("Should have been a TimeoutException", e, instanceOf(TimeoutException.class));
            }
        }
    }
}
