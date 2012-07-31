package org.eclipse.jetty.websocket.server.ab;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.protocol.CloseInfo;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.junit.Test;

public class TestABCase4 extends AbstractABCase
{
    /**
     * Send opcode 3 (reserved)
     */
    @Test
    public void testCase4_1_1() throws Exception
    {
        ByteBuffer buf = ByteBuffer.allocate(32);
        BufferUtil.clearToFill(buf);

        // Construct bad frame by hand
        byte opcode = 3;
        buf.put((byte)(0x00 | FIN | opcode)); // bad
        putPayloadLength(buf,0);
        putMask(buf);
        BufferUtil.flipToFlush(buf,0);

        // Expectations
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(buf);
            fuzzer.expect(expect);
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * Send opcode 4 (reserved), with payload
     */
    @Test
    public void testCase4_1_2() throws Exception
    {
        byte payload[] = StringUtil.getUtf8Bytes("reserved payload");
        ByteBuffer buf = ByteBuffer.allocate(32);
        BufferUtil.clearToFill(buf);

        // Construct bad frame by hand
        byte opcode = 4;
        buf.put((byte)(0x00 | FIN | opcode)); // bad
        putPayloadLength(buf,payload.length);
        putMask(buf);
        buf.put(masked(payload));
        BufferUtil.flipToFlush(buf,0);

        // Expectations
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(buf);
            fuzzer.expect(expect);
        }
        finally
        {
            fuzzer.close();
        }
    }
}
