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

import static org.hamcrest.CoreMatchers.anything;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.WebSocketTimeoutException;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TestName;

/**
 * Tests various early disconnected connection situations
 */
public class ClientDisconnectedTest
{
    /**
     * On Open, close socket
     */
    @WebSocket
    public static class OpenDropSocket
    {
        private static final Logger LOG = Log.getLogger(OpenDropSocket.class);

        @OnWebSocketConnect
        public void onOpen(Session sess)
        {
            LOG.debug("onOpen({})", sess);
            try
            {
                sess.disconnect();
            }
            catch (IOException ignore)
            {
            }
        }
    }

    /**
     * On Open, throw unhandled exception
     */
    @WebSocket
    public static class OpenFailSocket
    {
        private static final Logger LOG = Log.getLogger(OpenFailSocket.class);

        @OnWebSocketConnect
        public void onOpen(Session sess)
        {
            LOG.debug("onOpen({})", sess);
            // Test failure due to unhandled exception
            // this should trigger a fast-fail closure during open/connect
            throw new RuntimeException("Intentional FastFail");
        }
    }

    /**
     * On Message, drop connection
     */
    public static class MessageDropSocket extends WebSocketAdapter
    {
        private static final Logger LOG = Log.getLogger(MessageDropSocket.class);

        @Override
        public void onWebSocketText(String message)
        {
            LOG.debug("onWebSocketText({})", message);
            try
            {
                getSession().disconnect();
            }
            catch (IOException ignore)
            {
            }
        }
    }

    /**
     * On Close, drop connection
     */
    @WebSocket
    public static class CloseDropSocket
    {
        private static final Logger LOG = Log.getLogger(CloseDropSocket.class);

        @OnWebSocketClose
        public void onClose(Session session)
        {
            LOG.debug("onClose({})", session);
            try
            {
                session.disconnect();
            }
            catch (IOException ignore)
            {
            }
        }
    }

    /**
     * On Close, no reply
     */
    @WebSocket
    public static class CloseNoReplySocket
    {
        private static final Logger LOG = Log.getLogger(CloseDropSocket.class);

        @OnWebSocketClose
        public void onClose(Session session)
        {
            LOG.debug("onClose({})", session);
            try
            {
                // Take too long to reply
                // The client should see an idle timeout (no reply from server)
                TimeUnit.SECONDS.sleep(5);
            }
            catch (InterruptedException ignore)
            {
            }
        }
    }

    public static class EarlyCloseServlet extends WebSocketServlet implements WebSocketCreator
    {
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.setCreator(this);
        }

        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            if (req.hasSubProtocol("opendrop"))
            {
                resp.setAcceptedSubProtocol("opendrop");
                return new OpenDropSocket();
            }

            if (req.hasSubProtocol("openfail"))
            {
                resp.setAcceptedSubProtocol("openfail");
                return new OpenFailSocket();
            }

            if (req.hasSubProtocol("msgdrop"))
            {
                resp.setAcceptedSubProtocol("msgdrop");
                return new MessageDropSocket();
            }

            if (req.hasSubProtocol("closedrop"))
            {
                resp.setAcceptedSubProtocol("closedrop");
                return new CloseDropSocket();
            }

            if (req.hasSubProtocol("closenoreply"))
            {
                resp.setAcceptedSubProtocol("closenoreply");
                return new CloseDropSocket();
            }

            return null;
        }
    }

    @Rule
    public TestName testname = new TestName();

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    private SimpleServletServer server;
    private WebSocketClient client;

    @Before
    public void startServer() throws Exception
    {
        server = new SimpleServletServer(new EarlyCloseServlet());
        server.start();
    }

    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.start();
    }

    @After
    public void stopClient() throws Exception
    {
        client.stop();
    }

    /**
     * The remote endpoint sends a close frame immediately.
     *
     * @throws Exception on test failure
     */
    @Test
    public void immediateDrop() throws Exception
    {
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("openclose");

        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());

        URI wsUri = server.getServerUri().resolve("/");
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);

        expectedException.expect(ExecutionException.class);
        expectedException.expectCause(instanceOf(HttpResponseException.class));
        expectedException.expectMessage(containsString("503 Endpoint Creation Failed"));
        clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * The remote endpoint performed upgrade handshake ok, but failed its onOpen.
     *
     * @throws Exception on test failure
     */
    @Test
    public void remoteOpenFailure() throws Exception
    {
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("openfail");

        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());

        URI wsUri = server.getServerUri().resolve("/");

        try(StacklessLogging ignore = new StacklessLogging(OpenFailSocket.class))
        {
            Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);

            Session session = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            try
            {
                clientSocket.openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                assertThat("OnOpen.UpgradeRequest", clientSocket.openUpgradeRequest, notNullValue());
                assertThat("OnOpen.UpgradeResponse", clientSocket.openUpgradeResponse, notNullValue());
                assertThat("Negotiated SubProtocol", clientSocket.openUpgradeResponse.getAcceptedSubProtocol(), is("openfail"));

                clientSocket.awaitCloseEvent("Client");
                clientSocket.assertCloseInfo("Client", StatusCode.SERVER_ERROR, anything());
            }
            finally
            {
                session.close();
            }
        }
    }

    /**
     * The connection has performed handshake successfully.
     * <p>
     *     Send of message to remote results in dropped connection on server side.
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    @Ignore("Needs review")
    public void messageDrop() throws Exception
    {
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("msgdrop");

        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());

        URI wsUri = server.getServerUri().resolve("/");
        client.setMaxIdleTimeout(3000);
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);

        Session session = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        try
        {
            clientSocket.openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            assertThat("OnOpen.UpgradeRequest", clientSocket.openUpgradeRequest, notNullValue());
            assertThat("OnOpen.UpgradeResponse", clientSocket.openUpgradeResponse, notNullValue());
            assertThat("Negotiated SubProtocol", clientSocket.openUpgradeResponse.getAcceptedSubProtocol(), is("msgdrop"));

            session.getRemote().sendString("drop-me");

            clientSocket.awaitErrorEvent("Client");
            clientSocket.assertErrorEvent("Client", instanceOf(WebSocketTimeoutException.class), containsString("Connection Idle Timeout"));
        }
        finally
        {
            session.close();
        }
    }

    /**
     * The connection has performed handshake successfully.
     * <p>
     *     Client sends close handshake, remote drops connection with no reply
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    @Ignore("Needs review")
    public void closeDrop() throws Exception
    {
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("closedrop");

        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());

        URI wsUri = server.getServerUri().resolve("/");
        client.setMaxIdleTimeout(3000);
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);

        Session session = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        try
        {
            clientSocket.openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            assertThat("OnOpen.UpgradeRequest", clientSocket.openUpgradeRequest, notNullValue());
            assertThat("OnOpen.UpgradeResponse", clientSocket.openUpgradeResponse, notNullValue());
            assertThat("Negotiated SubProtocol", clientSocket.openUpgradeResponse.getAcceptedSubProtocol(), is("closedrop"));

            clientSocket.close(StatusCode.NORMAL, "All Done");

            clientSocket.awaitErrorEvent("Client");
            clientSocket.assertErrorEvent("Client", instanceOf(WebSocketTimeoutException.class), containsString("Connection Idle Timeout"));
        }
        finally
        {
            session.close();
        }
    }

    /**
     * The connection has performed handshake successfully.
     * <p>
     *     Client sends close handshake, remote never replies (but leaves connection open)
     * </p>
     *
     * @throws Exception on test failure
     */
    @Test
    @Ignore("Needs review")
    public void closeNoReply() throws Exception
    {
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("closenoreply");

        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());

        URI wsUri = server.getServerUri().resolve("/");
        client.setMaxIdleTimeout(3000);
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);

        Session session = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        try
        {
            clientSocket.openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            assertThat("OnOpen.UpgradeRequest", clientSocket.openUpgradeRequest, notNullValue());
            assertThat("OnOpen.UpgradeResponse", clientSocket.openUpgradeResponse, notNullValue());
            assertThat("Negotiated SubProtocol", clientSocket.openUpgradeResponse.getAcceptedSubProtocol(), is("closenoreply"));

            clientSocket.close(StatusCode.NORMAL, "All Done");

            clientSocket.awaitErrorEvent("Client");
            clientSocket.assertErrorEvent("Client", instanceOf(WebSocketTimeoutException.class), containsString("Connection Idle Timeout"));
        }
        finally
        {
            session.close();
        }
    }
}
