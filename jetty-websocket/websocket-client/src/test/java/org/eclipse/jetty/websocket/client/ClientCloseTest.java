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

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.io.AbstractWebSocketConnection;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.IBlockheadServerConnection;
import org.eclipse.jetty.websocket.common.test.IncomingFramesCapture;
import org.eclipse.jetty.websocket.common.test.RawFrameBuilder;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class ClientCloseTest
{
    private static final Logger LOG = Log.getLogger(ClientCloseTest.class);
    
    private static class CloseTrackingSocket extends WebSocketAdapter
    {
        private static final Logger LOG =  ClientCloseTest.LOG.getLogger("CloseTrackingSocket");

        public int closeCode = -1;
        public String closeReason = null;
        public CountDownLatch closeLatch = new CountDownLatch(1);
        public AtomicInteger closeCount = new AtomicInteger(0);
        public CountDownLatch openLatch = new CountDownLatch(1);

        public EventQueue<String> messageQueue = new EventQueue<>();
        public EventQueue<Throwable> errorQueue = new EventQueue<>();

        public void assertNoCloseEvent()
        {
            Assert.assertThat("Client Close Event",closeLatch.getCount(),is(1L));
            Assert.assertThat("Client Close Event Status Code ",closeCode,is(-1));
        }

        public void assertReceivedCloseEvent(int clientTimeoutMs, Matcher<Integer> statusCodeMatcher, Matcher<String> reasonMatcher)
                throws InterruptedException
        {
            long maxTimeout = clientTimeoutMs * 2;

            Assert.assertThat("Client Close Event Occurred",closeLatch.await(maxTimeout,TimeUnit.MILLISECONDS),is(true));
            Assert.assertThat("Client Close Event Count",closeCount.get(),is(1));
            Assert.assertThat("Client Close Event Status Code",closeCode,statusCodeMatcher);
            if (reasonMatcher == null)
            {
                Assert.assertThat("Client Close Event Reason",closeReason,nullValue());
            }
            else
            {
                Assert.assertThat("Client Close Event Reason",closeReason,reasonMatcher);
            }
        }

        public void assertReceivedError(Class<? extends Throwable> expectedThrownClass, Matcher<String> messageMatcher) throws TimeoutException,
                InterruptedException
        {
            errorQueue.awaitEventCount(1,30,TimeUnit.SECONDS);
            Throwable actual = errorQueue.poll();
            Assert.assertThat("Client Error Event",actual,instanceOf(expectedThrownClass));
            if (messageMatcher == null)
            {
                Assert.assertThat("Client Error Event Message",actual.getMessage(),nullValue());
            }
            else
            {
                Assert.assertThat("Client Error Event Message",actual.getMessage(),messageMatcher);
            }
        }

        public void clearQueues()
        {
            messageQueue.clear();
            errorQueue.clear();
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            LOG.debug("onWebSocketClose({},{})",statusCode,reason);
            super.onWebSocketClose(statusCode,reason);
            closeCount.incrementAndGet();
            closeCode = statusCode;
            closeReason = reason;
            closeLatch.countDown();
        }

        @Override
        public void onWebSocketConnect(Session session)
        {
            LOG.debug("onWebSocketConnect({})",session);
            super.onWebSocketConnect(session);
            openLatch.countDown();
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            LOG.debug("onWebSocketError",cause);
            Assert.assertThat("Error capture",errorQueue.offer(cause),is(true));
        }

        @Override
        public void onWebSocketText(String message)
        {
            LOG.debug("onWebSocketText({})",message);
            messageQueue.offer(message);
        }

        public EndPoint getEndPoint() throws Exception
        {
            Session session = getSession();
            Assert.assertThat("Session type",session,instanceOf(WebSocketSession.class));

            WebSocketSession wssession = (WebSocketSession)session;
            Field fld = wssession.getClass().getDeclaredField("connection");
            fld.setAccessible(true);
            Assert.assertThat("Field: connection",fld,notNullValue());

            Object val = fld.get(wssession);
            Assert.assertThat("Connection type",val,instanceOf(AbstractWebSocketConnection.class));
            @SuppressWarnings("resource")
            AbstractWebSocketConnection wsconn = (AbstractWebSocketConnection)val;
            return wsconn.getEndPoint();
        }
    }

    @Rule
    public TestTracker tt = new TestTracker();

    private BlockheadServer server;
    private WebSocketClient client;

    private void confirmConnection(CloseTrackingSocket clientSocket, Future<Session> clientFuture, IBlockheadServerConnection serverConns) throws Exception
    {
        // Wait for client connect on via future
        clientFuture.get(30,TimeUnit.SECONDS);

        // Wait for client connect via client websocket
        Assert.assertThat("Client WebSocket is Open",clientSocket.openLatch.await(30,TimeUnit.SECONDS),is(true));

        try
        {
            // Send message from client to server
            final String echoMsg = "echo-test";
            Future<Void> testFut = clientSocket.getRemote().sendStringByFuture(echoMsg);

            // Wait for send future
            testFut.get(30,TimeUnit.SECONDS);

            // Read Frame on server side
            IncomingFramesCapture serverCapture = serverConns.readFrames(1,30,TimeUnit.SECONDS);
            serverCapture.assertNoErrors();
            serverCapture.assertFrameCount(1);
            WebSocketFrame frame = serverCapture.getFrames().poll();
            Assert.assertThat("Server received frame",frame.getOpCode(),is(OpCode.TEXT));
            Assert.assertThat("Server received frame payload",frame.getPayloadAsUTF8(),is(echoMsg));

            // Server send echo reply
            serverConns.write(new TextFrame().setPayload(echoMsg));

            // Wait for received echo
            clientSocket.messageQueue.awaitEventCount(1,1,TimeUnit.SECONDS);

            // Verify received message
            String recvMsg = clientSocket.messageQueue.poll();
            Assert.assertThat("Received message",recvMsg,is(echoMsg));

            // Verify that there are no errors
            Assert.assertThat("Error events",clientSocket.errorQueue,empty());
        }
        finally
        {
            clientSocket.clearQueues();
        }
    }

    private void confirmServerReceivedCloseFrame(IBlockheadServerConnection serverConn, int expectedCloseCode, Matcher<String> closeReasonMatcher) throws IOException,
            TimeoutException
    {
        IncomingFramesCapture serverCapture = serverConn.readFrames(1,30,TimeUnit.SECONDS);
        serverCapture.assertNoErrors();
        serverCapture.assertFrameCount(1);
        serverCapture.assertHasFrame(OpCode.CLOSE,1);
        WebSocketFrame frame = serverCapture.getFrames().poll();
        Assert.assertThat("Server received close frame",frame.getOpCode(),is(OpCode.CLOSE));
        CloseInfo closeInfo = new CloseInfo(frame);
        Assert.assertThat("Server received close code",closeInfo.getStatusCode(),is(expectedCloseCode));
        if (closeReasonMatcher == null)
        {
            Assert.assertThat("Server received close reason",closeInfo.getReason(),nullValue());
        }
        else
        {
            Assert.assertThat("Server received close reason",closeInfo.getReason(),closeReasonMatcher);
        }
    }

    public static class TestClientTransportOverHTTP extends HttpClientTransportOverHTTP
    {
        @Override
        protected SelectorManager newSelectorManager(HttpClient client)
        {
            return new ClientSelectorManager(client, 1){
                @Override
                protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey key)
                {
                    TestEndPoint endPoint = new TestEndPoint(channel,selector,key,getScheduler());
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
            super((SocketChannel)channel,selector,key,scheduler);
        }

        @Override
        public boolean flush(ByteBuffer... buffers) throws IOException
        {
            boolean flushed = super.flush(buffers);
            congestedFlush.set(!flushed);
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
    public void testHalfClose() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        // Client connects
        CloseTrackingSocket clientSocket = new CloseTrackingSocket();
        Future<Session> clientConnectFuture = client.connect(clientSocket,server.getWsUri());

        // Server accepts connect
        IBlockheadServerConnection serverConn = server.accept();
        serverConn.upgrade();

        // client confirms connection via echo
        confirmConnection(clientSocket,clientConnectFuture,serverConn);

        // client sends close frame (code 1000, normal)
        final String origCloseReason = "Normal Close";
        clientSocket.getSession().close(StatusCode.NORMAL,origCloseReason);

        // server receives close frame
        confirmServerReceivedCloseFrame(serverConn,StatusCode.NORMAL,is(origCloseReason));

        // server sends 2 messages
        serverConn.write(new TextFrame().setPayload("Hello"));
        serverConn.write(new TextFrame().setPayload("World"));

        // server sends close frame (code 1000, no reason)
        CloseInfo sclose = new CloseInfo(StatusCode.NORMAL,"From Server");
        serverConn.write(sclose.asFrame());

        // client receives 2 messages
        clientSocket.messageQueue.awaitEventCount(2,1,TimeUnit.SECONDS);

        // Verify received messages
        String recvMsg = clientSocket.messageQueue.poll();
        Assert.assertThat("Received message 1",recvMsg,is("Hello"));
        recvMsg = clientSocket.messageQueue.poll();
        Assert.assertThat("Received message 2",recvMsg,is("World"));

        // Verify that there are no errors
        Assert.assertThat("Error events",clientSocket.errorQueue,empty());

        // client close event on ws-endpoint
        clientSocket.assertReceivedCloseEvent(timeout,is(StatusCode.NORMAL),containsString("From Server"));
    }

    @Ignore("Need sbordet's help here")
    @Test
    public void testNetworkCongestion() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        // Client connects
        CloseTrackingSocket clientSocket = new CloseTrackingSocket();
        Future<Session> clientConnectFuture = client.connect(clientSocket,server.getWsUri());

        // Server accepts connect
        IBlockheadServerConnection serverConn = server.accept();
        serverConn.upgrade();

        // client confirms connection via echo
        confirmConnection(clientSocket,clientConnectFuture,serverConn);

        // client sends BIG frames (until it cannot write anymore)
        // server must not read (for test purpose, in order to congest connection)
        // when write is congested, client enqueue close frame
        // client initiate write, but write never completes
        EndPoint endp = clientSocket.getEndPoint();
        Assert.assertThat("EndPoint is testable",endp,instanceOf(TestEndPoint.class));
        TestEndPoint testendp = (TestEndPoint)endp;

        char msg[] = new char[10240];
        int writeCount = 0;
        long writeSize = 0;
        int i = 0;
        while (!testendp.congestedFlush.get())
        {
            int z = i - ((i / 26) * 26);
            char c = (char)('a' + z);
            Arrays.fill(msg,c);
            clientSocket.getRemote().sendStringByFuture(String.valueOf(msg));
            writeCount++;
            writeSize += msg.length;
        }
        LOG.debug("Wrote {} frames totalling {} bytes of payload before congestion kicked in",writeCount,writeSize);

        // Verify that there are no errors
        Assert.assertThat("Error events",clientSocket.errorQueue,empty());

        // client idle timeout triggers close event on client ws-endpoint
        // client close event on ws-endpoint
        clientSocket.assertReceivedCloseEvent(timeout,
                anyOf(is(StatusCode.SHUTDOWN),is(StatusCode.ABNORMAL)),
                anyOf(containsString("Timeout"),containsString("timeout"),containsString("Write")));
    }

    @Test
    public void testProtocolException() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        // Client connects
        CloseTrackingSocket clientSocket = new CloseTrackingSocket();
        Future<Session> clientConnectFuture = client.connect(clientSocket,server.getWsUri());

        // Server accepts connect
        IBlockheadServerConnection serverConn = server.accept();
        serverConn.upgrade();

        // client confirms connection via echo
        confirmConnection(clientSocket,clientConnectFuture,serverConn);

        // client should not have received close message (yet)
        clientSocket.assertNoCloseEvent();

        // server sends bad close frame (too big of a reason message)
        byte msg[] = new byte[400];
        Arrays.fill(msg,(byte)'x');
        ByteBuffer bad = ByteBuffer.allocate(500);
        RawFrameBuilder.putOpFin(bad,OpCode.CLOSE,true);
        RawFrameBuilder.putLength(bad,msg.length + 2,false);
        bad.putShort((short)StatusCode.NORMAL);
        bad.put(msg);
        BufferUtil.flipToFlush(bad,0);
        try (StacklessLogging quiet = new StacklessLogging(Parser.class))
        {
            serverConn.write(bad);

            // client should have noticed the error
            clientSocket.assertReceivedError(ProtocolException.class,containsString("Invalid control frame"));

            // client parse invalid frame, notifies server of close (protocol error)
            confirmServerReceivedCloseFrame(serverConn,StatusCode.PROTOCOL,allOf(containsString("Invalid control frame"),containsString("length")));
        }

        // server disconnects
        serverConn.disconnect();

        // client triggers close event on client ws-endpoint
        clientSocket.assertReceivedCloseEvent(timeout,is(StatusCode.PROTOCOL),allOf(containsString("Invalid control frame"),containsString("length")));
    }

    @Test
    public void testReadEOF() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        // Client connects
        CloseTrackingSocket clientSocket = new CloseTrackingSocket();
        Future<Session> clientConnectFuture = client.connect(clientSocket,server.getWsUri());

        // Server accepts connect
        IBlockheadServerConnection serverConn = server.accept();
        serverConn.upgrade();

        // client confirms connection via echo
        confirmConnection(clientSocket,clientConnectFuture,serverConn);

        // client sends close frame
        final String origCloseReason = "Normal Close";
        clientSocket.getSession().close(StatusCode.NORMAL,origCloseReason);

        // server receives close frame
        confirmServerReceivedCloseFrame(serverConn,StatusCode.NORMAL,is(origCloseReason));

        // client should not have received close message (yet)
        clientSocket.assertNoCloseEvent();

        // server shuts down connection (no frame reply)
        serverConn.disconnect();

        // client reads -1 (EOF)
        // client triggers close event on client ws-endpoint
        clientSocket.assertReceivedCloseEvent(timeout,is(StatusCode.ABNORMAL),containsString("EOF"));
    }

    @Test
    // TODO work out why this test is failing
    @Ignore
    public void testServerNoCloseHandshake() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        // Client connects
        CloseTrackingSocket clientSocket = new CloseTrackingSocket();
        Future<Session> clientConnectFuture = client.connect(clientSocket,server.getWsUri());

        // Server accepts connect
        IBlockheadServerConnection serverConn = server.accept();
        serverConn.upgrade();

        // client confirms connection via echo
        confirmConnection(clientSocket,clientConnectFuture,serverConn);

        // client sends close frame
        final String origCloseReason = "Normal Close";
        clientSocket.getSession().close(StatusCode.NORMAL,origCloseReason);

        // server receives close frame
        confirmServerReceivedCloseFrame(serverConn,StatusCode.NORMAL,is(origCloseReason));

        // client should not have received close message (yet)
        clientSocket.assertNoCloseEvent();

        // server never sends close frame handshake
        // server sits idle

        // client idle timeout triggers close event on client ws-endpoint
        clientSocket.assertReceivedCloseEvent(timeout,is(StatusCode.SHUTDOWN),containsString("Timeout"));
    }

    @Test(timeout = 5000L)
    public void testStopLifecycle() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        int clientCount = 3;
        CloseTrackingSocket clientSockets[] = new CloseTrackingSocket[clientCount];
        IBlockheadServerConnection serverConns[] = new IBlockheadServerConnection[clientCount];

        // Connect Multiple Clients
        for (int i = 0; i < clientCount; i++)
        {
            // Client Request Upgrade
            clientSockets[i] = new CloseTrackingSocket();
            Future<Session> clientConnectFuture = client.connect(clientSockets[i],server.getWsUri());

            // Server accepts connection
            serverConns[i] = server.accept();
            serverConns[i].upgrade();

            // client confirms connection via echo
            confirmConnection(clientSockets[i],clientConnectFuture,serverConns[i]);
        }

        // client lifecycle stop
        client.stop();

        // clients send close frames (code 1001, shutdown)
        for (int i = 0; i < clientCount; i++)
        {
            // server receives close frame
            confirmServerReceivedCloseFrame(serverConns[i],StatusCode.SHUTDOWN,containsString("Shutdown"));
        }

        // clients disconnect
        for (int i = 0; i < clientCount; i++)
        {
            clientSockets[i].assertReceivedCloseEvent(timeout,is(StatusCode.SHUTDOWN),containsString("Shutdown"));
        }
    }

    @Test
    public void testWriteException() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        // Client connects
        CloseTrackingSocket clientSocket = new CloseTrackingSocket();
        Future<Session> clientConnectFuture = client.connect(clientSocket,server.getWsUri());

        // Server accepts connect
        IBlockheadServerConnection serverConn = server.accept();
        serverConn.upgrade();

        // client confirms connection via echo
        confirmConnection(clientSocket,clientConnectFuture,serverConn);

        // setup client endpoint for write failure (test only)
        EndPoint endp = clientSocket.getEndPoint();
        endp.shutdownOutput();

        // client enqueue close frame
        // client write failure
        final String origCloseReason = "Normal Close";
        clientSocket.getSession().close(StatusCode.NORMAL,origCloseReason);

        clientSocket.assertReceivedError(EofException.class,null);

        // client triggers close event on client ws-endpoint
        // assert - close code==1006 (abnormal)
        // assert - close reason message contains (write failure)
        clientSocket.assertReceivedCloseEvent(timeout,is(StatusCode.ABNORMAL),containsString("EOF"));
    }
}
