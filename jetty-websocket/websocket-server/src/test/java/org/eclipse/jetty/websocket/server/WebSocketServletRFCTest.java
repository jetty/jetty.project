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

package org.eclipse.jetty.websocket.server;

import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.frames.BinaryFrame;
import org.eclipse.jetty.websocket.common.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.common.test.UnitGenerator;
import org.eclipse.jetty.websocket.common.util.Hex;
import org.eclipse.jetty.websocket.server.helper.RFCServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test various <a href="http://tools.ietf.org/html/rfc6455">RFC 6455</a> specified requirements placed on {@link WebSocketServlet}
 */
@RunWith(AdvancedRunner.class)
public class WebSocketServletRFCTest
{
    private static Generator generator = new UnitGenerator();
    private static SimpleServletServer server;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new RFCServlet());
        server.start();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    /**
     * @param clazz the class to enable
     * @param enabled true to enable the stack traces (or not)
     * @deprecated use {@link StacklessLogging} in a try-with-resources block instead
     */
    @Deprecated
    private void enableStacks(Class<?> clazz, boolean enabled)
    {
        StdErrLog log = StdErrLog.getLogger(clazz);
        log.setHideStacks(!enabled);
    }

    /**
     * Test that aggregation of binary frames into a single message occurs
     * @throws Exception on test failure
     */
    @Test
    public void testBinaryAggregate() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Generate binary frames
            byte buf1[] = new byte[128];
            byte buf2[] = new byte[128];
            byte buf3[] = new byte[128];

            Arrays.fill(buf1,(byte)0xAA);
            Arrays.fill(buf2,(byte)0xBB);
            Arrays.fill(buf3,(byte)0xCC);

            WebSocketFrame bin;

            bin = new BinaryFrame().setPayload(buf1).setFin(false);

            client.write(bin); // write buf1 (fin=false)

            bin = new ContinuationFrame().setPayload(buf2).setFin(false);

            client.write(bin); // write buf2 (fin=false)

            bin = new ContinuationFrame().setPayload(buf3).setFin(true);

            client.write(bin); // write buf3 (fin=true)

            // Read frame echo'd back (hopefully a single binary frame)
            EventQueue<WebSocketFrame> frames = client.readFrames(1,1000,TimeUnit.MILLISECONDS);
            Frame binmsg = frames.poll();
            int expectedSize = buf1.length + buf2.length + buf3.length;
            Assert.assertThat("BinaryFrame.payloadLength",binmsg.getPayloadLength(),is(expectedSize));

            int aaCount = 0;
            int bbCount = 0;
            int ccCount = 0;

            ByteBuffer echod = binmsg.getPayload();
            while (echod.remaining() >= 1)
            {
                byte b = echod.get();
                switch (b)
                {
                    case (byte)0xAA:
                        aaCount++;
                        break;
                    case (byte)0xBB:
                        bbCount++;
                        break;
                    case (byte)0xCC:
                        ccCount++;
                        break;
                    default:
                        Assert.fail(String.format("Encountered invalid byte 0x%02X",(byte)(0xFF & b)));
                }
            }
            Assert.assertThat("Echoed data count for 0xAA",aaCount,is(buf1.length));
            Assert.assertThat("Echoed data count for 0xBB",bbCount,is(buf2.length));
            Assert.assertThat("Echoed data count for 0xCC",ccCount,is(buf3.length));
        }
        finally
        {
            client.close();
        }
    }

    @Test(expected = NotUtf8Exception.class)
    public void testDetectBadUTF8()
    {
        byte buf[] = new byte[]
        { (byte)0xC2, (byte)0xC3 };

        Utf8StringBuilder utf = new Utf8StringBuilder();
        utf.append(buf,0,buf.length);
    }

    /**
     * Test the requirement of issuing socket and receiving echo response
     * @throws Exception on test failure
     */
    @Test
    public void testEcho() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Generate text frame
            String msg = "this is an echo ... cho ... ho ... o";
            client.write(new TextFrame().setPayload(msg));

            // Read frame (hopefully text frame)
            EventQueue<WebSocketFrame> frames = client.readFrames(1,30,TimeUnit.SECONDS);
            WebSocketFrame tf = frames.poll();
            Assert.assertThat("Text Frame.status code",tf.getPayloadAsUTF8(),is(msg));
        }
        finally
        {
            client.close();
        }
    }

    /**
     * Test the requirement of responding with server terminated close code 1011 when there is an unhandled (internal server error) being produced by the
     * WebSocket POJO.
     * @throws Exception on test failure
     */
    @Test
    public void testInternalError() throws Exception
    {
        try (BlockheadClient client = new BlockheadClient(server.getServerUri());
             StacklessLogging stackless=new StacklessLogging(EventDriver.class))
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            try (StacklessLogging context = new StacklessLogging(EventDriver.class))
            {
                // Generate text frame
                client.write(new TextFrame().setPayload("CRASH"));

                // Read frame (hopefully close frame)
                EventQueue<WebSocketFrame> frames = client.readFrames(1,30,TimeUnit.SECONDS);
                Frame cf = frames.poll();
                CloseInfo close = new CloseInfo(cf);
                Assert.assertThat("Close Frame.status code",close.getStatusCode(),is(StatusCode.SERVER_ERROR));
            }
        }
    }

    /**
     * Test http://tools.ietf.org/html/rfc6455#section-4.1 where server side upgrade handling is supposed to be case insensitive.
     * <p>
     * This test will simulate a client requesting upgrade with all lowercase headers.
     * @throws Exception on test failure
     */
    @Test
    public void testLowercaseUpgrade() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();

            StringBuilder req = new StringBuilder();
            req.append("GET ").append(client.getRequestPath()).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(client.getRequestHost()).append("\r\n");
            req.append("Upgrade: websocket\r\n");
            req.append("connection: upgrade\r\n");
            req.append("sec-websocket-key: ").append(client.getRequestWebSocketKey()).append("\r\n");
            req.append("sec-websocket-origin: ").append(client.getRequestWebSocketOrigin()).append("\r\n");
            req.append("sec-websocket-protocol: echo\r\n");
            req.append("sec-websocket-version: 13\r\n");
            req.append("\r\n");
            client.writeRaw(req.toString());

            client.expectUpgradeResponse();

            // Generate text frame
            String msg = "this is an echo ... cho ... ho ... o";
            client.write(new TextFrame().setPayload(msg));

            // Read frame (hopefully text frame)
            EventQueue<WebSocketFrame> frames = client.readFrames(1,30,TimeUnit.SECONDS);
            WebSocketFrame tf = frames.poll();
            Assert.assertThat("Text Frame.status code",tf.getPayloadAsUTF8(),is(msg));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testTextNotUTF8() throws Exception
    {
        try (StacklessLogging stackless=new StacklessLogging(Parser.class);
             BlockheadClient client = new BlockheadClient(server.getServerUri()))
        {
            client.setProtocols("other");
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            byte buf[] = new byte[]
            { (byte)0xC2, (byte)0xC3 };

            WebSocketFrame txt = new TextFrame().setPayload(ByteBuffer.wrap(buf));
            txt.setMask(Hex.asByteArray("11223344"));
            ByteBuffer bbHeader = generator.generateHeaderBytes(txt);
            client.writeRaw(bbHeader);
            client.writeRaw(txt.getPayload());

            EventQueue<WebSocketFrame> frames = client.readFrames(1,1,TimeUnit.SECONDS);
            WebSocketFrame frame = frames.poll();
            Assert.assertThat("frames[0].opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            Assert.assertThat("Close Status Code",close.getStatusCode(),is(StatusCode.BAD_PAYLOAD));
        }
    }

    /**
     * Test http://tools.ietf.org/html/rfc6455#section-4.1 where server side upgrade handling is supposed to be case insensitive.
     * <p>
     * This test will simulate a client requesting upgrade with all uppercase headers.
     * @throws Exception on test failure
     */
    @Test
    public void testUppercaseUpgrade() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();

            StringBuilder req = new StringBuilder();
            req.append("GET ").append(client.getRequestPath()).append(" HTTP/1.1\r\n");
            req.append("HOST: ").append(client.getRequestHost()).append("\r\n");
            req.append("UPGRADE: WEBSOCKET\r\n");
            req.append("CONNECTION: UPGRADE\r\n");
            req.append("SEC-WEBSOCKET-KEY: ").append(client.getRequestWebSocketKey()).append("\r\n");
            req.append("SEC-WEBSOCKET-ORIGIN: ").append(client.getRequestWebSocketOrigin()).append("\r\n");
            req.append("SEC-WEBSOCKET-PROTOCOL: ECHO\r\n");
            req.append("SEC-WEBSOCKET-VERSION: 13\r\n");
            req.append("\r\n");
            client.writeRaw(req.toString());

            client.expectUpgradeResponse();

            // Generate text frame
            String msg = "this is an echo ... cho ... ho ... o";
            client.write(new TextFrame().setPayload(msg));

            // Read frame (hopefully text frame)
            EventQueue<WebSocketFrame> frames = client.readFrames(1,30,TimeUnit.SECONDS);
            WebSocketFrame tf = frames.poll();
            Assert.assertThat("Text Frame.status code",tf.getPayloadAsUTF8(),is(msg));
        }
        finally
        {
            client.close();
        }
    }
}
