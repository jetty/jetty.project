package org.eclipse.jetty.websocket.server.ab;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.protocol.CloseInfo;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.eclipse.jetty.websocket.server.helper.Hex;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Test of Close Handling
 */
public class TestABCase7 extends AbstractABCase
{
    /**
     * Basic message then close frame, normal behavior
     */
    @Test
    public void testCase7_1_1() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(WebSocketFrame.text("Hello World"));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(WebSocketFrame.text("Hello World"));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * Close frame, then another close frame (send frame ignored)
     */
    @Test
    public void testCase7_1_2() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        send.add(new CloseInfo().asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
            fuzzer.expectNoMoreFrames();
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * Close frame, then ping frame (no pong received)
     */
    @Test
    public void testCase7_1_3() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        send.add(WebSocketFrame.ping().setPayload("out of band ping"));

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
            fuzzer.expectNoMoreFrames();
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * Close frame, then ping frame (no pong received)
     */
    @Test
    public void testCase7_1_4() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        send.add(WebSocketFrame.text("out of band text"));

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
            fuzzer.expectNoMoreFrames();
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * Text fin=false, close, then continuation fin=true
     */
    @Test
    public void testCase7_1_5() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload("an").setFin(false));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        send.add(new WebSocketFrame(OpCode.CONTINUATION).setPayload("ticipation").setFin(true));

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
            fuzzer.expectNoMoreFrames();
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * 256k msg, then close, then ping
     */
    @Test
    @Ignore("Problematic")
    public void testCase7_1_6() throws Exception
    {
        byte msg[] = new byte[256 * 1024];
        Arrays.fill(msg,(byte)'*');

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload(msg));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        send.add(new WebSocketFrame(OpCode.PING).setPayload("out of band"));

        List<WebSocketFrame> expect = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.TEXT).setPayload(msg));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
            fuzzer.expectNoMoreFrames();
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * close with no payload (payload length 0)
     */
    @Test
    public void testCase7_3_1() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.CLOSE));

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new WebSocketFrame(OpCode.CLOSE));

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
            fuzzer.expectNoMoreFrames();
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * close with invalid payload (payload length 1)
     */
    @Test
    public void testCase7_3_2() throws Exception
    {
        byte payload[] = new byte[]
        { 0x00 };

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame(OpCode.CLOSE).setPayload(payload));

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
            fuzzer.expectNoMoreFrames();
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * close with valid payload (payload length 2)
     */
    @Test
    public void testCase7_3_3() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
            fuzzer.expectNoMoreFrames();
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * close with valid payload (with reason)
     */
    @Test
    public void testCase7_3_4() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new CloseInfo(StatusCode.NORMAL,"Hic").asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.NORMAL,"Hic").asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
            fuzzer.expectNoMoreFrames();
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * close with valid payload (with 123 byte reason)
     */
    @Test
    public void testCase7_3_5() throws Exception
    {
        byte utf[] = new byte[123];
        Arrays.fill(utf,(byte)'!');
        String reason = StringUtil.toUTF8String(utf,0,utf.length);

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new CloseInfo(StatusCode.NORMAL,reason).asFrame());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.NORMAL,reason).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
            fuzzer.expectNoMoreFrames();
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * close with invalid payload (124 byte reason) (exceeds total allowed control frame payload bytes)
     */
    @Test
    public void testCase7_3_6() throws Exception
    {
        ByteBuffer payload = ByteBuffer.allocate(256);
        BufferUtil.clearToFill(payload);
        payload.put((byte)0xE8);
        payload.put((byte)0x03);
        byte reason[] = new byte[124]; // too big
        Arrays.fill(reason,(byte)'!');
        payload.put(reason);
        BufferUtil.flipToFlush(payload,0);

        List<WebSocketFrame> send = new ArrayList<>();
        WebSocketFrame close = new WebSocketFrame();
        close.setPayload(payload);
        close.setOpCode(OpCode.CLOSE); // set opcode after payload (to prevent early bad payload detection)
        send.add(close);

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
            fuzzer.expectNoMoreFrames();
        }
        finally
        {
            fuzzer.close();
        }
    }

    /**
     * close with invalid payload (124 byte reason) (exceeds total allowed control frame payload bytes)
     */
    @Test
    public void testCase7_5_1() throws Exception
    {
        ByteBuffer payload = ByteBuffer.allocate(256);
        BufferUtil.clearToFill(payload);
        payload.put((byte)0x03); // normal close
        payload.put((byte)0xE8);
        byte invalidUtf[] = Hex.asByteArray("CEBAE1BDB9CF83CEBCCEB5EDA080656469746564");
        payload.put(invalidUtf);
        BufferUtil.flipToFlush(payload,0);

        List<WebSocketFrame> send = new ArrayList<>();
        WebSocketFrame close = new WebSocketFrame();
        close.setPayload(payload);
        close.setOpCode(OpCode.CLOSE); // set opcode after payload (to prevent early bad payload detection)
        send.add(close);

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.BAD_PAYLOAD).asFrame());

        Fuzzer fuzzer = new Fuzzer(this);
        try
        {
            fuzzer.connect();
            fuzzer.setSendMode(Fuzzer.SendMode.BULK);
            fuzzer.send(send);
            fuzzer.expect(expect);
            fuzzer.expectNoMoreFrames();
        }
        finally
        {
            fuzzer.close();
        }
    }
}
