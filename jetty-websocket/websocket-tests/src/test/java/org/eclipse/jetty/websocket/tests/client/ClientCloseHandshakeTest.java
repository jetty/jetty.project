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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketTimeoutException;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSServer;
import org.eclipse.jetty.websocket.tests.UntrustedWSSession;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class ClientCloseHandshakeTest
{
    private static final Logger LOG = Log.getLogger(ClientCloseHandshakeTest.class);

    @Rule
    public TestName testname = new TestName();

    private UntrustedWSServer server;
    private WebSocketClient client;

    @Before
    public void startClient() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        client = new WebSocketClient(httpClient);
        client.addBean(httpClient);
        client.start();
    }

    @Before
    public void startServer() throws Exception
    {
        server = new UntrustedWSServer();
        server.registerWebSocket("/badclose", (req, resp) -> new BadCloseSocket("SERVER"));
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

    /**
     * Client Initiated - no data
     * <pre>
     *     Client                  Server
     *     ----------              ----------
     *     TCP Connect             TCP Accept
     *     WS Handshake Request >
     *                           < WS Handshake Response
     *     OnOpen()                OnOpen()
     *     close(Normal)
     *     send:Close/Normal >
     *                             OnFrame(Close)
     *                             OnClose(normal)
     *                               exit onClose()
     *                           < send:Close/Normal
     *     OnFrame(Close)
     *     OnClose(normal)
     *     disconnect()
     * </pre>
     */
    @Test
    public void testClientInitiated_NoData() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
        CompletableFuture<UntrustedWSSession> serverSessionFut = new CompletableFuture<>();
        server.registerOnOpenFuture(wsUri, serverSessionFut);

        // Client connects
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

        // Wait for client connect on via future
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        try (StacklessLogging ignored = new StacklessLogging(clientSocket.LOG))
        {
            clientSession.setIdleTimeout(1000);
            clientSession.getRemote().setBatchMode(BatchMode.OFF);

            // Wait for client connect via client websocket
            assertTrue("Client WebSocket is Open", clientSocket.openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS));

            // client should not have received close message (yet)
            clientSocket.assertNotClosed("Client");

            // client initiates close
            clientSession.close(StatusCode.NORMAL, "Normal");

            // verify client close
            clientSocket.awaitCloseEvent("Client");
            clientSocket.assertCloseInfo("Client", StatusCode.NORMAL, containsString("Normal"));
        }
        finally
        {
            clientSession.close();
        }
    }

    /**
     * Client Initiated - no data - server supplied alternate close code
     * <pre>
     *     Client                  Server
     *     ----------              ----------
     *     TCP Connect             TCP Accept
     *     WS Handshake Request >
     *                           < WS Handshake Response
     *     OnOpen()                OnOpen()
     *     close(Normal)
     *     send:Close/Normal >
     *                             OnFrame(Close)
     *                             OnClose(normal)
     *                               close(Shutdown)
     *                             < send:Close/Shutdown (queue)
     *                                  cb.success() -> disconnect()
     *                               exit onClose()
     *     OnFrame(Close)
     *     OnClose(Shutdown)
     *     disconnect()
     * </pre>
     */
    @Test
    public void testClientInitiated_NoData_ChangeClose() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
        CompletableFuture<UntrustedWSSession> serverSessionFut = new CompletableFuture<>();
        server.registerOnOpenFuture(wsUri, serverSessionFut);

        // Client connects
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

        // Server accepts connect
        UntrustedWSSession serverSession = serverSessionFut.get(10, TimeUnit.SECONDS);
        // Establish server side onClose behavior
        serverSession.getUntrustedEndpoint().setOnCloseConsumer((session, closeInfo) ->
                session.close(StatusCode.SHUTDOWN, "Server Shutdown"));

        // Wait for client connect on via future
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        try (StacklessLogging ignored = new StacklessLogging(clientSocket.LOG))
        {
            clientSession.setIdleTimeout(1000);
            clientSession.getRemote().setBatchMode(BatchMode.OFF);

            // Wait for client connect via client websocket
            assertTrue("Client WebSocket is Open", clientSocket.openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS));

            // client should not have received close message (yet)
            clientSocket.assertNotClosed("Client");

            // client initiates close
            clientSession.close(StatusCode.NORMAL, "Normal");

            // verify client close
            clientSocket.awaitCloseEvent("Client");
            clientSocket.assertCloseInfo("Client", StatusCode.SHUTDOWN, containsString("Server Shutdown"));
        }
        finally
        {
            clientSession.close();
        }
    }

    /**
     * Server Initiated - no data
     * <pre>
     *     Client                  Server
     *     ----------              ----------
     *     Connect                 Accept
     *     Handshake Request >
     *                           < Handshake Response
     *     OnOpen                  OnOpen
     *                             close(Normal)
     *                           < send(Close/Normal)
     *     OnFrame(Close)
     *     OnClose(normal)
     *       exit onClose()
     *     send:Close/Normal)    >
     *                             OnFrame(Close)
     *     disconnect()            OnClose(normal)
     *                             disconnect()
     * </pre>
     */
    @Test
    public void testServerInitiated_NoData() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
        CompletableFuture<UntrustedWSSession> serverSessionFut = new CompletableFuture<>();
        server.registerOnOpenFuture(wsUri, serverSessionFut);

        // Client connects
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

        // Wait for server connect
        UntrustedWSSession serverSession = serverSessionFut.get(10, TimeUnit.SECONDS);

        // Wait for client connect on via future
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        try (StacklessLogging ignored = new StacklessLogging(clientSocket.LOG))
        {
            clientSession.setIdleTimeout(1000);
            clientSession.getRemote().setBatchMode(BatchMode.OFF);

            // Wait for client connect via client websocket
            assertTrue("Client WebSocket is Open", clientSocket.openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS));

            // client should not have received close message (yet)
            clientSocket.assertNotClosed("Client");

            // server initiates close
            serverSession.close(StatusCode.NORMAL, "Server initiated");

            // verify client close
            clientSocket.awaitCloseEvent("Client");
            clientSocket.assertCloseInfo("Client", StatusCode.NORMAL, containsString("Server initiated"));
        }
        finally
        {
            clientSession.close();
        }
    }

    /**
     * Client Read IOException
     * <pre>
     *     Client                  Server
     *     ----------              ----------
     *     Connect                 Accept
     *     Handshake Request >
     *                           < Handshake Response
     *     OnOpen                  OnOpen
     *     ....
     *                             disconnect - (accidental)
     *                             conn.onError(EOF)
     *                             OnClose(ABNORMAL)
     *                             disconnect()
     *     read -> IOException
     *     conn.onError(IOException)
     *     OnClose(ABNORMAL)
     *     disconnect()
     * </pre>
     */
    @Test
    public void testClient_Read_IOException()
    {
        // TODO: somehow?
    }

    /**
     * Client Idle Timeout
     * <pre>
     *     Client                  Server
     *     ----------              ----------
     *     Connect                 Accept
     *     Handshake Request >
     *                           < Handshake Response
     *     OnOpen                  OnOpen
     *     ...(some time later)...
     *     onIdleTimeout()
     *     conn.onError(TimeoutException)
     *     send:Close/Shutdown  >
     *                             OnFrame(Close)
     *                             OnClose(Shutdown)
     *                               exit onClose()
     *                           < send(Close/Shutdown)
     *     OnFrame(Close)
     *     OnClose(Shutdown)       disconnect()
     *     disconnect()
     * </pre>
     */
    @Test
    @Ignore("Needs review")
    public void testClient_IdleTimeout() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
        CompletableFuture<UntrustedWSSession> serverSessionFut = new CompletableFuture<>();
        server.registerOnOpenFuture(wsUri, serverSessionFut);

        // Client connects
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

        // Server accepts connect
        UntrustedWSSession serverSession = serverSessionFut.get(10, TimeUnit.SECONDS);

        // Wait for client connect on via future
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        try (StacklessLogging ignored = new StacklessLogging(clientSocket.LOG))
        {
            clientSession.setIdleTimeout(1000);
            clientSession.getRemote().setBatchMode(BatchMode.OFF);

            // Wait for client connect via client websocket
            assertTrue("Client WebSocket is Open", clientSocket.openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS));

            // client should not have received close message (yet)
            clientSocket.assertNotClosed("Client");

            // server sends some data
            int length = 30;
            ByteBuffer partialFrame = ByteBuffer.allocate(length + 5);
            partialFrame.put((byte) 0x82);
            byte b = 0x00; // no masking
            b |= length & 0x7F;
            partialFrame.put(b);
            partialFrame.flip();

            serverSession.getUntrustedConnection().writeRaw(partialFrame);
            // server shuts down connection)
            serverSession.disconnect();

            // client read timeout
            clientSocket.awaitErrorEvent("Client");
            clientSocket.assertErrorEvent("Client", instanceOf(WebSocketTimeoutException.class), containsString("Idle Timeout"));

            // TODO: should this also cause an onClose event?
            // clientSocket.awaitCloseEvent("Client");
            // clientSocket.assertCloseInfo("Client", StatusCode.ABNORMAL, containsString("Disconnected"));
        }
        finally
        {
            clientSession.close();
        }
    }

    /**
     * Client ProtocolViolation
     * <pre>
     *     Bad Client              Server
     *     ----------              ----------
     *     Connect                 Accept
     *     Handshake Request >
     *                           < Handshake Response
     *     OnOpen                  OnOpen
     *     ....
     *     send:Text(BadFormat)
     *                             close(ProtocolViolation)
     *                             send(Close/ProtocolViolation)
     *     OnFrame(Close)          disconnect()
     *     OnClose(ProtocolViolation)
     *     send(Close/ProtocolViolation) >  FAILS
     *     disconnect()
     * </pre>
     */
    @Test
    public void testClient_ProtocolViolation_Received() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        URI wsUri = server.getWsUri().resolve("/badclose");

        // Client connects
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

        // Wait for client connect on via future
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        try
        {
            clientSession.getRemote().setBatchMode(BatchMode.OFF);

            // Wait for client connect via client websocket
            assertThat("Client WebSocket is Open", clientSocket.openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS), is(true));

            // client should not have received close message (yet)
            clientSocket.assertNotClosed("Client");

            // Trigger behavior on server side with message
            clientSession.getRemote().sendString("fail-now");

            // client error event on ws-endpoint
            clientSocket.awaitErrorEvent("Client");
            clientSocket.assertErrorEvent("Client", instanceOf(ProtocolException.class), containsString("Invalid control frame"));
        }
        finally
        {
            clientSession.close();
        }
    }

    /**
     * Client Exception during Write
     * <pre>
     *     Bad Client              Server
     *     ----------              ----------
     *     Connect                 Accept
     *     Handshake Request >
     *                           < Handshake Response
     *     OnOpen                  OnOpen
     *     ....
     *     send:Text()
     *     write -> IOException
     *     conn.onError(IOException)
     *     OnClose(Shutdown)
     *     disconnect()
     *                             read -> IOException
     *                             conn.onError(IOException)
     *                             OnClose(ABNORMAL)
     *                             disconnect()
     * </pre>
     */
    @Test
    @Ignore("Needs review")
    public void testWriteException() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
        CompletableFuture<UntrustedWSSession> serverSessionFut = new CompletableFuture<>();
        server.registerOnOpenFuture(wsUri, serverSessionFut);

        // Client connects
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

        // client confirms connection via echo
        // Wait for client connect on via future
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        clientSession.getRemote().setBatchMode(BatchMode.OFF);

        // Wait for client connect via client websocket
        assertThat("Client WebSocket is Open", clientSocket.openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS), is(true));

        // setup client endpoint for write failure (test only)
        EndPoint endp = clientSocket.getJettyEndPoint();
        endp.shutdownOutput();

        // client enqueue close frame
        // client write failure
        final String origCloseReason = "Normal Close";
        clientSocket.close(StatusCode.NORMAL, origCloseReason);

        assertThat("OnError", clientSocket.error.get(), instanceOf(EofException.class));

        // client triggers close event on client ws-endpoint
        // assert - close code==1006 (abnormal)
        // assert - close reason message contains (write failure)
        assertTrue("Client onClose not called", clientSocket.closeLatch.getCount() > 0);
    }
}
