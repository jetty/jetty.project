//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server.ab;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.protocol.CloseInfo;
import org.eclipse.jetty.websocket.protocol.Generator;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.eclipse.jetty.websocket.server.ByteBufferAssert;
import org.eclipse.jetty.websocket.server.blockhead.BlockheadClient;
import org.eclipse.jetty.websocket.server.helper.IncomingFramesCapture;
import org.junit.Assert;

import static org.hamcrest.Matchers.is;

/**
 * Fuzzing utility for the AB tests.
 */
public class Fuzzer
{
    public static enum SendMode
    {
        BULK,
        PER_FRAME,
        SLOW
    }

    private static final int KBYTE = 1024;
    private static final int MBYTE = KBYTE * KBYTE;

    private static final Logger LOG = Log.getLogger(Fuzzer.class);

    // Client side framing mask
    protected static final byte[] MASK =
    { 0x11, 0x22, 0x33, 0x44 };

    private final BlockheadClient client;
    private final Generator generator;
    private final String testname;
    private SendMode sendMode = SendMode.BULK;
    private int slowSendSegmentSize = 5;

    public Fuzzer(AbstractABCase testcase) throws Exception
    {
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();

        int bigMessageSize = 20 * MBYTE;

        policy.setBufferSize(bigMessageSize);
        policy.setMaxPayloadSize(bigMessageSize);
        policy.setMaxTextMessageSize(bigMessageSize);
        policy.setMaxBinaryMessageSize(bigMessageSize);

        this.client = new BlockheadClient(policy,testcase.getServer().getServerUri());
        this.generator = testcase.getLaxGenerator();
        this.testname = testcase.testname.getMethodName();
    }

    public ByteBuffer asNetworkBuffer(List<WebSocketFrame> send)
    {
        int buflen = 0;
        for (WebSocketFrame f : send)
        {
            buflen += f.getPayloadLength() + Generator.OVERHEAD;
        }
        ByteBuffer buf = ByteBuffer.allocate(buflen);
        BufferUtil.clearToFill(buf);

        // Generate frames
        for (WebSocketFrame f : send)
        {
            f.setMask(MASK); // make sure we have mask set
            BufferUtil.put(generator.generate(f),buf);
        }
        BufferUtil.flipToFlush(buf,0);
        return buf;
    }

    public void close()
    {
        this.client.disconnect();
    }

    public void connect() throws IOException
    {
        if (!client.isConnected())
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();
        }
    }

    public void expect(List<WebSocketFrame> expect) throws IOException, TimeoutException
    {
        expect(expect,TimeUnit.MILLISECONDS,500);
    }

    public void expect(List<WebSocketFrame> expect, TimeUnit unit, int duration) throws IOException, TimeoutException
    {
        int expectedCount = expect.size();

        // Read frames
        IncomingFramesCapture capture = client.readFrames(expect.size(),unit,duration);
        if (LOG.isDebugEnabled())
        {
            capture.dump();
        }

        String prefix = "";
        for (int i = 0; i < expectedCount; i++)
        {
            WebSocketFrame expected = expect.get(i);
            WebSocketFrame actual = capture.getFrames().pop();

            prefix = "Frame[" + i + "]";

            Assert.assertThat(prefix + ".opcode",OpCode.name(actual.getOpCode()),is(OpCode.name(expected.getOpCode())));
            prefix += "/" + actual.getOpCode();
            if (expected.getOpCode() == OpCode.CLOSE)
            {
                CloseInfo expectedClose = new CloseInfo(expected);
                CloseInfo actualClose = new CloseInfo(actual);
                Assert.assertThat(prefix + ".statusCode",actualClose.getStatusCode(),is(expectedClose.getStatusCode()));
            }
            else
            {
                Assert.assertThat(prefix + ".payloadLength",actual.getPayloadLength(),is(expected.getPayloadLength()));
                ByteBufferAssert.assertEquals(prefix + ".payload",expected.getPayload(),actual.getPayload());
            }
        }
    }

    public void expect(WebSocketFrame expect) throws IOException, TimeoutException
    {
        expect(Collections.singletonList(expect));
    }

    public void expectNoMoreFrames()
    {
        // TODO Should test for no more frames. success if connection closed.

    }

    public SendMode getSendMode()
    {
        return sendMode;
    }

    public int getSlowSendSegmentSize()
    {
        return slowSendSegmentSize;
    }

    public void send(ByteBuffer buf) throws IOException
    {
        Assert.assertThat("Client connected",client.isConnected(),is(true));
        LOG.debug("Sending bytes {}",BufferUtil.toDetailString(buf));
        if (sendMode == SendMode.SLOW)
        {
            client.writeRawSlowly(buf,slowSendSegmentSize);
        }
        else
        {
            client.writeRaw(buf);
        }
    }

    public void send(ByteBuffer buf, int numBytes) throws IOException
    {
        client.writeRaw(buf,numBytes);
        client.flush();
    }

    public void send(List<WebSocketFrame> send) throws IOException
    {
        Assert.assertThat("Client connected",client.isConnected(),is(true));
        LOG.debug("[{}] Sending {} frames (mode {})",testname,send.size(),sendMode);
        if ((sendMode == SendMode.BULK) || (sendMode == SendMode.SLOW))
        {
            int buflen = 0;
            for (WebSocketFrame f : send)
            {
                buflen += f.getPayloadLength() + Generator.OVERHEAD;
            }
            ByteBuffer buf = ByteBuffer.allocate(buflen);
            BufferUtil.clearToFill(buf);

            // Generate frames
            for (WebSocketFrame f : send)
            {
                f.setMask(MASK); // make sure we have mask set
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("payload: {}",BufferUtil.toDetailString(f.getPayload()));
                }
                BufferUtil.put(generator.generate(f),buf);
            }
            BufferUtil.flipToFlush(buf,0);

            // Write Data Frame
            switch (sendMode)
            {
                case BULK:
                    client.writeRaw(buf);
                    break;
                case SLOW:
                    client.writeRawSlowly(buf,slowSendSegmentSize);
                    break;
            }
        }
        else if (sendMode == SendMode.PER_FRAME)
        {
            for (WebSocketFrame f : send)
            {
                f.setMask(MASK); // make sure we have mask set
                // Using lax generator, generate and send
                client.writeRaw(generator.generate(f));
                client.flush();
            }
        }
    }

    public void send(WebSocketFrame send) throws IOException
    {
        send(Collections.singletonList(send));
    }

    public void setSendMode(SendMode sendMode)
    {
        this.sendMode = sendMode;
    }

    public void setSlowSendSegmentSize(int segmentSize)
    {
        this.slowSendSegmentSize = segmentSize;
    }
}
