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

package org.eclipse.jetty.websocket.tests.server;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.core.BatchMode;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.internal.WebSocketChannel;
import org.eclipse.jetty.websocket.core.internal.WebSocketConnection;
import org.eclipse.jetty.websocket.core.client.UpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.client.AbstractUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.CoreUtils;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.eclipse.jetty.websocket.tests.TrackingFrameHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anything;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Test various <a href="http://tools.ietf.org/html/rfc6455">RFC 6455</a> specified requirements placed on {@link WebSocketServlet}
 */
public class WebSocketServletRFCTest
{
    private static SimpleServletServer server;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new WebSocketServlet()
        {
            @Override
            public void configure(WebSocketServletFactory factory)
            {
                factory.register(RFC6455Socket.class);
            }
        });
        server.start();
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    private WebSocketCoreClient client;

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketCoreClient();
        client.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    /**
     * Test that aggregation of binary frames into a single message occurs
     *
     * @throws Exception on test failure
     */
    @Test
    public void testBinaryAggregate(TestInfo testInfo) throws Exception
    {
        TrackingFrameHandler clientTracking = new TrackingFrameHandler(testInfo.getTestMethod().toString());

        URI wsUri = server.getWsUri();

        Future<FrameHandler.CoreSession> clientConnectFuture = client.connect(clientTracking, wsUri);

        FrameHandler.CoreSession channel = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        try
        {


            // Generate binary frames
            byte buf1[] = new byte[128];
            byte buf2[] = new byte[128];
            byte buf3[] = new byte[128];

            Arrays.fill(buf1, (byte) 0xAA);
            Arrays.fill(buf2, (byte) 0xBB);
            Arrays.fill(buf3, (byte) 0xCC);

            Frame bin;

            bin = new Frame(OpCode.BINARY).setPayload(buf1).setFin(false);
            channel.sendFrame(bin, Callback.NOOP, BatchMode.OFF); // write buf1 (fin=false)

            bin = new Frame(OpCode.CONTINUATION).setPayload(buf2).setFin(false);
            channel.sendFrame(bin, Callback.NOOP, BatchMode.OFF); // write buf2 (fin=false)

            bin = new Frame(OpCode.CONTINUATION).setPayload(buf3).setFin(true);
            channel.sendFrame(bin, Callback.NOOP, BatchMode.OFF); // write buf3 (fin=true)

            // Read frame echo'd back (hopefully a single binary frame)
            Frame incomingFrame = clientTracking.framesQueue.poll(5, TimeUnit.SECONDS);

            int expectedSize = buf1.length + buf2.length + buf3.length;
            assertThat("Frame.payloadLength", incomingFrame.getPayloadLength(), is(expectedSize));

            int aaCount = 0;
            int bbCount = 0;
            int ccCount = 0;

            ByteBuffer echod = incomingFrame.getPayload();
            while (echod.remaining() >= 1)
            {
                byte b = echod.get();
                switch (b)
                {
                    case (byte) 0xAA:
                        aaCount++;
                        break;
                    case (byte) 0xBB:
                        bbCount++;
                        break;
                    case (byte) 0xCC:
                        ccCount++;
                        break;
                    default:
                        fail(String.format("Encountered invalid byte 0x%02X", (byte) (0xFF & b)));
                }
            }

            assertThat("Echoed data count for 0xAA", aaCount, is(buf1.length));
            assertThat("Echoed data count for 0xBB", bbCount, is(buf2.length));
            assertThat("Echoed data count for 0xCC", ccCount, is(buf3.length));
        }
        finally
        {
            CoreUtils.close(channel, 1, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testDetectBadUTF8()
    {
        assertThrows(NotUtf8Exception.class, ()->
        {
            byte buf[] = new byte[]
                    {(byte)0xC2, (byte)0xC3};

            Utf8StringBuilder utf = new Utf8StringBuilder();
            utf.append(buf, 0, buf.length);
        });
    }

    /**
     * Test the requirement of responding with server terminated close code 1011 when there is an unhandled (internal server error) being produced by the
     * WebSocket POJO.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testInternalError(TestInfo testInfo) throws Exception
    {
        TrackingFrameHandler clientTracking = new TrackingFrameHandler(testInfo.getTestMethod().toString());
        URI wsUri = server.getWsUri();

        AbstractUpgradeRequest upgradeRequest = new UpgradeRequest(client, wsUri, clientTracking);
        Future<FrameHandler.CoreSession> channelFuture = client.connect(upgradeRequest);

        FrameHandler.CoreSession channel = channelFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        try (StacklessLogging ignored = new StacklessLogging(RFC6455Socket.class))
        {
            channel.sendFrame(new Frame(OpCode.TEXT).setPayload("CRASH"), Callback.NOOP, BatchMode.OFF);

            clientTracking.awaitClosedEvent("Client");
            clientTracking.assertCloseStatus("Client", StatusCode.SERVER_ERROR, anything());
        }
        finally
        {
            CoreUtils.close(channel, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Test http://tools.ietf.org/html/rfc6455#section-4.1 where server side upgrade handling is supposed to be case insensitive.
     * <p>
     * This test will simulate a client requesting upgrade with all lowercase headers.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testLowercaseUpgrade(TestInfo testInfo) throws Exception
    {
        TrackingFrameHandler clientTracking = new TrackingFrameHandler(testInfo.getTestMethod().toString());
        URI wsUri = server.getWsUri();

        AbstractUpgradeRequest upgradeRequest = new UpgradeRequest(client, wsUri, clientTracking);
        upgradeRequest.header("upgrade", "websocket");
        upgradeRequest.header("connection", "upgrade");
        upgradeRequest.header("sec-websocket-key", Defaults.getStaticWebSocketKey());
        upgradeRequest.header("sec-websocket-origin", wsUri.toASCIIString());
        upgradeRequest.header("sec-websocket-version", "13");

        Future<FrameHandler.CoreSession> channelFuture = client.connect(upgradeRequest);

        FrameHandler.CoreSession channel = channelFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        confirmEcho(channel, clientTracking);
    }


    /**
     * Test http://tools.ietf.org/html/rfc6455#section-4.1 where server side upgrade handling is supposed to be case insensitive.
     * <p>
     * This test will simulate a client requesting upgrade with all uppercase headers.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testUppercaseUpgrade(TestInfo testInfo) throws Exception
    {
        TrackingFrameHandler clientTracking = new TrackingFrameHandler(testInfo.getTestMethod().toString());
        URI wsUri = server.getWsUri();

        AbstractUpgradeRequest upgradeRequest = new UpgradeRequest(client, wsUri, clientTracking);
        upgradeRequest.header("UPGRADE", "WEBSOCKET");
        upgradeRequest.header("CONNECTION", "UPGRADE");
        upgradeRequest.header("SEC-WEBSOCKET-KEY", Defaults.getStaticWebSocketKey());
        upgradeRequest.header("SEC-WEBSOCKET-ORIGIN", wsUri.toASCIIString());
        upgradeRequest.header("SEC-WEBSOCKET-VERSION", "13");

        Future<FrameHandler.CoreSession> channelFuture = client.connect(upgradeRequest);

        FrameHandler.CoreSession channel = channelFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        confirmEcho(channel, clientTracking);
    }

    protected void confirmEcho(FrameHandler.CoreSession channel, TrackingFrameHandler clientTracking) throws InterruptedException, ExecutionException, TimeoutException
    {
        try
        {
            // Generate text frame
            String msg = "this is an echo ... cho ... ho ... o";
            channel.sendFrame(new Frame(OpCode.TEXT).setPayload(msg), Callback.NOOP, BatchMode.OFF);

            // Read frame (hopefully text frame)
            Frame frame = clientTracking.framesQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Frame.opCode", frame.getOpCode(), is(OpCode.TEXT));
            String incomingMessage = frame.getPayloadAsUTF8();
            assertThat("Incoming Message", incomingMessage, is(msg));
        }
        finally
        {
            CoreUtils.close(channel, 1, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testTextNotUTF8(TestInfo testInfo) throws Exception
    {
        TrackingFrameHandler clientTracking = new TrackingFrameHandler(testInfo.getTestMethod().toString());
        URI wsUri = server.getWsUri();

        AbstractUpgradeRequest upgradeRequest = new UpgradeRequest(client, wsUri, clientTracking)
        {
            @Override
            protected WebSocketConnection newWebSocketConnection(EndPoint endp, Executor executor, ByteBufferPool byteBufferPool, WebSocketChannel wsChannel)
            {
                // Disable validating on this specific websocket connection
                return new WebSocketConnection(endp, executor, byteBufferPool, wsChannel, false);
            }
        };

        Future<FrameHandler.CoreSession> channelFuture = client.connect(upgradeRequest);

        FrameHandler.CoreSession channel = channelFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        byte buf[] = new byte[]{(byte) 0xC2, (byte) 0xC3};

        try (StacklessLogging ignored = new StacklessLogging(RFC6455Socket.class))
        {
            Frame txt = new Frame(OpCode.TEXT).setPayload(ByteBuffer.wrap(buf));

            channel.sendFrame(txt, Callback.NOOP, BatchMode.OFF);

            clientTracking.awaitClosedEvent("Client");
            clientTracking.assertCloseStatus("Client", StatusCode.BAD_PAYLOAD, anything());
        }
        finally
        {
            CoreUtils.close(channel, 1, TimeUnit.SECONDS);
        }
    }
}
