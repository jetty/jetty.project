/*******************************************************************************
 * Copyright (c) 2011 Intalio, Inc.
 * ======================================================================
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *   The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 *
 *   The Apache License v2.0 is available at
 *   http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 *******************************************************************************/
package org.eclipse.jetty.websocket.server;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.eclipse.jetty.server.SelectChannelConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.annotations.OnWebSocketBinary;
import org.eclipse.jetty.websocket.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.annotations.OnWebSocketText;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.AcceptHash;
import org.eclipse.jetty.websocket.api.OpCode;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.PingFrame;
import org.eclipse.jetty.websocket.frames.PongFrame;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class WebSocketMessageRFC6455Test
{
    @WebSocket
    public static class TestWebSocket
    {
        protected boolean _latch;
        boolean _onConnect = false;
        boolean _echo = true;
        boolean _aggregate = false;
        private final CountDownLatch connected = new CountDownLatch(1);
        private final CountDownLatch disconnected = new CountDownLatch(1);
        private WebSocketConnection connection;

        private boolean awaitConnected(long time) throws InterruptedException
        {
            return connected.await(time,TimeUnit.MILLISECONDS);
        }

        private boolean awaitDisconnected(long time) throws InterruptedException
        {
            return disconnected.await(time,TimeUnit.MILLISECONDS);
        }

        @OnWebSocketClose
        public void onClose(int code, String message)
        {
            disconnected.countDown();
        }

        @OnWebSocketFrame
        public void onFrame(BaseFrame frame)
        {
            if (_echo)
            {
                if (!(frame instanceof PingFrame) && !(frame instanceof PongFrame) && !(frame instanceof CloseFrame))
                {
                    try
                    {
                        connection.write(frame);
                    }
                    catch (IOException e)
                    {
                        e.printStackTrace();
                    }
                }
            }
        }

        @OnWebSocketBinary
        public void onMessage(byte[] data, int offset, int length)
        {
            if (_aggregate)
            {
                try
                {
                    connection.write(data,offset,length);
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
        }

        @OnWebSocketText
        public void onMessage(String data)
        {
            __textCount.incrementAndGet();
            if (_latch)
            {
                try
                {
                    __latch.await();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }

            if (_aggregate)
            {
                try
                {
                    connection.write(data);
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
        }

        @OnWebSocketConnect
        public void onOpen(WebSocketConnection connection)
        {
            this.connection = connection;
            if (_onConnect)
            {
                try
                {
                    connection.write("sent on connect");
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
            connected.countDown();
        }

    }

    private static final int WSVERSION = 13; // RFC-6455 version
    private static Server __server;
    private static SelectChannelConnector __connector;
    private static TestWebSocket __serverWebSocket;
    private static CountDownLatch __latch;

    private static AtomicInteger __textCount = new AtomicInteger(0);

    @BeforeClass
    public static void startServer() throws Exception
    {
        __server = new Server();
        __connector = new SelectChannelConnector();
        __server.addConnector(__connector);
        WebSocketHandler wsHandler = new WebSocketHandler()
        {
            @Override
            public void registerWebSockets(WebSocketServerFactory factory)
            {
                factory.register(TestWebSocket.class);
                factory.setCreator(new WebSocketCreator()
                {
                    @Override
                    public Object createWebSocket(WebSocketRequest req, WebSocketResponse resp)
                    {
                        __textCount.set(0);

                        __serverWebSocket = new TestWebSocket();
                        __serverWebSocket._onConnect = req.hasSubProtocol("onConnect");
                        __serverWebSocket._echo = req.hasSubProtocol("echo");
                        __serverWebSocket._aggregate = req.hasSubProtocol("aggregate");
                        __serverWebSocket._latch = req.hasSubProtocol("latch");
                        if (__serverWebSocket._latch)
                        {
                            __latch = new CountDownLatch(1);
                        }
                        return __serverWebSocket;
                    }
                });
            }
        };
        wsHandler.getWebSocketFactory().getPolicy().setBufferSize(8192);
        wsHandler.getWebSocketFactory().getPolicy().setMaxIdleTime(1000);
        wsHandler.setHandler(new DefaultHandler());
        __server.setHandler(wsHandler);
        __server.start();
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        __server.stop();
        __server.join();
    }

    private void lookFor(String string, InputStream in) throws IOException
    {
        String orig = string;
        Utf8StringBuilder scanned = new Utf8StringBuilder();
        try
        {
            while (true)
            {
                int b = in.read();
                if (b < 0)
                {
                    throw new EOFException();
                }
                scanned.append((byte)b);
                assertEquals("looking for\"" + orig + "\" in '" + scanned + "'",string.charAt(0),b);
                if (string.length() == 1)
                {
                    break;
                }
                string = string.substring(1);
            }
        }
        catch (IOException e)
        {
            System.err.println("IOE while looking for \"" + orig + "\" in '" + scanned + "'");
            throw e;
        }
    }

    private void skipTo(String string, InputStream in) throws IOException
    {
        int state = 0;

        while (true)
        {
            int b = in.read();
            if (b < 0)
            {
                throw new EOFException();
            }

            if (b == string.charAt(state))
            {
                state++;
                if (state == string.length())
                {
                    break;
                }
            }
            else
            {
                state = 0;
            }
        }
    }

    @Test
    public void testBinaryAggregate() throws Exception
    {
        Socket socket = new Socket("localhost",__connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: aggregate\r\n"
                + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        socket.setSoTimeout(1000);
        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        output.write(OpCode.BINARY.getCode());
        output.write(0x8a);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        byte[] bytes = "0123456789".getBytes(StringUtil.__ISO_8859_1);
        for (byte b : bytes)
        {
            output.write(b ^ 0xff);
        }
        output.flush();

        output.write(0x80);
        output.write(0x8a);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        for (byte b : bytes)
        {
            output.write(b ^ 0xff);
        }
        output.flush();

        assertEquals(0x80 + OpCode.BINARY.getCode(),input.read());
        assertEquals(20,input.read());
        lookFor("01234567890123456789",input);
    }

    @Test
    public void testBlockedConsumer() throws Exception
    {
        Socket socket = new Socket("localhost",__connector.getLocalPort());
        OutputStream output = socket.getOutputStream();

        byte[] bytes = "This is a long message of text that we will send again and again".getBytes(StringUtil.__ISO_8859_1);
        byte[] mesg = new byte[bytes.length + 6];
        mesg[0] = (byte)(0x80 + OpCode.TEXT.getCode());
        mesg[1] = (byte)(0x80 + bytes.length);
        mesg[2] = (byte)0xff;
        mesg[3] = (byte)0xff;
        mesg[4] = (byte)0xff;
        mesg[5] = (byte)0xff;
        for (int i = 0; i < bytes.length; i++)
        {
            mesg[6 + i] = (byte)(bytes[i] ^ 0xff);
        }

        final int count = 100000;

        output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: latch\r\n"
                + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(60000);

        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        // Send and receive 1 message
        output.write(mesg);
        output.flush();
        while (__textCount.get() == 0)
        {
            Thread.sleep(10);
        }

        // unblock the latch in 4s
        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    Thread.sleep(4000);
                    __latch.countDown();
                    // System.err.println("latched");
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        }.start();

        // Send enough messages to fill receive buffer
        long max = 0;
        long start = System.currentTimeMillis();
        for (int i = 0; i < count; i++)
        {
            output.write(mesg);
            if ((i % 100) == 0)
            {
                // System.err.println(">>> "+i);
                output.flush();

                long now = System.currentTimeMillis();
                long duration = now - start;
                start = now;
                if (max < duration)
                {
                    max = duration;
                }
            }
        }

        Thread.sleep(50);
        while (__textCount.get() < (count + 1))
        {
            System.err.println(__textCount.get() + "<" + (count + 1));
            Thread.sleep(10);
        }
        assertEquals(count + 1,__textCount.get()); // all messages
        assertTrue(max > 2000); // was blocked
    }

    @Test
    public void testBlockedProducer() throws Exception
    {
        final Socket socket = new Socket("localhost",__connector.getLocalPort());
        OutputStream output = socket.getOutputStream();

        final int count = 100000;

        output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: latch\r\n"
                + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(60000);

        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);
        __latch.countDown();

        // wait 2s and then consume messages
        final AtomicLong totalB = new AtomicLong();
        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    Thread.sleep(2000);

                    byte[] recv = new byte[32 * 1024];

                    int len = 0;
                    while (len >= 0)
                    {
                        totalB.addAndGet(len);
                        len = socket.getInputStream().read(recv,0,recv.length);
                        Thread.sleep(10);
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        }.start();

        // Send enough messages to fill receive buffer
        long max = 0;
        long start = System.currentTimeMillis();
        String mesg = "How Now Brown Cow";
        for (int i = 0; i < count; i++)
        {
            __serverWebSocket.connection.write(mesg);
            if ((i % 100) == 0)
            {
                output.flush();

                long now = System.currentTimeMillis();
                long duration = now - start;
                start = now;
                if (max < duration)
                {
                    max = duration;
                }
            }
        }

        while (totalB.get() < (count * (mesg.length() + 2)))
        {
            Thread.sleep(100);
        }

        assertEquals(count * (mesg.length() + 2),totalB.get()); // all messages
        Assert.assertThat("Was blocked (max time)",max,greaterThan(1000L)); // was blocked
    }

    @Test
    public void testCloseIn() throws Exception
    {
        int[][] tests =
        {
        { -1, 0, -1 },
        { -1, 0, -1 },
        { 1000, 2, 1000 },
        { 1000, 2 + 4, 1000 },
        { 1005, 2 + 23, 1002 },
        { 1005, 2 + 23, 1002 },
        { 1006, 2 + 23, 1002 },
        { 1006, 2 + 23, 1002 },
        { 4000, 2, 4000 },
        { 4000, 2 + 4, 4000 },
        { 9000, 2 + 23, 1002 },
        { 9000, 2 + 23, 1002 } };

        String[] mesg =
        { "", "", "", "mesg", "", "mesg", "", "mesg", "", "mesg", "", "mesg" };

        String[] resp =
        { "", "", "", "mesg", "Invalid close code 1005", "Invalid close code 1005", "Invalid close code 1006", "Invalid close code 1006", "", "mesg",
                "Invalid close code 9000", "Invalid close code 9000" };

        for (int t = 0; t < tests.length; t++)
        {
            String tst = "" + t;
            Socket socket = new Socket("localhost",__connector.getLocalPort());
            OutputStream output = socket.getOutputStream();
            output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: chat\r\n"
                    + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "\r\n").getBytes("ISO-8859-1"));
            output.flush();

            socket.setSoTimeout(100000);
            InputStream input = socket.getInputStream();

            lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
            skipTo("Sec-WebSocket-Accept: ",input);
            lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
            skipTo("\r\n\r\n",input);

            assertTrue(__serverWebSocket.awaitConnected(1000));
            assertNotNull(__serverWebSocket.connection);

            int code = tests[t][0];
            String m = mesg[t];

            output.write(0x88);
            output.write(0x80 + (code <= 0?0:(2 + m.length())));
            output.write(0x00);
            output.write(0x00);
            output.write(0x00);
            output.write(0x00);

            if (code > 0)
            {
                output.write(code / 0x100);
                output.write(code % 0x100);
                output.write(m.getBytes());
            }
            output.flush();

            __serverWebSocket.awaitDisconnected(1000);

            byte[] buf = new byte[128];
            int len = input.read(buf);

            assertEquals(tst,2 + tests[t][1],len);
            assertEquals(tst,(byte)0x88,buf[0]);

            if (len >= 4)
            {
                code = ((0xff & buf[2]) * 0x100) + (0xff & buf[3]);
                assertEquals(tst,tests[t][2],code);

                if (len > 4)
                {
                    m = new String(buf,4,len - 4,"UTF-8");
                    assertEquals(tst,resp[t],m);
                }
            }
            else
            {
                assertEquals(tst,tests[t][2],-1);
            }

            len = input.read(buf);
            assertEquals(tst,-1,len);
        }
    }

    @Test
    public void testCloseOut() throws Exception
    {
        int[][] tests =
        {
        { -1, 0, -1 },
        { -1, 0, -1 },
        { 0, 2, 1000 },
        { 0, 2 + 4, 1000 },
        { 1000, 2, 1000 },
        { 1000, 2 + 4, 1000 },
        { 1005, 0, -1 },
        { 1005, 0, -1 },
        { 1006, 0, -1 },
        { 1006, 0, -1 },
        { 9000, 2, 9000 },
        { 9000, 2 + 4, 9000 } };

        String[] mesg =
        { null, "Not Sent", null, "mesg", null, "mesg", null, "mesg", null, "mesg", null, "mesg" };

        for (int t = 0; t < tests.length; t++)
        {
            String tst = "" + t;
            Socket socket = new Socket("localhost",__connector.getLocalPort());
            OutputStream output = socket.getOutputStream();
            output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: chat\r\n"
                    + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "\r\n").getBytes("ISO-8859-1"));
            output.flush();

            socket.setSoTimeout(100000);
            InputStream input = socket.getInputStream();

            lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
            skipTo("Sec-WebSocket-Accept: ",input);
            lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
            skipTo("\r\n\r\n",input);

            assertTrue(__serverWebSocket.awaitConnected(1000));
            assertNotNull(__serverWebSocket.connection);

            __serverWebSocket.connection.close(tests[t][0],mesg[t]);

            byte[] buf = new byte[128];
            int len = input.read(buf);
            assertEquals(tst,2 + tests[t][1],len);
            assertEquals(tst,(byte)0x88,buf[0]);

            if (len >= 4)
            {
                int code = ((0xff & buf[2]) * 0x100) + (0xff & buf[3]);
                assertEquals(tst,tests[t][2],code);

                if (len > 4)
                {
                    String m = new String(buf,4,len - 4,"UTF-8");
                    assertEquals(tst,mesg[t],m);
                }
            }
            else
            {
                assertEquals(tst,tests[t][2],-1);
            }

            try
            {
                output.write(0x88);
                output.write(0x80);
                output.write(0x00);
                output.write(0x00);
                output.write(0x00);
                output.write(0x00);
                output.flush();
            }
            catch (IOException e)
            {
                System.err.println("socket " + socket);
                throw e;
            }

            len = input.read(buf);
            assertEquals(tst,-1,len);
        }
    }

    @Test
    public void testDeflateFrameExtension() throws Exception
    {
        Socket socket = new Socket("localhost",__connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: echo\r\n"
                + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "Sec-WebSocket-Extensions: x-deflate-frame;minLength=64\r\n"
                + "Sec-WebSocket-Extensions: fragment;minFragments=2\r\n" + "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();
        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("Sec-WebSocket-Extensions: ",input);
        lookFor("x-deflate-frame;minLength=64",input);
        skipTo("Sec-WebSocket-Extensions: ",input);
        lookFor("fragment;",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        // Server sends a big message
        String text = "0123456789ABCDEF ";
        text = text + text + text + text;
        text = text + text + text + text;
        text = text + text + text + text + 'X';
        byte[] data = text.getBytes("utf-8");
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        byte[] buf = new byte[data.length];

        buf[0] = ((byte)0x7e);
        buf[1] = (byte)(data.length >> 8);
        buf[2] = (byte)(data.length & 0xff);

        int l = deflater.deflate(buf,3,buf.length - 3);

        assertTrue(deflater.finished());

        output.write(0xC1);
        output.write((byte)(0x80 | (0xff & (l + 3))));
        output.write(0x00);
        output.write(0x00);
        output.write(0x00);
        output.write(0x00);
        output.write(buf,0,l + 3);
        output.flush();

        assertEquals(0x40 + OpCode.TEXT.getCode(),input.read());
        assertEquals(0x20 + 3,input.read());
        assertEquals(0x7e,input.read());
        assertEquals(0x02,input.read());
        assertEquals(0x20,input.read());

        byte[] raw = new byte[32];
        assertEquals(32,input.read(raw));

        Inflater inflater = new Inflater();
        inflater.setInput(raw);

        byte[] result = new byte[544];
        assertEquals(544,inflater.inflate(result));
        assertEquals(TypeUtil.toHexString(data,0,544),TypeUtil.toHexString(result));

        assertEquals((byte)0xC0,(byte)input.read());
        assertEquals(0x21 + 3,input.read());
        assertEquals(0x7e,input.read());
        assertEquals(0x02,input.read());
        assertEquals(0x21,input.read());

        assertEquals(32,input.read(raw));

        inflater.reset();
        inflater.setInput(raw);
        result = new byte[545];
        assertEquals(545,inflater.inflate(result));
        assertEquals(TypeUtil.toHexString(data,544,545),TypeUtil.toHexString(result));

    }

    @Test
    public void testFragmentExtension() throws Exception
    {
        Socket socket = new Socket("localhost",__connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: onConnect\r\n"
                + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "Sec-WebSocket-Extensions: fragment;maxLength=4;minFragments=7\r\n" + "\r\n")
                .getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();
        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("Sec-WebSocket-Extensions: ",input);
        lookFor("fragment;",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        assertEquals(0x01,input.read());
        assertEquals(0x04,input.read());
        lookFor("sent",input);
        assertEquals(0x00,input.read());
        assertEquals(0x04,input.read());
        lookFor(" on ",input);
        assertEquals(0x00,input.read());
        assertEquals(0x04,input.read());
        lookFor("conn",input);
        assertEquals(0x00,input.read());
        assertEquals(0x01,input.read());
        lookFor("e",input);
        assertEquals(0x00,input.read());
        assertEquals(0x01,input.read());
        lookFor("c",input);
        assertEquals(0x00,input.read());
        assertEquals(0x00,input.read());
        assertEquals(0x80,input.read());
        assertEquals(0x01,input.read());
        lookFor("t",input);
    }

    @Test
    public void testHash()
    {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",AcceptHash.hashKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    public void testIdentityExtension() throws Exception
    {
        Socket socket = new Socket("localhost",__connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: onConnect\r\n"
                + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "Sec-WebSocket-Extensions: identity;param=0\r\n"
                + "Sec-WebSocket-Extensions: identity;param=1, identity ; param = '2' ; other = ' some = value ' \r\n" + "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();
        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("Sec-WebSocket-Extensions: ",input);
        lookFor("identity;param=0",input);
        skipTo("Sec-WebSocket-Extensions: ",input);
        lookFor("identity;param=1",input);
        skipTo("Sec-WebSocket-Extensions: ",input);
        lookFor("identity;",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        assertEquals(0x81,input.read());
        assertEquals(0x0f,input.read());
        lookFor("sent on connect",input);
    }

    @Test
    public void testIdle() throws Exception
    {
        Socket socket = new Socket("localhost",__connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: onConnect\r\n"
                + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(10000);

        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        assertEquals(0x81,input.read());
        assertEquals(0x0f,input.read());
        lookFor("sent on connect",input);

        assertEquals((byte)0x88,(byte)input.read());
        assertEquals(26,input.read());
        assertEquals(1000 / 0x100,input.read());
        assertEquals(1000 % 0x100,input.read());
        lookFor("Idle",input);

        // respond to close
        output.write(0x88 ^ 0xff);
        output.write(0x80 ^ 0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.flush();

        assertTrue(__serverWebSocket.awaitDisconnected(5000));
        try
        {
            __serverWebSocket.connection.write("Don't send");
            Assert.fail("Should have thrown IOException");
        }
        catch (IOException e)
        {
            Assert.assertThat("IOException",e.getMessage(),containsString("TODO"));
        }
    }

    @Test
    public void testMaxBinarySize() throws Exception
    {
        Socket socket = new Socket("localhost",__connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: other\r\n"
                + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        socket.setSoTimeout(100000);
        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        output.write(0x02);
        output.write(0x8a);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        byte[] bytes = "0123456789".getBytes(StringUtil.__ISO_8859_1);
        for (byte b : bytes)
        {
            output.write(b ^ 0xff);
        }
        output.flush();

        output.write(0x80);
        output.write(0x8a);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        for (byte b : bytes)
        {
            output.write(b ^ 0xff);
        }
        output.flush();

        assertEquals(0x80 | OpCode.CLOSE.getCode(),input.read());
        assertEquals(19,input.read());
        int code = ((0xff & input.read()) * 0x100) + (0xff & input.read());
        assertEquals(StatusCode.MESSAGE_TOO_LARGE,code);
        lookFor("Message size > 15",input);
    }

    @Test
    public void testMaxBinarySize2() throws Exception
    {
        Socket socket = new Socket("localhost",__connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: other\r\n"
                + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        socket.setSoTimeout(100000);
        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        output.write(0x02);
        output.write(0x94);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        byte[] bytes = "01234567890123456789".getBytes(StringUtil.__ISO_8859_1);
        for (byte b : bytes)
        {
            output.write(b ^ 0xff);
        }
        output.flush();

        assertEquals(0x80 | OpCode.CLOSE.getCode(),input.read());
        assertEquals(19,input.read());
        int code = ((0xff & input.read()) * 0x100) + (0xff & input.read());
        assertEquals(StatusCode.MESSAGE_TOO_LARGE,code);
        lookFor("Message size > 15",input);
    }

    @Test
    public void testMaxTextSize() throws Exception
    {
        Socket socket = new Socket("localhost",__connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: other\r\n"
                + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        socket.setSoTimeout(1000);
        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        output.write(0x01);
        output.write(0x8a);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        byte[] bytes = "0123456789".getBytes(StringUtil.__ISO_8859_1);
        for (byte b : bytes)
        {
            output.write(b ^ 0xff);
        }
        output.flush();

        output.write(0x80);
        output.write(0x8a);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        for (byte b : bytes)
        {
            output.write(b ^ 0xff);
        }
        output.flush();

        assertEquals(0x80 | OpCode.CLOSE.getCode(),input.read());
        assertEquals(30,input.read());
        int code = ((0xff & input.read()) * 0x100) + (0xff & input.read());
        assertEquals(StatusCode.MESSAGE_TOO_LARGE,code);
        lookFor("Text message size > 15 chars",input);
    }

    @Test
    public void testMaxTextSize2() throws Exception
    {
        Socket socket = new Socket("localhost",__connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: other\r\n"
                + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        socket.setSoTimeout(100000);
        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        output.write(0x01);
        output.write(0x94);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        byte[] bytes = "01234567890123456789".getBytes(StringUtil.__ISO_8859_1);
        for (byte b : bytes)
        {
            output.write(b ^ 0xff);
        }
        output.flush();

        assertEquals(0x80 | OpCode.CLOSE.getCode(),input.read());
        assertEquals(30,input.read());
        int code = ((0xff & input.read()) * 0x100) + (0xff & input.read());
        assertEquals(StatusCode.MESSAGE_TOO_LARGE,code);
        lookFor("Text message size > 15 chars",input);
    }

    @Test
    public void testMaxTextSizeFalseFrag() throws Exception
    {
        Socket socket = new Socket("localhost",__connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: other\r\n"
                + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        socket.setSoTimeout(1000);
        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        output.write(0x81);
        output.write(0x80 | 0x7E);
        output.write((byte)((16 * 1024) >> 8));
        output.write((byte)((16 * 1024) & 0xff));
        output.write(0x00);
        output.write(0x00);
        output.write(0x00);
        output.write(0x00);

        for (int i = 0; i < (16 * 1024); i++)
        {
            output.write('X');
        }
        output.flush();

        assertEquals(0x80 | OpCode.CLOSE.getCode(),input.read());
        assertEquals(33,input.read());
        int code = ((0xff & input.read()) * 0x100) + (0xff & input.read());
        assertEquals(StatusCode.MESSAGE_TOO_LARGE,code);
        lookFor("Text message size > 10240 chars",input);
    }

    @Test
    public void testNotUTF8() throws Exception
    {
        Socket socket = new Socket("localhost",__connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: chat\r\n"
                + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        socket.setSoTimeout(100000);
        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        output.write(0x81);
        output.write(0x82);
        output.write(0x00);
        output.write(0x00);
        output.write(0x00);
        output.write(0x00);
        output.write(0xc3);
        output.write(0x28);
        output.flush();

        assertEquals(0x80 | OpCode.CLOSE.getCode(),input.read());
        assertEquals(15,input.read());
        int code = ((0xff & input.read()) * 0x100) + (0xff & input.read());
        assertEquals(StatusCode.BAD_PAYLOAD,code);
        lookFor("Invalid UTF-8",input);
    }

    @Test
    public void testServerEcho() throws Exception
    {
        Socket socket = new Socket("localhost",__connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: echo\r\n"
                + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "\r\n").getBytes("ISO-8859-1"));
        output.flush();
        output.write(0x84);
        output.write(0x8f);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        byte[] bytes = "this is an echo".getBytes(StringUtil.__ISO_8859_1);
        for (byte b : bytes)
        {
            output.write(b ^ 0xff);
        }
        output.flush();
        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        assertEquals(0x84,input.read());
        assertEquals(0x0f,input.read());
        lookFor("this is an echo",input);
    }

    @Test
    public void testServerPingPong() throws Exception
    {
        Socket socket = new Socket("localhost",__connector.getLocalPort());
        // Make sure the read times out if there are problems with the implementation
        OutputStream output = socket.getOutputStream();
        output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: echo\r\n"
                + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "\r\n").getBytes("ISO-8859-1"));
        output.flush();
        output.write(0x89);
        output.write(0x80);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.flush();

        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        socket.setSoTimeout(1000);
        assertEquals(0x8A,input.read());
        assertEquals(0x00,input.read());
    }

    @Test
    public void testServerSendBigStringMessage() throws Exception
    {
        Socket socket = new Socket("localhost",__connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n"
                + "Sec-WebSocket-Protocol: chat, superchat\r\n" + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        // Server sends a big message
        StringBuilder message = new StringBuilder();
        String text = "0123456789ABCDEF";
        for (int i = 0; i < ((0x2000) / text.length()); i++)
        {
            message.append(text);
        }
        String data = message.toString();
        __serverWebSocket.connection.write(data);

        assertEquals(OpCode.TEXT.getCode(),input.read());
        assertEquals(0x7e,input.read());
        assertEquals(0x1f,input.read());
        assertEquals(0xf6,input.read());
        lookFor(data.substring(0,0x1ff6),input);
        assertEquals(0x80,input.read());
        assertEquals(0x0A,input.read());
        lookFor(data.substring(0x1ff6),input);
    }

    @Test
    public void testServerSendOnConnect() throws Exception
    {
        Socket socket = new Socket("localhost",__connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: onConnect\r\n"
                + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        assertEquals(0x81,input.read());
        assertEquals(0x0f,input.read());
        lookFor("sent on connect",input);
    }

    @Test
    public void testTCPClose() throws Exception
    {
        Socket socket = new Socket("localhost",__connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: onConnect\r\n"
                + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        assertEquals(0x81,input.read());
        assertEquals(0x0f,input.read());
        lookFor("sent on connect",input);
        socket.close();

        assertTrue(__serverWebSocket.awaitDisconnected(500));

        try
        {
            __serverWebSocket.connection.write("Don't send");
            Assert.fail("Should have thrown IOException");
        }
        catch (IOException e)
        {
            Assert.assertThat("IOException",e.getMessage(),containsString("TODO"));
        }
    }

    @Test
    public void testTCPHalfClose() throws Exception
    {
        Socket socket = new Socket("localhost",__connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n" + "Sec-WebSocket-Protocol: onConnect\r\n"
                + "Sec-WebSocket-Version: " + WSVERSION + "\r\n" + "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        assertEquals(0x81,input.read());
        assertEquals(0x0f,input.read());
        lookFor("sent on connect",input);

        socket.shutdownOutput();

        assertTrue(__serverWebSocket.awaitDisconnected(500));

        assertEquals(0x88,input.read());
        assertEquals(0x00,input.read());
        assertEquals(-1,input.read());

        // look for broken pipe
        try
        {
            for (int i = 0; i < 1000; i++)
            {
                output.write(0);
            }
            Assert.fail();
        }
        catch (SocketException e)
        {
            // expected
        }
    }
}
