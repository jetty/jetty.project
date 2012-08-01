package org.eclipse.jetty.websocket.server.ab;

import java.util.ArrayList;
import java.util.List;

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
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame((byte)3)); // intentionally bad

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

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
     * Send opcode 4 (reserved), with payload
     */
    @Test
    public void testCase4_1_2() throws Exception
    {
        byte payload[] = StringUtil.getUtf8Bytes("reserved payload");

        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame((byte)4).setPayload(payload)); // intentionally bad

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

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
     * Send small text, then frame with opcode 5 (reserved), then ping
     */
    @Test
    public void testCase4_1_3() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(WebSocketFrame.text("hello"));
        send.add(new WebSocketFrame((byte)5)); // intentionally bad
        send.add(WebSocketFrame.ping());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(WebSocketFrame.text("hello")); // echo
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

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
     * Send small text, then frame with opcode 6 (reserved) w/payload, then ping
     */
    @Test
    public void testCase4_1_4() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(WebSocketFrame.text("hello"));
        send.add(new WebSocketFrame((byte)6).setPayload("bad")); // intentionally bad
        send.add(WebSocketFrame.ping());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(WebSocketFrame.text("hello")); // echo
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

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
     * Send small text, then frame with opcode 7 (reserved) w/payload, then ping
     */
    @Test
    public void testCase4_1_5() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(WebSocketFrame.text("hello"));
        send.add(new WebSocketFrame((byte)7).setPayload("bad")); // intentionally bad
        send.add(WebSocketFrame.ping());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(WebSocketFrame.text("hello")); // echo
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

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
     * Send opcode 11 (reserved)
     */
    @Test
    public void testCase4_2_1() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame((byte)11)); // intentionally bad

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

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
     * Send opcode 12 (reserved)
     */
    @Test
    public void testCase4_2_2() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new WebSocketFrame((byte)12).setPayload("bad")); // intentionally bad

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

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
     * Send small text, then frame with opcode 13 (reserved), then ping
     */
    @Test
    public void testCase4_2_3() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(WebSocketFrame.text("hello"));
        send.add(new WebSocketFrame((byte)13)); // intentionally bad
        send.add(WebSocketFrame.ping());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(WebSocketFrame.text("hello")); // echo
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

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
     * Send small text, then frame with opcode 14 (reserved), then ping
     */
    @Test
    public void testCase4_2_4() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(WebSocketFrame.text("hello"));
        send.add(new WebSocketFrame((byte)14).setPayload("bad")); // intentionally bad
        send.add(WebSocketFrame.ping());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(WebSocketFrame.text("hello")); // echo
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

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
     * Send small text, then frame with opcode 15 (reserved), then ping
     */
    @Test
    public void testCase4_2_5() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(WebSocketFrame.text("hello"));
        send.add(new WebSocketFrame((byte)15).setPayload("bad")); // intentionally bad
        send.add(WebSocketFrame.ping());

        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(WebSocketFrame.text("hello")); // echo
        expect.add(new CloseInfo(StatusCode.PROTOCOL).asFrame());

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
}
