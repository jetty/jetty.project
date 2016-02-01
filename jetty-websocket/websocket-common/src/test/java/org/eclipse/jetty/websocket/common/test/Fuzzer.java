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

package org.eclipse.jetty.websocket.common.test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.io.IOState;
import org.junit.Assert;

/**
 * Fuzzing utility for the AB tests.
 */
public class Fuzzer implements AutoCloseable
{
    public static enum CloseState
    {
        OPEN,
        REMOTE_INITIATED,
        LOCAL_INITIATED
    }

    public static enum SendMode
    {
        BULK,
        PER_FRAME,
        SLOW
    }

    public static enum DisconnectMode
    {
        /** Disconnect occurred after a proper close handshake */
        CLEAN,
        /** Disconnect occurred in a harsh manner, without a close handshake */
        UNCLEAN
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

    public Fuzzer(Fuzzed testcase) throws Exception
    {
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();

        int bigMessageSize = 20 * MBYTE;

        policy.setMaxTextMessageSize(bigMessageSize);
        policy.setMaxBinaryMessageSize(bigMessageSize);
        policy.setIdleTimeout(5000);

        this.client = new BlockheadClient(policy,testcase.getServerURI());
        this.client.setTimeout(2,TimeUnit.SECONDS);
        this.generator = testcase.getLaxGenerator();
        this.testname = testcase.getTestMethodName();
    }

    public ByteBuffer asNetworkBuffer(List<WebSocketFrame> send)
    {
        int buflen = 0;
        for (Frame f : send)
        {
            buflen += f.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
        }
        ByteBuffer buf = ByteBuffer.allocate(buflen);

        // Generate frames
        for (WebSocketFrame f : send)
        {
            setClientMask(f);
            generator.generateWholeFrame(f,buf);
        }
        buf.flip();
        return buf;
    }
    
    @Override
    public void close() throws Exception
    {
        this.client.disconnect();
    }

    public void disconnect()
    {
        this.client.disconnect();
    }

    public void connect() throws IOException
    {
        if (!client.isConnected())
        {
            client.connect();
            client.addHeader("X-TestCase: " + testname + "\r\n");
            client.sendStandardRequest();
            client.expectUpgradeResponse();
        }
    }

    public void expect(List<WebSocketFrame> expect) throws Exception
    {
        expect(expect,10,TimeUnit.SECONDS);
    }

    public void expect(List<WebSocketFrame> expect, int duration, TimeUnit unit) throws Exception
    {
        int expectedCount = expect.size();
        LOG.debug("expect() {} frame(s)",expect.size());

        // Read frames
        EventQueue<WebSocketFrame> frames = client.readFrames(expect.size(),duration,unit);
        
        String prefix = "";
        for (int i = 0; i < expectedCount; i++)
        {
            WebSocketFrame expected = expect.get(i);
            WebSocketFrame actual = frames.poll();

            prefix = "Frame[" + i + "]";

            LOG.debug("{} {}",prefix,actual);

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

    public void expect(WebSocketFrame expect) throws Exception
    {
        expect(Collections.singletonList(expect));
    }

    public void expectNoMoreFrames()
    {
        // TODO Should test for no more frames. success if connection closed.
    }

    public CloseState getCloseState()
    {
        IOState ios = client.getIOState();

        if (ios.wasLocalCloseInitiated())
        {
            return CloseState.LOCAL_INITIATED;
        }
        else if (ios.wasRemoteCloseInitiated())
        {
            return CloseState.REMOTE_INITIATED;
        }
        else
        {
            return CloseState.OPEN;
        }
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
            for (Frame f : send)
            {
                buflen += f.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
            }
            ByteBuffer buf = ByteBuffer.allocate(buflen);

            // Generate frames
            for (WebSocketFrame f : send)
            {
                setClientMask(f);
                buf.put(generator.generateHeaderBytes(f));
                if (f.hasPayload())
                {
                    buf.put(f.getPayload());
                }
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
                default:
                    throw new RuntimeException("Whoops, unsupported sendMode: " + sendMode);
            }
        }
        else if (sendMode == SendMode.PER_FRAME)
        {
            for (WebSocketFrame f : send)
            {
                f.setMask(MASK); // make sure we have mask set
                // Using lax generator, generate and send
                ByteBuffer fullframe = ByteBuffer.allocate(f.getPayloadLength() + Generator.MAX_HEADER_LENGTH);
                BufferUtil.clearToFill(fullframe);
                generator.generateWholeFrame(f,fullframe);
                BufferUtil.flipToFlush(fullframe,0);
                client.writeRaw(fullframe);
                client.flush();
            }
        }
    }

    public void send(WebSocketFrame send) throws IOException
    {
        send(Collections.singletonList(send));
    }

    public void sendAndIgnoreBrokenPipe(List<WebSocketFrame> send) throws IOException
    {
        try
        {
            send(send);
        }
        catch (SocketException ignore)
        {
            // Potential for SocketException (Broken Pipe) here.
            // But not in 100% of testing scenarios. It is a safe
            // exception to ignore in this testing scenario, as the
            // slow writing of the frames can result in the server
            // throwing a PROTOCOL ERROR termination/close when it
            // encounters the bad continuation frame above (this
            // termination is the expected behavior), and this
            // early socket close can propagate back to the client
            // before it has a chance to finish writing out the
            // remaining frame octets
            Assert.assertThat("Allowed to be a broken pipe",ignore.getMessage().toLowerCase(Locale.ENGLISH),containsString("broken pipe"));
        }
    }

    private void setClientMask(WebSocketFrame f)
    {
        if (LOG.isDebugEnabled())
        {
            f.setMask(new byte[]
            { 0x00, 0x00, 0x00, 0x00 });
        }
        else
        {
            f.setMask(MASK); // make sure we have mask set
        }
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
