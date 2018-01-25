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

import static org.hamcrest.Matchers.anything;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

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
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketChannel;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.core.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.core.io.WebSocketConnection;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.CoreUtils;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.eclipse.jetty.websocket.tests.TrackingFrameHandler;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 * Test various <a href="http://tools.ietf.org/html/rfc6455">RFC 6455</a> specified requirements placed on {@link WebSocketServlet}
 */
public class WebSocketServletRFCTest
{
    private static SimpleServletServer server;

    @BeforeClass
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

    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Rule
    public TestName testname = new TestName();

    private WebSocketCoreClient client;

    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketCoreClient();
        client.start();
    }

    @After
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
    public void testBinaryAggregate() throws Exception
    {
        TrackingFrameHandler clientTracking = new TrackingFrameHandler(testname.getMethodName());

        URI wsUri = server.getWsUri();

        Future<FrameHandler.Channel> clientConnectFuture = client.connect(clientTracking, wsUri);

        FrameHandler.Channel channel = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        try
        {


            // Generate binary frames
            byte buf1[] = new byte[128];
            byte buf2[] = new byte[128];
            byte buf3[] = new byte[128];

            Arrays.fill(buf1, (byte) 0xAA);
            Arrays.fill(buf2, (byte) 0xBB);
            Arrays.fill(buf3, (byte) 0xCC);

            WebSocketFrame bin;

            bin = new BinaryFrame().setPayload(buf1).setFin(false);
            channel.sendFrame(bin, Callback.NOOP, BatchMode.OFF); // write buf1 (fin=false)

            bin = new ContinuationFrame().setPayload(buf2).setFin(false);
            channel.sendFrame(bin, Callback.NOOP, BatchMode.OFF); // write buf2 (fin=false)

            bin = new ContinuationFrame().setPayload(buf3).setFin(true);
            channel.sendFrame(bin, Callback.NOOP, BatchMode.OFF); // write buf3 (fin=true)

            // Read frame echo'd back (hopefully a single binary frame)
            WebSocketFrame incomingFrame = clientTracking.framesQueue.poll(5, TimeUnit.SECONDS);

            int expectedSize = buf1.length + buf2.length + buf3.length;
            assertThat("BinaryFrame.payloadLength", incomingFrame.getPayloadLength(), is(expectedSize));

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
                        Assert.fail(String.format("Encountered invalid byte 0x%02X", (byte) (0xFF & b)));
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

    @Test(expected = NotUtf8Exception.class)
    public void testDetectBadUTF8()
    {
        byte buf[] = new byte[]
                {(byte) 0xC2, (byte) 0xC3};

        Utf8StringBuilder utf = new Utf8StringBuilder();
        utf.append(buf, 0, buf.length);
    }

    /**
     * Test the requirement of responding with server terminated close code 1011 when there is an unhandled (internal server error) being produced by the
     * WebSocket POJO.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testInternalError() throws Exception
    {
        TrackingFrameHandler clientTracking = new TrackingFrameHandler(testname.getMethodName());
        URI wsUri = server.getWsUri();

        WebSocketCoreClientUpgradeRequest upgradeRequest = new WebSocketCoreClientUpgradeRequest.Static(client, wsUri, clientTracking);
        Future<FrameHandler.Channel> channelFuture = client.connect(upgradeRequest);

        FrameHandler.Channel channel = channelFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        try (StacklessLogging ignored = new StacklessLogging(RFC6455Socket.class))
        {
            channel.sendFrame(new TextFrame().setPayload("CRASH"), Callback.NOOP, BatchMode.OFF);

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
    public void testLowercaseUpgrade() throws Exception
    {
        TrackingFrameHandler clientTracking = new TrackingFrameHandler(testname.getMethodName());
        URI wsUri = server.getWsUri();

        WebSocketCoreClientUpgradeRequest upgradeRequest = new WebSocketCoreClientUpgradeRequest.Static(client, wsUri, clientTracking);
        upgradeRequest.header("upgrade", "websocket");
        upgradeRequest.header("connection", "upgrade");
        upgradeRequest.header("sec-websocket-key", Defaults.getStaticWebSocketKey());
        upgradeRequest.header("sec-websocket-origin", wsUri.toASCIIString());
        upgradeRequest.header("sec-websocket-protocol", "echo");
        upgradeRequest.header("sec-websocket-version", "13");

        Future<FrameHandler.Channel> channelFuture = client.connect(upgradeRequest);

        FrameHandler.Channel channel = channelFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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
    public void testUppercaseUpgrade() throws Exception
    {
        TrackingFrameHandler clientTracking = new TrackingFrameHandler(testname.getMethodName());
        URI wsUri = server.getWsUri();

        WebSocketCoreClientUpgradeRequest upgradeRequest = new WebSocketCoreClientUpgradeRequest.Static(client, wsUri, clientTracking);
        upgradeRequest.header("UPGRADE", "WEBSOCKET");
        upgradeRequest.header("CONNECTION", "UPGRADE");
        upgradeRequest.header("SEC-WEBSOCKET-KEY", Defaults.getStaticWebSocketKey());
        upgradeRequest.header("SEC-WEBSOCKET-ORIGIN", wsUri.toASCIIString());
        upgradeRequest.header("SEC-WEBSOCKET-PROTOCOL", "ECHO");
        upgradeRequest.header("SEC-WEBSOCKET-VERSION", "13");

        Future<FrameHandler.Channel> channelFuture = client.connect(upgradeRequest);

        FrameHandler.Channel channel = channelFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        confirmEcho(channel, clientTracking);
    }

    protected void confirmEcho(FrameHandler.Channel channel, TrackingFrameHandler clientTracking) throws InterruptedException, ExecutionException, TimeoutException
    {
        try
        {
            // Generate text frame
            String msg = "this is an echo ... cho ... ho ... o";
            channel.sendFrame(new TextFrame().setPayload(msg), Callback.NOOP, BatchMode.OFF);

            // Read frame (hopefully text frame)
            WebSocketFrame frame = clientTracking.framesQueue.poll(5, TimeUnit.SECONDS);
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
    public void testTextNotUTF8() throws Exception
    {
        TrackingFrameHandler clientTracking = new TrackingFrameHandler(testname.getMethodName());
        URI wsUri = server.getWsUri();

        WebSocketCoreClientUpgradeRequest upgradeRequest = new WebSocketCoreClientUpgradeRequest.Static(client, wsUri, clientTracking)
        {
            @Override
            protected WebSocketConnection newWebSocketConnection(EndPoint endp, Executor executor, ByteBufferPool byteBufferPool, WebSocketChannel wsChannel)
            {
                // Disable validating on this specific websocket connection
                return new WebSocketConnection(endp, executor, byteBufferPool, wsChannel, false);
            }
        };

        upgradeRequest.setSubProtocols("other");

        Future<FrameHandler.Channel> channelFuture = client.connect(upgradeRequest);

        FrameHandler.Channel channel = channelFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        byte buf[] = new byte[]{(byte) 0xC2, (byte) 0xC3};

        try (StacklessLogging ignored = new StacklessLogging(RFC6455Socket.class))
        {
            WebSocketFrame txt = new TextFrame().setPayload(ByteBuffer.wrap(buf));

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
