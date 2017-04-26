//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.client;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.client.WebSocketUpgradeRequest;
import org.eclipse.jetty.websocket.common.AcceptHash;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.LeakTrackingBufferPoolRule;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSServer;
import org.eclipse.jetty.websocket.tests.UntrustedWSSession;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 * Various connect condition testing
 */
@SuppressWarnings("Duplicates")
public class ClientConnectTest
{
    @Rule
    public TestName testname = new TestName();
    
    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("Test");
    
    private final int timeout = 500;
    private UntrustedWSServer server;
    private WebSocketClient client;
    
    @SuppressWarnings("unchecked")
    private <E extends Throwable> E assertExpectedError(Throwable t, TrackingEndpoint clientSocket, Class<E> errorClass) throws IOException
    {
        // Validate thrown cause
        Throwable cause = t;
        
        while (cause instanceof ExecutionException)
        {
            cause = cause.getCause();
        }
        
        Assert.assertThat("Cause", cause, instanceOf(errorClass));
        
        if (clientSocket.session != null)
        {
            // Validate websocket captured cause
            Throwable clientCause = clientSocket.error.get();
            Assert.assertThat("Client Error", clientCause, notNullValue());
            Assert.assertThat("Client Error", clientCause, instanceOf(errorClass));
            
            // Validate that websocket didn't see an open event
            assertThat("Client socket isOpen", clientSocket.session.isOpen(), is(false));
            
            // Return the captured cause
            return (E) clientCause;
        }
        else
        {
            return (E) cause;
        }
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
        server = new UntrustedWSServer();
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
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);
        
        Session sess = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        
        clientSocket.openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        
        assertThat("OnOpen.UpgradeRequest", clientSocket.openUpgradeRequest, notNullValue());
        assertThat("OnOpen.UpgradeResponse", clientSocket.openUpgradeResponse, notNullValue());
        
        sess.close();
    }
    
    @Test
    public void testAltConnect() throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
        
        HttpClient httpClient = new HttpClient();
        httpClient.start();
        
        WebSocketUpgradeRequest req = new WebSocketUpgradeRequest(new WebSocketClient(), httpClient, wsUri, clientSocket);
        req.header("X-Foo", "Req");
        CompletableFuture<Session> sess = req.sendAsync();
        
        sess.thenAccept((s) ->
        {
            System.out.printf("Session: %s%n", s);
            s.close();
            assertThat("OnOpen.UpgradeRequest", clientSocket.openUpgradeRequest, notNullValue());
            assertThat("OnOpen.UpgradeResponse", clientSocket.openUpgradeResponse, notNullValue());
        });
    }
    
    @Test
    public void testUpgradeWithAuthorizationHeader() throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
        CompletableFuture<UntrustedWSSession> serverSessionFut = new CompletableFuture<UntrustedWSSession>()
        {
            @Override
            public boolean complete(UntrustedWSSession session)
            {
                // echo back text as-well
                session.getUntrustedEndpoint().setOnTextFunction((serverSession, text) -> text);
                return super.complete(session);
            }
        };
        server.registerOnOpenFuture(wsUri, serverSessionFut);
        
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        // actual value for this test is irrelevant, its important that this
        // header actually be sent with a value (the value specified)
        upgradeRequest.setHeader("Authorization", "Bogus SHA1");
        Future<Session> future = client.connect(clientSocket, wsUri, upgradeRequest);
        
        Session clientSession = future.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        clientSession.close();
        
        UntrustedWSSession serverSession = serverSessionFut.get(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        
        String authLine = serverSession.getUntrustedEndpoint().openUpgradeRequest.getHeader("Authorization");
        
        assertThat("Request Container Authorization", authLine, is("Authorization: Bogus SHA1"));
        assertThat("OnOpen.UpgradeRequest", clientSocket.openUpgradeRequest, notNullValue());
        assertThat("OnOpen.UpgradeResponse", clientSocket.openUpgradeResponse, notNullValue());
    }
    
    @Test
    public void testBadHandshake() throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        server.registerHttpService("/empty-404", (req, resp) ->
        {
            resp.setStatus(404);
            resp.setHeader("Connection", "close");
        });
        URI wsUri = server.getWsUri().resolve("/empty-404");
        Future<Session> future = client.connect(clientSocket, wsUri);
        
        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Assert.fail("Expected ExecutionException -> UpgradeException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            UpgradeException ue = assertExpectedError(e, clientSocket, UpgradeException.class);
            Assert.assertThat("UpgradeException.requestURI", ue.getRequestURI(), notNullValue());
            Assert.assertThat("UpgradeException.requestURI", ue.getRequestURI().toASCIIString(), is(wsUri.toASCIIString()));
            Assert.assertThat("UpgradeException.responseStatusCode", ue.getResponseStatusCode(), is(404));
        }
    }
    
    @Test
    public void testBadHandshake_GetOK() throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        server.registerHttpService("/empty-200", (req, resp) ->
        {
            resp.setStatus(200);
            resp.setHeader("Connection", "close");
        });
        URI wsUri = server.getWsUri().resolve("/empty-200");
        
        Future<Session> future = client.connect(clientSocket, wsUri);
        
        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Assert.fail("Expected ExecutionException -> UpgradeException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            UpgradeException ue = assertExpectedError(e, clientSocket, UpgradeException.class);
            Assert.assertThat("UpgradeException.requestURI", ue.getRequestURI(), notNullValue());
            Assert.assertThat("UpgradeException.requestURI", ue.getRequestURI().toASCIIString(), is(wsUri.toASCIIString()));
            Assert.assertThat("UpgradeException.responseStatusCode", ue.getResponseStatusCode(), is(200));
        }
    }
    
    @Test
    public void testBadHandshake_GetOK_WithSecWebSocketAccept() throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        server.registerHttpService("/bad-accept-200", (req, resp) ->
        {
            // Simulate a bad server that doesn't follow RFC6455 completely.
            // A status 200 (not upgrade), but with some RFC6455 headers.
            resp.setStatus(200);
            String key = req.getHeader("Sec-WebSocket-Key");
            resp.setHeader("Sec-WebSocket-Accept", AcceptHash.hashKey(key));
        });
        URI wsUri = server.getWsUri().resolve("/bad-accept-200");
        
        Future<Session> future = client.connect(clientSocket, wsUri);
        
        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Assert.fail("Expected ExecutionException -> UpgradeException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            UpgradeException ue = assertExpectedError(e, clientSocket, UpgradeException.class);
            Assert.assertThat("UpgradeException.requestURI", ue.getRequestURI(), notNullValue());
            Assert.assertThat("UpgradeException.requestURI", ue.getRequestURI().toASCIIString(), is(wsUri.toASCIIString()));
            Assert.assertThat("UpgradeException.responseStatusCode", ue.getResponseStatusCode(), is(200));
        }
    }
    
    @Test
    public void testBadHandshake_SwitchingProtocols_InvalidConnectionHeader() throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        server.registerHttpService("/bad-connection-header", (req, resp) ->
        {
            // Simulate a bad server that doesn't follow RFC6455 completely.
            // A status 101 (switching protocol), but with "Connection: close"
            resp.setStatus(101);
            String key = req.getHeader("Sec-WebSocket-Key");
            resp.setHeader("Sec-WebSocket-Accept", AcceptHash.hashKey(key));
            resp.setHeader("Connection", "close");
        });
        URI wsUri = server.getWsUri().resolve("/bad-connection-header");
        Future<Session> future = client.connect(clientSocket, wsUri);
        
        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Assert.fail("Expected ExecutionException -> UpgradeException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            UpgradeException ue = assertExpectedError(e, clientSocket, UpgradeException.class);
            Assert.assertThat("UpgradeException.requestURI", ue.getRequestURI(), notNullValue());
            Assert.assertThat("UpgradeException.requestURI", ue.getRequestURI().toASCIIString(), is(wsUri.toASCIIString()));
            Assert.assertThat("UpgradeException.responseStatusCode", ue.getResponseStatusCode(), is(101));
        }
    }
    
    @Test
    public void testBadHandshake_SwitchingProtocols_NoConnectionHeader() throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        server.registerHttpService("/bad-switching-protocols-no-connection-header", (req, resp) ->
        {
            // Simulate a bad server that doesn't follow RFC6455 completely.
            // Send Switching Protocols 101, but no 'Connection' header
            resp.setStatus(101);
            String key = req.getHeader("Sec-WebSocket-Key");
            resp.setHeader("Sec-WebSocket-Accept", AcceptHash.hashKey(key));
            // Intentionally leave out Connection header
        });
        URI wsUri = server.getWsUri().resolve("/bad-switching-protocols-no-connection-header");
        Future<Session> future = client.connect(clientSocket, wsUri);
        
        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Assert.fail("Expected ExecutionException -> UpgradeException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            UpgradeException ue = assertExpectedError(e, clientSocket, UpgradeException.class);
            Assert.assertThat("UpgradeException.requestURI", ue.getRequestURI(), notNullValue());
            Assert.assertThat("UpgradeException.requestURI", ue.getRequestURI().toASCIIString(), is(wsUri.toASCIIString()));
            Assert.assertThat("UpgradeException.responseStatusCode", ue.getResponseStatusCode(), is(101));
        }
    }
    
    @Test
    public void testBadHandshake_InvalidWsAccept() throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        server.registerHttpService("/bad-switching-protocols-invalid-ws-accept", (req, resp) ->
        {
            // Simulate a bad server that doesn't follow RFC6455 completely.
            // Send Switching Protocols 101, with bad Sec-WebSocket-Accept header
            resp.setStatus(101);
            resp.setHeader("Sec-WebSocket-Accept", "rubbish");
        });
        URI wsUri = server.getWsUri().resolve("/bad-switching-protocols-invalid-ws-accept");
        Future<Session> future = client.connect(clientSocket, wsUri);
        
        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Assert.fail("Expected ExecutionException -> UpgradeException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            UpgradeException ue = assertExpectedError(e, clientSocket, UpgradeException.class);
            Assert.assertThat("UpgradeException.requestURI", ue.getRequestURI(), notNullValue());
            Assert.assertThat("UpgradeException.requestURI", ue.getRequestURI().toASCIIString(), is(wsUri.toASCIIString()));
            Assert.assertThat("UpgradeException.responseStatusCode", ue.getResponseStatusCode(), is(101));
        }
    }
    
    /**
     * Test for when encountering a "Transfer-Encoding: chunked" on a Upgrade Response header.
     * <ul>
     * <li><a href="https://bugs.eclipse.org/bugs/show_bug.cgi?id=393075">Eclipse Jetty Bug #393075</a></li>
     * <li><a href="https://issues.apache.org/bugzilla/show_bug.cgi?id=54067">Apache Tomcat Bug #54067</a></li>
     * </ul>
     *
     * @throws Exception on test failure
     */
    @Test
    public void testHandshakeQuirk_TransferEncoding() throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        server.registerWebSocket("/quirk/tomcat", (upgradeRequest, upgradeResponse) ->
        {
            // Extra header that Tomcat 7.x returns
            upgradeResponse.addHeader("Transfer-Encoding", "chunked");
            return new UntrustedWSEndpoint("tomcat-quirk");
        });
        URI wsUri = server.getWsUri().resolve("/quirk/tomcat");
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);
        
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat("Client saw Transfer-Encoding header",
                clientSession.getUpgradeResponse().getHeader("Transfer-Encoding"),
                is("chunked"));
        
        assertThat("Client open event occurred",
                clientSocket.openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                is(true));
        clientSession.close();
    }
    
    @Test
    public void testConnection_Refused() throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        // This should be a ws:// uri to a machine that exists, but to a port
        // that isn't listening.
        // Intentionally bad port with nothing listening on it
        URI wsUri = new URI("ws://127.0.0.1:1");
        
        try
        {
            Future<Session> future = client.connect(clientSocket, wsUri);
            
            // The attempt to get upgrade response future should throw error
            future.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Assert.fail("Expected ExecutionException -> ConnectException");
        }
        catch (ConnectException e)
        {
            assertExpectedError(e, clientSocket, ConnectException.class);
        }
        catch (ExecutionException e)
        {
            if (OS.IS_WINDOWS)
            {
                // On windows, this is a SocketTimeoutException
                assertExpectedError(e, clientSocket, SocketTimeoutException.class);
            }
            else
            {
                // Expected path - java.net.ConnectException
                assertExpectedError(e, clientSocket, ConnectException.class);
            }
        }
    }
    
    @Test(expected = TimeoutException.class)
    public void testConnectionTimeout_AcceptNoUpgradeResponse() throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        server.registerHttpService("/accept-no-upgrade-timeout", (req, resp) ->
        {
            // Intentionally take a long time here
            // This simulates a server that accepts the request, but doesn't send
            // any response (either at all, or in a timely manner)
            try
            {
                TimeUnit.MICROSECONDS.sleep(5);
            }
            catch (InterruptedException ignore)
            {
            }
        });
        URI wsUri = server.getWsUri().resolve("/accept-no-upgrade-timeout");
        Future<Session> future = client.connect(clientSocket, wsUri);
        
        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Assert.fail("Expected ExecutionException -> TimeoutException");
        }
        catch (ExecutionException e)
        {
            // Expected path - java.net.ConnectException
            assertExpectedError(e, clientSocket, ConnectException.class);
        }
    }
}
