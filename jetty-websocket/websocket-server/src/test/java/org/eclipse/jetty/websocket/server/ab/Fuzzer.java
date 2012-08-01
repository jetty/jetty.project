package org.eclipse.jetty.websocket.server.ab;

import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.protocol.CloseInfo;
import org.eclipse.jetty.websocket.protocol.Generator;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.eclipse.jetty.websocket.server.ByteBufferAssert;
import org.eclipse.jetty.websocket.server.blockhead.BlockheadClient;
import org.eclipse.jetty.websocket.server.helper.IncomingFramesCapture;
import org.junit.Assert;

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

    private static final Logger LOG = Log.getLogger(Fuzzer.class);

    // Client side framing mask
    protected static final byte[] MASK =
    { 0x11, 0x22, 0x33, 0x44 };

    private final BlockheadClient client;
    private final Generator generator;
    private SendMode sendMode = SendMode.BULK;
    private int slowSendSegmentSize = 5;

    public Fuzzer(AbstractABCase testcase) throws Exception
    {
        this.client = new BlockheadClient(testcase.getServer().getServerUri());
        this.generator = testcase.getLaxGenerator();
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
        int expectedCount = expect.size();

        // Read frames
        IncomingFramesCapture capture = client.readFrames(expect.size(),TimeUnit.MILLISECONDS,500);

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

    public void send(List<WebSocketFrame> send) throws IOException
    {
        Assert.assertThat("Client connected",client.isConnected(),is(true));
        LOG.debug("Sending {} frames (mode {})",send.size(),sendMode);
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
