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

package org.eclipse.jetty.ee10.websocket.tests.client;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.api.Frame;
import org.eclipse.jetty.ee10.websocket.api.Session;
import org.eclipse.jetty.ee10.websocket.api.StatusCode;
import org.eclipse.jetty.ee10.websocket.api.WebSocketFrameListener;
import org.eclipse.jetty.ee10.websocket.api.WebSocketListener;
import org.eclipse.jetty.ee10.websocket.api.exceptions.MessageTooLargeException;
import org.eclipse.jetty.ee10.websocket.api.exceptions.WebSocketTimeoutException;
import org.eclipse.jetty.ee10.websocket.api.util.WSURI;
import org.eclipse.jetty.ee10.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee10.websocket.tests.CloseTrackingEndpoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.OpCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClientCloseTest
{
    private Server server;
    private WebSocketClient client;
    private BlockingArrayQueue<ServerEndpoint> serverEndpoints = new BlockingArrayQueue<>();

    private Session confirmConnection(CloseTrackingEndpoint clientSocket, Future<Session> clientFuture) throws Exception
    {
        // Wait for client connect on via future
        Session session = clientFuture.get(30, SECONDS);

        try
        {
            // Send message from client to server
            final String echoMsg = "echo-test";
            clientSocket.getRemote().sendString(echoMsg);

            // Verify received message
            String recvMsg = clientSocket.messageQueue.poll(5, SECONDS);
            assertThat("Received message", recvMsg, is(echoMsg));

            // Verify that there are no errors
            assertThat("Error events", clientSocket.error.get(), nullValue());
        }
        finally
        {
            clientSocket.clearQueues();
        }

        return session;
    }

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.setMaxTextMessageSize(1024);
        client.start();
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder holder = new ServletHolder(new JettyWebSocketServlet()
        {
            @Override
            public void configure(JettyWebSocketServletFactory factory)
            {
                factory.setIdleTimeout(Duration.ofSeconds(10));
                factory.setMaxTextMessageSize(1024 * 1024 * 2);
                factory.setCreator((req, resp) ->
                {
                    ServerEndpoint endpoint = new ServerEndpoint();
                    serverEndpoints.offer(endpoint);
                    return endpoint;
                });
            }
        });
        context.addServlet(holder, "/ws");

        server.setHandler(new HandlerList(context, new DefaultHandler()));
        JettyWebSocketServletContainerInitializer.configure(context, null);

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
        server.stop();
    }

    @Test
    public void testHalfClose() throws Exception
    {
        // Set client timeout
        final int timeout = 5000;
        client.setIdleTimeout(Duration.ofMillis(timeout));

        ClientOpenSessionTracker clientSessionTracker = new ClientOpenSessionTracker(1);
        clientSessionTracker.addTo(client);

        // Client connects
        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        CloseTrackingEndpoint clientSocket = new CloseTrackingEndpoint();
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

        // client confirms connection via echo
        confirmConnection(clientSocket, clientConnectFuture);

        // client sends close frame (code 1000, normal)
        final String origCloseReason = "send-more-frames";
        clientSocket.getSession().close(StatusCode.NORMAL, origCloseReason);

        // Verify received messages
        String recvMsg = clientSocket.messageQueue.poll(5, SECONDS);
        assertThat("Received message 1", recvMsg, is("Hello"));
        recvMsg = clientSocket.messageQueue.poll(5, SECONDS);
        assertThat("Received message 2", recvMsg, is("World"));

        // Verify that there are no errors
        assertThat("Error events", clientSocket.error.get(), nullValue());

        // client close event on ws-endpoint
        clientSocket.assertReceivedCloseEvent(timeout, is(StatusCode.NORMAL), containsString(""));
        clientSessionTracker.assertClosedProperly(client);
    }

    @Test
    public void testMessageTooLargeException() throws Exception
    {
        // Set client timeout
        final int timeout = 3000;
        client.setIdleTimeout(Duration.ofMillis(timeout));

        ClientOpenSessionTracker clientSessionTracker = new ClientOpenSessionTracker(1);
        clientSessionTracker.addTo(client);

        // Client connects
        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        CloseTrackingEndpoint clientSocket = new CloseTrackingEndpoint();
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

        // client confirms connection via echo
        confirmConnection(clientSocket, clientConnectFuture);

        clientSocket.getSession().getRemote().sendString("too-large-message");
        clientSocket.assertReceivedCloseEvent(timeout, is(StatusCode.MESSAGE_TOO_LARGE), containsString("Text message too large"));

        // client should have noticed the error
        assertThat("OnError Latch", clientSocket.errorLatch.await(2, SECONDS), is(true));
        assertThat("OnError", clientSocket.error.get(), instanceOf(MessageTooLargeException.class));

        // client triggers close event on client ws-endpoint
        clientSessionTracker.assertClosedProperly(client);
    }

    @Test
    public void testRemoteDisconnect() throws Exception
    {
        // Set client timeout
        final int clientTimeout = 3000;
        client.setIdleTimeout(Duration.ofMillis(clientTimeout));

        ClientOpenSessionTracker clientSessionTracker = new ClientOpenSessionTracker(1);
        clientSessionTracker.addTo(client);

        // Client connects
        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        CloseTrackingEndpoint clientSocket = new CloseTrackingEndpoint();
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

        // client confirms connection via echo
        confirmConnection(clientSocket, clientConnectFuture);

        // client sends close frame (triggering server connection abort)
        final String origCloseReason = "abort";
        clientSocket.getSession().close(StatusCode.NORMAL, origCloseReason);

        // client reads -1 (EOF)
        // client triggers close event on client ws-endpoint
        clientSocket.assertReceivedCloseEvent(2000,
            is(StatusCode.ABNORMAL),
            containsString("Session Closed"));

        clientSessionTracker.assertClosedProperly(client);
    }

    @Test
    public void testServerNoCloseHandshake() throws Exception
    {
        // Set client timeout
        final int clientTimeout = 1000;
        client.setIdleTimeout(Duration.ofMillis(clientTimeout));

        ClientOpenSessionTracker clientSessionTracker = new ClientOpenSessionTracker(1);
        clientSessionTracker.addTo(client);

        // Client connects
        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        CloseTrackingEndpoint clientSocket = new CloseTrackingEndpoint();
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

        // client confirms connection via echo
        confirmConnection(clientSocket, clientConnectFuture);

        // client sends close frame
        final String origCloseReason = "sleep|2500";
        clientSocket.getSession().close(StatusCode.NORMAL, origCloseReason);

        // client close should occur
        clientSocket.assertReceivedCloseEvent(clientTimeout * 2,
            is(StatusCode.SHUTDOWN),
            containsString("Timeout"));

        // client idle timeout triggers close event on client ws-endpoint
        assertThat("OnError Latch", clientSocket.errorLatch.await(2, SECONDS), is(true));
        assertThat("OnError", clientSocket.error.get(), instanceOf(WebSocketTimeoutException.class));
        clientSessionTracker.assertClosedProperly(client);
    }

    @Test
    public void testDoubleClose() throws Exception
    {
        ClientOpenSessionTracker clientSessionTracker = new ClientOpenSessionTracker(1);
        clientSessionTracker.addTo(client);

        // Client connects
        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        CloseTrackingEndpoint clientSocket = new CloseTrackingEndpoint();
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

        // Client confirms connection via echo.
        confirmConnection(clientSocket, clientConnectFuture);

        // Close twice, first close should succeed and second close is a NOOP
        clientSocket.getSession().close(StatusCode.NORMAL, "close1");
        clientSocket.getSession().close(StatusCode.NO_CODE, "close2");

        // Second close is ignored, we are notified of the first close.
        clientSocket.assertReceivedCloseEvent(5000, is(StatusCode.NORMAL), containsString("close1"));
        assertNull(clientSocket.error.get());
    }

    @Test
    public void testStopLifecycle() throws Exception
    {
        // Set client timeout
        final int timeout = 3000;
        client.setIdleTimeout(Duration.ofMillis(timeout));

        int sessionCount = 3;
        ClientOpenSessionTracker clientSessionTracker = new ClientOpenSessionTracker(sessionCount);
        clientSessionTracker.addTo(client);

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        List<CloseTrackingEndpoint> clientSockets = new ArrayList<>();

        // Open Multiple Clients
        for (int i = 0; i < sessionCount; i++)
        {
            // Client Request Upgrade
            CloseTrackingEndpoint clientSocket = new CloseTrackingEndpoint();
            clientSockets.add(clientSocket);
            Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

            // client confirms connection via echo
            confirmConnection(clientSocket, clientConnectFuture);
        }

        assertThat(serverEndpoints.size(), is(sessionCount));

        try
        {
            // block all the server threads
            for (int i = 0; i < sessionCount; i++)
            {
                clientSockets.get(i).getSession().getRemote().sendString("block");
            }

            assertTimeoutPreemptively(ofSeconds(5), () ->
            {
                // client lifecycle stop (the meat of this test)
                client.stop();
            });

            // clients disconnect
            for (int i = 0; i < sessionCount; i++)
            {
                clientSockets.get(i).assertReceivedCloseEvent(2000, is(StatusCode.SHUTDOWN), containsString("Container being shut down"));
            }

            // ensure all Sessions are gone. connections are gone. etc. (client and server)
            // ensure ConnectionListener onClose is called 3 times
            clientSessionTracker.assertClosedProperly(client);

            assertThat(serverEndpoints.size(), is(sessionCount));
        }
        finally
        {
            for (int i = 0; i < sessionCount; i++)
            {
                serverEndpoints.get(i).block.countDown();
            }
        }
    }

    @Test
    public void testWriteException() throws Exception
    {
        // Set client timeout
        final int timeout = 2000;
        client.setIdleTimeout(Duration.ofMillis(timeout));

        ClientOpenSessionTracker clientSessionTracker = new ClientOpenSessionTracker(1);
        clientSessionTracker.addTo(client);

        // Client connects
        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        CloseTrackingEndpoint clientSocket = new CloseTrackingEndpoint();
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

        // client confirms connection via echo
        confirmConnection(clientSocket, clientConnectFuture);

        try
        {
            // Block on the server so that the server does not detect a read failure
            clientSocket.getSession().getRemote().sendString("block");

            // setup client endpoint for write failure (test only)
            EndPoint endp = clientSocket.getEndPoint();
            endp.shutdownOutput();

            // client enqueue close frame
            // should result in a client write failure
            final String origCloseReason = "Normal Close from Client";
            clientSocket.getSession().close(StatusCode.NORMAL, origCloseReason);

            assertThat("OnError Latch", clientSocket.errorLatch.await(2, SECONDS), is(true));
            assertThat("OnError", clientSocket.error.get(), instanceOf(EofException.class));

            // client triggers close event on client ws-endpoint
            // assert - close code==1006 (abnormal)
            clientSocket.assertReceivedCloseEvent(timeout, is(StatusCode.ABNORMAL), null);
            clientSessionTracker.assertClosedProperly(client);

            assertThat(serverEndpoints.size(), is(1));
        }
        finally
        {
            for (ServerEndpoint endpoint : serverEndpoints)
            {
                endpoint.block.countDown();
            }
        }
    }

    public static class ServerEndpoint implements WebSocketFrameListener, WebSocketListener
    {
        private static final Logger LOG = LoggerFactory.getLogger(ServerEndpoint.class);
        private Session session;
        CountDownLatch block = new CountDownLatch(1);

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len)
        {
        }

        @Override
        public void onWebSocketText(String message)
        {
            try
            {
                if (message.equals("too-large-message"))
                {
                    // send extra large message
                    byte[] buf = new byte[1024 * 1024];
                    Arrays.fill(buf, (byte)'x');
                    String bigmsg = new String(buf, UTF_8);
                    session.getRemote().sendString(bigmsg);
                }
                else if (message.equals("block"))
                {
                    LOG.debug("blocking");
                    assertTrue(block.await(5, TimeUnit.MINUTES));
                    LOG.debug("unblocked");
                }
                else
                {
                    // simple echo
                    session.getRemote().sendString(message);
                }
            }
            catch (Throwable t)
            {
                LOG.debug("send text failure", t);
                throw new RuntimeException(t);
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
        }

        @Override
        public void onWebSocketConnect(Session session)
        {
            this.session = session;
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onWebSocketError(): ", cause);
        }

        @Override
        public void onWebSocketFrame(Frame frame)
        {
            if (frame.getOpCode() == OpCode.CLOSE)
            {
                CloseStatus closeInfo = new CloseStatus(frame.getPayload());
                String reason = closeInfo.getReason();

                if (reason.equals("send-more-frames"))
                {
                    try
                    {
                        session.getRemote().sendString("Hello");
                        session.getRemote().sendString("World");
                    }
                    catch (Throwable ignore)
                    {
                        LOG.debug("OOPS", ignore);
                    }
                }
                else if (reason.equals("abort"))
                {
                    try
                    {
                        SECONDS.sleep(1);
                        LOG.info("Server aborting session abruptly");
                        session.disconnect();
                    }
                    catch (Throwable ignore)
                    {
                        LOG.trace("IGNORED", ignore);
                    }
                }
                else if (reason.startsWith("sleep|"))
                {
                    int idx = reason.indexOf('|');
                    int timeMs = Integer.parseInt(reason.substring(idx + 1));
                    try
                    {
                        LOG.info("Server Sleeping for {} ms", timeMs);
                        TimeUnit.MILLISECONDS.sleep(timeMs);
                    }
                    catch (InterruptedException ignore)
                    {
                        LOG.trace("IGNORED", ignore);
                    }
                }
            }
        }
    }
}
