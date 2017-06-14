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

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.RawFrameBuilder;
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
    
    public static class TestClientTransportOverHTTP extends HttpClientTransportOverHTTP
    {
        @Override
        protected SelectorManager newSelectorManager(HttpClient client)
        {
            return new ClientSelectorManager(client, 1)
            {
                @Override
                protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey key)
                {
                    ClientCloseTest.TestEndPoint endPoint = new ClientCloseTest.TestEndPoint(channel, selector, key, getScheduler());
                    endPoint.setIdleTimeout(client.getIdleTimeout());
                    return endPoint;
                }
            };
        }
    }
    
    public static class TestEndPoint extends SocketChannelEndPoint
    {
        public AtomicBoolean congestedFlush = new AtomicBoolean(false);
        
        public TestEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler)
        {
            super((SocketChannel) channel, selector, key, scheduler);
        }
        
        @Override
        public boolean flush(ByteBuffer... buffers) throws IOException
        {
            boolean flushed = super.flush(buffers);
            congestedFlush.set(!flushed);
            // TODO: if true, toss exception (different use case)
            return flushed;
        }
    }
    
    @Before
    public void startClient() throws Exception
    {
        HttpClient httpClient = new HttpClient(new TestClientTransportOverHTTP(), null);
        client = new WebSocketClient(httpClient);
        client.addBean(httpClient);
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
     *     OnFrame(Close)          close.success(disconnect())
     *     OnClose(normal)
     *     disconnect()
     * </pre>
     */
    @Test
    public void testClientInitiated_NoData()
    {
    
    }
    
    /**
     * Client Initiated - no data - alternate close code
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
    public void testClientInitiated_NoData_ChangeClose()
    {
    
    }

    /**
     * Client Initiated - async send (complete message) during onClose
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
     *                               sendAsync:Text (queued to extension stack)
     *                               exit onClose()
     *                           < send:Close/Normal (queued)
     *     OnFrame(Close)          disconnect()
     *     OnClose(normal)
     *     disconnect()
     * </pre>
     */
    @Test
    public void testClientInitiated_AsyncDataDuringClose()
    {
    
    }
    
    /**
     * Client Initiated - partial send data during on close
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
     *                               session.getRemote().writePartial(msg, fin=false)
     *                               exit onClose()
     *                           < send:Close/Normal (queued)
     *     OnFrame(Close)          disconnect()
     *     OnClose(normal)
     *     disconnect()
     * </pre>
     */
    @Test
    public void testClientInitiated_PartialDataDuringClose()
    {
    
    }

    /**
     * Client Initiated - async streaming during on close
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
     *                               session.getRemote().getOutputStream()
     *                               new Thread()
     *                                   send movie to client
     *                               exit onClose()
     *                           < send:Close/Normal
     *     OnFrame(Close)          disconnect()
     *     OnClose(normal)
     *     disconnect()
     * </pre>
     */
    @Test
    public void testClientInitiated_AsyncStreamingDuringClose()
    {
        // TODO: dubious
    }
    
    /**
     * Client Initiated - server is streaming data
     * <pre>
     *     Client                  Server                  Server Thread
     *     ----------              ----------              --------------------
     *     Connect                 Accept
     *     Handshake Request >
     *                           < Handshake Response
     *     OnOpen                  OnOpen
     *                             new Thread()            send(Text/!fin)
     *                                                     send(Continuation/!fin)
     *     close(Normal)
     *     send(Close/Normal)    >
     *                             OnFrame(Close)
     *                             OnClose(normal)
     *                               exit onClose()
     *                                                     send(Continuation/!fin)
     *                           < send(Close/Normal)
     *                                                     send(Continuation/fin) - FAIL
     *     OnFrame(Close)          disconnect()
     *     disconnect()
     * </pre>
     */
    @Test
    public void testClientInitiated_ServerStreamingData()
    {
    
    }

    /**
     * Client Initiated - client is streaming data
     * <pre>
     *     Client               Client Thread            Server
     *     ----------           -------------            ----------
     *     Connect                                       Accept
     *     Handshake Request >
     *                                                 < Handshake Response
     *     OnOpen                                        OnOpen
     *     new Thread()         send(Text/!fin)          - (streaming here)
     *                          send(Continuation/!fin)
     *     close(Normal)
     *     send:Close/Normal
     *                          send(Continuation/!fin) FAIL
     *                          send(Continuation/fin)  FAIL - (whole here)
     *                                                   OnFrame(Close)
     *
     *                                                   OnClose(normal)
     *                                                      exit onClose()
     *                                                   send:Close/Normal
     *     OnFrame(Close)
     *     OnClose(normal)                               disconnect()
     *     disconnect()
     * </pre>
     */
    @Test
    public void testClientInitiated_ClientStreamingData()
    {
    
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
    public void testServerInitiated_NoData()
    {
    
    }
    
    /**
     * Server Initiated - server is streaming data
     * <pre>
     *     Client                  Server                  Server Thread
     *     ----------              ----------              --------------------
     *     Connect                 Accept
     *     Handshake Request >
     *                           < Handshake Response
     *     OnOpen                  OnOpen
     *                             new Thread()            send(Text/!fin)
     *                                                     send(Continuation/!fin)
     *                             close(Normal)
     *                                                     send(Continuation/!fin)
     *                                                     send(Continuation/fin)
     *                           < send(Close/Normal)
     *     OnFrame(Close)
     *     send(Close/Normal)    >
     *     OnClose(normal)         OnFrame(Close)
     *     disconnect()            OnClose(normal)
     *                             disconnect()
     * </pre>
     */
    @Test
    public void testServerInitiated_ServerStreamingData()
    {
    
    }
    
    /**
     * Server Initiated - client is streaming data
     * <pre>
     *     Client               Client Thread            Server
     *     ----------           -------------            ----------
     *     Connect                                       Accept
     *     Handshake Request >
     *                                                 < Handshake Response
     *     OnOpen                                        OnOpen
     *     new Thread()         send(Text/!fin)
     *                          send(Continuation/!fin)
     *                                                   close(Normal)
     *                                                   send(Close/Normal)
     *     OnFrame(Close)
     *                          send(Continuation/!fin)
     *                          send(Continuation/fin)
     *     send(Close/Normal)
     *     OnClose(normal)                               OnFrame(Close)
     *     disconnect()                                  OnClose(normal)
     *                                                   disconnect()
     * </pre>
     */
    @Test
    public void testServerInitiated_ClientStreamingData()
    {
    
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
    
    }
    
    /**
     * Client Reads -1
     * <pre>
     *     Client                  Server
     *     ----------              ----------
     *     Connect                 Accept
     *     Handshake Request >
     *                           < Handshake Response
     *     OnOpen                  OnOpen
     *     ...(some time later)...
     *     fillAndParse()          disconnect - (no-close-handshake)
     *     read = -1
     *     // no close frame received?
     *     OnClose(ABNORMAL)
     *     disconnect()
     * </pre>
     */
    @Test
    @Ignore("Needs work")
    public void testClient_Read_Minus1() throws Exception
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
        clientSession.getRemote().setBatchMode(BatchMode.OFF);
    
        // Wait for client connect via client websocket
        assertThat("Client WebSocket is Open", clientSocket.openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS), is(true));
    
        try (StacklessLogging ignored = new StacklessLogging(clientSocket.LOG))
        {
            // client sends close frame
            final String origCloseReason = "Normal Close";
            clientSocket.close(StatusCode.NORMAL, origCloseReason);
        
            // server receives close frame
            serverSession.getUntrustedEndpoint().awaitCloseEvent("Server");
            serverSession.getUntrustedEndpoint().assertCloseInfo("Server", StatusCode.NORMAL, is(origCloseReason));
        
            // client should not have received close message (yet)
            clientSocket.assertNotClosed("Client");
        
            // server shuts down connection (no frame reply)
            serverSession.disconnect();
        
            // client reads -1 (EOF)
            clientSocket.assertErrorEvent("Client", instanceOf(IOException.class), containsString("EOF"));
            // client triggers close event on client ws-endpoint
            clientSocket.awaitCloseEvent("Client");
            clientSocket.assertCloseInfo("Client", StatusCode.ABNORMAL, containsString("Disconnected"));
        }
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
    public void testClient_IdleTimeout()
    {
    
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
     *     close(Normal)
     *     send:Close/Normal >
     *                             (state unknown)
     *     ...(some time later)...
     *     onIdleTimeout()
     *     conn.onError(TimeoutException)
     *     OnClose(ABNORMAL/IdleTimeout)
     *     disconnect()
     * </pre>
     */
    @Test
    public void testClient_IdleTimeout_Alt()
    {
    
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
        clientSession.getRemote().setBatchMode(BatchMode.OFF);
    
        // Wait for client connect via client websocket
        assertThat("Client WebSocket is Open", clientSocket.openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS), is(true));
    
        // client should not have received close message (yet)
        clientSocket.assertNotClosed("Client");
    
        // server sends bad close frame (too big of a reason message)
        byte msg[] = new byte[400];
        Arrays.fill(msg, (byte) 'x');
        ByteBuffer bad = ByteBuffer.allocate(500);
        RawFrameBuilder.putOpFin(bad, OpCode.CLOSE, true);
        RawFrameBuilder.putLength(bad, msg.length + 2, false);
        bad.putShort((short) StatusCode.NORMAL);
        bad.put(msg);
        BufferUtil.flipToFlush(bad, 0);
        try (StacklessLogging ignored = new StacklessLogging(Parser.class))
        {
            serverSession.getUntrustedConnection().writeRaw(bad);
        
            // client should have noticed the error
            clientSocket.assertErrorEvent("Client", instanceOf(ProtocolException.class), containsString("Invalid control frame"));
        
            // client parse invalid frame, notifies server of close (protocol error)
            serverSession.getUntrustedEndpoint().awaitCloseEvent("Server");
            serverSession.getUntrustedEndpoint().assertCloseInfo("Server", StatusCode.PROTOCOL, allOf(containsString("Invalid control frame"), containsString("length")));
        }
    
        // server disconnects
        serverSession.disconnect();
    
        // client error event on ws-endpoint
        clientSocket.awaitErrorEvent("Client");
        clientSocket.assertErrorEvent("Client", instanceOf(ProtocolException.class), containsString("Invalid control frame"));
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
        
        // Server accepts connect
        UntrustedWSSession serverSession = serverSessionFut.get(10, TimeUnit.SECONDS);
        
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
    
    @Test
    public void testDecoderError_CallsOnError()
    {
        // TODO: put in JSR specific layers of tests
    }
}
