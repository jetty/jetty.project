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

package org.eclipse.jetty.websocket.tests.client;

import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.core.UpgradeException;
import org.eclipse.jetty.websocket.core.internal.WebSocketCore;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.LocalServer;
import org.eclipse.jetty.websocket.tests.TextMessageSocket;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Various connect condition testing
 */
public class ClientConnectTest
{
    private static final Logger LOG = Log.getLogger(ClientConnectTest.class);

    private ByteBufferPool bufferPool = new MappedByteBufferPool();
    private final int timeout = 500;
    private LocalServer server;
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

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.getHttpClient().setByteBufferPool(bufferPool);
        client.setConnectTimeout(timeout);
        client.start();
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new LocalServer();
        server.setStopTimeout(0);
        server.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        LOG.info("Ignore the stop thread warnings (this is expected for these tests)");
        server.stop();
    }

    @Test
    public void testUpgradeRequest(TestInfo testInfo) throws Exception
    {
        server.registerWebSocket("/simple", (req, resp) -> new TextMessageSocket("hello world"));

        TrackingEndpoint clientSocket = new TrackingEndpoint(testInfo.getTestMethod().toString());

        URI wsUri = server.getWsUri().resolve("/simple");
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

        Session sess = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        clientSocket.openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        assertThat("OnOpen.UpgradeRequest", clientSocket.openUpgradeRequest, notNullValue());
        assertThat("OnOpen.UpgradeResponse", clientSocket.openUpgradeResponse, notNullValue());

        sess.close();
    }

    @Test
    public void testUpgradeWithAuthorizationHeader(TestInfo testInfo) throws Exception
    {
        server.registerWebSocket("/auth-test", (upgradeRequest, upgradeResponse) ->
            new TextMessageSocket(upgradeRequest.getHeader("Authorization")));

        TrackingEndpoint clientSocket = new TrackingEndpoint(testInfo.getTestMethod().toString());
        URI wsUri = server.getWsUri().resolve("/auth-test");
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        // actual value for this test is irrelevant, its important that this
        // header actually be sent with a value (the value specified)
        upgradeRequest.setHeader("Authorization", "Bogus SHA1");
        Future<Session> future = client.connect(clientSocket, wsUri, upgradeRequest);

        Session clientSession = future.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        clientSession.close();

        String authLine = clientSocket.messageQueue.poll(1, TimeUnit.SECONDS);

        assertThat("Request Container Authorization", authLine, is("Bogus SHA1"));
        assertThat("OnOpen.UpgradeRequest", clientSocket.openUpgradeRequest, notNullValue());
        assertThat("OnOpen.UpgradeResponse", clientSocket.openUpgradeResponse, notNullValue());
    }

    @Test
    public void testBadHandshake(TestInfo testInfo) throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testInfo.getTestMethod().toString());
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
            fail("Expected ExecutionException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            assertExecutionException(e, instanceOf(UpgradeException.class),
                    containsString("Failed to upgrade to websocket: Unexpected HTTP Response Status Code: 404 Not Found"));
        }
    }

    @Test
    public void testBadHandshake_GetOK(TestInfo testInfo) throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testInfo.getTestMethod().toString());
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
            fail("Expected ExecutionException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            assertExecutionException(e, instanceOf(UpgradeException.class),
                    containsString("Failed to upgrade to websocket: Unexpected HTTP Response Status Code: 200 OK"));
        }
    }

    @Test
    public void testBadHandshake_GetOK_WithSecWebSocketAccept(TestInfo testInfo) throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testInfo.getTestMethod().toString());
        server.registerHttpService("/bad-accept-200", (req, resp) ->
        {
            // Simulate a bad server that doesn't follow RFC6455 completely.
            // A status 200 (not upgrade), but with some RFC6455 headers.
            resp.setStatus(200);
            String key = req.getHeader("Sec-WebSocket-Key");
            resp.setHeader("Sec-WebSocket-Accept", WebSocketCore.hashKey(key));
        });
        URI wsUri = server.getWsUri().resolve("/bad-accept-200");

        Future<Session> future = client.connect(clientSocket, wsUri);

        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            fail("Expected ExecutionException -> UpgradeException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            assertExecutionException(e, instanceOf(UpgradeException.class),
                    containsString("Failed to upgrade to websocket: Unexpected HTTP Response Status Code: 200 OK"));

        }
    }

    @Test
    public void testBadHandshake_SwitchingProtocols_InvalidConnectionHeader(TestInfo testInfo) throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testInfo.getTestMethod().toString());
        server.registerHttpService("/bad-connection-header", (req, resp) ->
        {
            // Simulate a bad server that doesn't follow RFC6455 completely.
            // A status 101 (switching protocol), but with "Connection: close"
            resp.setStatus(101);
            String key = req.getHeader("Sec-WebSocket-Key");
            resp.setHeader("Sec-WebSocket-Accept", WebSocketCore.hashKey(key));
            resp.setHeader("Connection", "close"); // Intentionally Invalid
        });
        URI wsUri = server.getWsUri().resolve("/bad-connection-header");
        Future<Session> future = client.connect(clientSocket, wsUri);

        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            fail("Expected ExecutionException -> UpgradeException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            assertExecutionException(e, instanceOf(HttpResponseException.class),
                    containsString("101 Switching Protocols without Connection: Upgrade not supported"));
        }
    }

    @Test
    public void testBadHandshake_SwitchingProtocols_NoConnectionHeader(TestInfo testInfo) throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testInfo.getTestMethod().toString());
        server.registerHttpService("/bad-switching-protocols-no-connection-header", (req, resp) ->
        {
            // Simulate a bad server that doesn't follow RFC6455 completely.
            // Send Switching Protocols 101, but no 'Connection' header
            resp.setStatus(101);
            String key = req.getHeader("Sec-WebSocket-Key");
            resp.setHeader("Sec-WebSocket-Accept", WebSocketCore.hashKey(key));
            // Intentionally leave out Connection header
        });
        URI wsUri = server.getWsUri().resolve("/bad-switching-protocols-no-connection-header");
        Future<Session> future = client.connect(clientSocket, wsUri);

        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            fail("Expected ExecutionException");
        }
        catch (ExecutionException e)
        {
            // Expected Path
            assertExecutionException(e, instanceOf(HttpResponseException.class),
                    containsString("101 Switching Protocols without Connection: Upgrade not supported"));
        }
    }

    @Test
    public void testBadHandshake_InvalidWsAccept(TestInfo testInfo) throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testInfo.getTestMethod().toString());
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
            fail("Expected ExecutionException");
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
    public void testHandshakeQuirk_TransferEncoding(TestInfo testInfo) throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testInfo.getTestMethod().toString());
        server.registerWebSocket("/quirk/tomcat", (upgradeRequest, upgradeResponse) ->
        {
            // Extra header that Tomcat 7.x returns
            upgradeResponse.addHeader("Transfer-Encoding", "chunked");
            return new TrackingEndpoint("tomcat-quirk");
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
    public void testConnection_Refused(TestInfo testInfo) throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testInfo.getTestMethod().toString());
        // This should be a ws:// uri to a machine that exists, but to a port
        // that isn't listening.
        // Intentionally bad port with nothing listening on it
        URI wsUri = new URI("ws://127.0.0.1:1");

        try
        {
            Future<Session> future = client.connect(clientSocket, wsUri);

            // The attempt to get upgrade response future should throw error
            future.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            fail("Expected ExecutionException");
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
    public void testConnectionTimeout_AcceptNoUpgradeResponse(TestInfo testInfo) throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testInfo.getTestMethod().toString());
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
            fail("Expected ExecutionException");
        }
        catch (ExecutionException e)
        {
            assertUpgradeException(e, instanceOf(java.util.concurrent.TimeoutException.class), containsString("timeout"));
        }
    }

}
