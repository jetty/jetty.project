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

import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.client.WebSocketUpgradeRequest;
import org.eclipse.jetty.websocket.core.handshake.AcceptHash;
import org.eclipse.jetty.websocket.core.UpgradeException;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSServer;
import org.eclipse.jetty.websocket.tests.UntrustedWSSession;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 * Various connect condition testing
 */
public class ClientConnectTest
{
    private static final Logger LOG = Log.getLogger(ClientConnectTest.class);
    
    @Rule
    public TestName testname = new TestName();
    
    private ByteBufferPool bufferPool = new MappedByteBufferPool();
    private final int timeout = 500;
    private UntrustedWSServer server;
    private WebSocketClient client;
    
    private void assertExecutionException(ExecutionException actualException, Matcher<Throwable> exceptionCauseMatcher, Matcher<String> messageMatcher)
    {
        Throwable cause = actualException.getCause();
        assertThat("ExecutionException cause", cause, exceptionCauseMatcher);
        assertThat("ExecutionException message", cause.getMessage(), messageMatcher);
    }
    
    private void assertUpgradeException(ExecutionException actualException, Matcher<Throwable> upgradeExceptionCauseMatcher, Matcher<String> messageMatcher)
    {
        Throwable cause = actualException.getCause();
        assertThat("ExecutionException cause", cause, instanceOf(UpgradeException.class));
        Throwable actualCause = cause.getCause();
        assertThat("UpgradeException cause", actualCause, upgradeExceptionCauseMatcher);
        assertThat("UpgradeException message", actualCause.getMessage(), messageMatcher);
    }

    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.getHttpClient().setByteBufferPool(bufferPool);
        client.setConnectTimeout(timeout);
        client.start();
    }
    
    @Before
    public void startServer() throws Exception
    {
        server = new UntrustedWSServer();
        server.setStopTimeout(0);
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
        LOG.info("Ignore the stop thread warnings (this is expected for these tests)");
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
        
        WebSocketUpgradeRequest req = new WebSocketUpgradeRequest(new WebSocketClient(), wsUri);
        req.header("X-Foo", "Req");
        req.setWebSocket(clientSocket);
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
        
        assertThat("Request Container Authorization", authLine, is("Bogus SHA1"));
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
            Assert.fail("Expected ExecutionException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            assertExecutionException(e, instanceOf(HttpResponseException.class),
                    containsString("Not a 101 Switching Protocols Response: 404 Not Found"));
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
            Assert.fail("Expected ExecutionException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            assertExecutionException(e, instanceOf(HttpResponseException.class),
                    containsString("Not a 101 Switching Protocols Response: 200 OK"));
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
            assertExecutionException(e, instanceOf(HttpResponseException.class),
                    containsString("Not a 101 Switching Protocols Response: 200 OK"));
    
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
            resp.setHeader("Connection", "close"); // Intentionally Invalid
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
            assertExecutionException(e, instanceOf(HttpResponseException.class),
                    containsString("101 Switching Protocols without Connection: Upgrade not supported"));
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
            Assert.fail("Expected ExecutionException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            assertExecutionException(e, instanceOf(HttpResponseException.class),
                    containsString("101 Switching Protocols without Connection: Upgrade not supported"));
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
            resp.setHeader("Sec-WebSocket-Accept", "rubbish"); // Intentionally Invalid
            resp.setHeader("Connection", "Upgrade");
            resp.setHeader("Upgrade", "WebSocket");
        });
        URI wsUri = server.getWsUri().resolve("/bad-switching-protocols-invalid-ws-accept");
        Future<Session> future = client.connect(clientSocket, wsUri);
        
        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Assert.fail("Expected ExecutionException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            assertExecutionException(e, instanceOf(HttpResponseException.class),
                    containsString("Invalid Sec-WebSocket-Accept hash"));
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
        
        assertThat("Client dropped Transfer-Encoding header",
                clientSession.getUpgradeResponse().getHeader("Transfer-Encoding"),
                nullValue());
        
        assertThat("Client onOpen event occurred",
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
            Assert.fail("Expected ExecutionException");
        }
        catch (ExecutionException e)
        {
            assertExecutionException(e,
                    anyOf(
                            instanceOf(java.net.SocketTimeoutException.class), // seen on windows
                            instanceOf(java.net.ConnectException.class) // seen everywhere else
                    ),
                    anyOf(
                            containsString("Connect"),
                            containsString("Timeout")
                    )
            );
        }
    }
    
    @Test
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
                TimeUnit.SECONDS.sleep(5);
            }
            catch (InterruptedException ignore)
            {
            }
        });
        URI wsUri = server.getWsUri().resolve("/accept-no-upgrade-timeout");
        client.setMaxIdleTimeout(500); // we do connect, just sit idle for the upgrade step
        Future<Session> future = client.connect(clientSocket, wsUri);
        
        try
        {
            // The attempt to get upgrade response future should throw error
            future.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Assert.fail("Expected ExecutionException");
        }
        catch (ExecutionException e)
        {
            assertUpgradeException(e, instanceOf(java.util.concurrent.TimeoutException.class), containsString("timeout"));
        }
    }
    
}
