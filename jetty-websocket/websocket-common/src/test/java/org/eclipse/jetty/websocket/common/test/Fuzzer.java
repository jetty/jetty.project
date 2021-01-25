//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Fuzzing utility for the AB tests.
 */
public class Fuzzer implements AutoCloseable
{
    public enum SendMode
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
        {0x11, 0x22, 0x33, 0x44};

    private final Fuzzed testcase;
    private final BlockheadClient client;
    private final Generator generator;
    private BlockheadConnection clientConnection;
    private SendMode sendMode = SendMode.BULK;
    private int slowSendSegmentSize = 5;

    public Fuzzer(Fuzzed testcase) throws Exception
    {
        this.testcase = testcase;
        this.client = new BlockheadClient();
        int bigMessageSize = 20 * MBYTE;

        client.getPolicy().setMaxTextMessageSize(bigMessageSize);
        client.getPolicy().setMaxBinaryMessageSize(bigMessageSize);
        client.getPolicy().setIdleTimeout(5000);

        client.setIdleTimeout(TimeUnit.SECONDS.toMillis(2));

        client.start();

        this.generator = testcase.getLaxGenerator();
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
            generator.generateWholeFrame(f, buf);
        }
        buf.flip();
        return buf;
    }

    @Override
    public void close()
    {
        this.clientConnection.close();
        try
        {
            this.client.stop();
        }
        catch (Exception ignore)
        {
            LOG.ignore(ignore);
        }
    }

    public void disconnect()
    {
        this.clientConnection.abort();
    }

    public void connect() throws IOException
    {
        BlockheadClientRequest request = this.client.newWsRequest(testcase.getServerURI());
        request.idleTimeout(2, TimeUnit.SECONDS);
        Future<BlockheadConnection> connFut = request.sendAsync();

        try
        {
            this.clientConnection = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT);
        }
        catch (InterruptedException e)
        {
            throw new IOException("Connect interrupted", e);
        }
        catch (ExecutionException e)
        {
            throw new IOException("Connect execution failed", e);
        }
        catch (TimeoutException e)
        {
            throw new IOException("Connect timed out", e);
        }
    }

    public void expect(List<WebSocketFrame> expect) throws Exception
    {
        expect(expect, Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
    }

    /**
     * Read the response frames and validate them against the expected frame list
     *
     * @param expect the list of expected frames
     * @param duration the timeout duration to wait for each read frame
     * @param unit the timeout unit to wait for each read frame
     * @throws Exception if unable to validate expectations
     */
    public void expect(List<WebSocketFrame> expect, long duration, TimeUnit unit) throws Exception
    {
        int expectedCount = expect.size();
        LOG.debug("expect() {} frame(s)", expect.size());

        // Read frames
        LinkedBlockingQueue<WebSocketFrame> frames = clientConnection.getFrameQueue();

        String prefix = "";
        for (int i = 0; i < expectedCount; i++)
        {
            WebSocketFrame expected = expect.get(i);
            WebSocketFrame actual = frames.poll(duration, unit);

            prefix = "Frame[" + i + "]";

            LOG.debug("{} {}", prefix, actual);

            assertThat(prefix, actual, is(notNullValue()));
            assertThat(prefix + ".opcode", OpCode.name(actual.getOpCode()), is(OpCode.name(expected.getOpCode())));
            prefix += "/" + actual.getOpCode();
            if (expected.getOpCode() == OpCode.CLOSE)
            {
                CloseInfo expectedClose = new CloseInfo(expected);
                CloseInfo actualClose = new CloseInfo(actual);
                assertThat(prefix + ".statusCode", actualClose.getStatusCode(), is(expectedClose.getStatusCode()));
            }
            else
            {
                assertThat(prefix + ".payloadLength", actual.getPayloadLength(), is(expected.getPayloadLength()));
                ByteBufferAssert.assertEquals(prefix + ".payload", expected.getPayload(), actual.getPayload());
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
        assertThat("Client connected", clientConnection.isOpen(), is(true));
        LOG.debug("Sending bytes {}", BufferUtil.toDetailString(buf));
        if (sendMode == SendMode.SLOW)
        {
            clientConnection.writeRawSlowly(buf, slowSendSegmentSize);
        }
        else
        {
            clientConnection.writeRaw(buf);
        }
    }

    public void send(ByteBuffer buf, int numBytes) throws IOException
    {
        clientConnection.writeRaw(buf, numBytes);
    }

    public void send(List<WebSocketFrame> send) throws IOException
    {
        assertThat("Client connected", clientConnection.isOpen(), is(true));
        LOG.debug("Sending {} frames (mode {})", send.size(), sendMode);
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
            BufferUtil.flipToFlush(buf, 0);

            // Write Data Frame
            switch (sendMode)
            {
                case BULK:
                    clientConnection.writeRaw(buf);
                    break;
                case SLOW:
                    clientConnection.writeRawSlowly(buf, slowSendSegmentSize);
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
                generator.generateWholeFrame(f, fullframe);
                BufferUtil.flipToFlush(fullframe, 0);
                clientConnection.writeRaw(fullframe);
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
            assertThat("Allowed to be a broken pipe", ignore.getMessage().toLowerCase(Locale.ENGLISH), containsString("broken pipe"));
        }
    }

    private void setClientMask(WebSocketFrame f)
    {
        if (LOG.isDebugEnabled())
        {
            f.setMask(new byte[]
                {0x00, 0x00, 0x00, 0x00});
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
