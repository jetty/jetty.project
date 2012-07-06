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

import org.eclipse.jetty.server.SelectChannelConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.protocol.OpCode;
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

        @OnWebSocketMessage
        public void onMessage(byte[] data, int offset, int length)
        {
            if (_aggregate)
            {
                try
                {
                    connection.write(null,fnf(),data,offset,length);
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
        }

        @OnWebSocketMessage
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
                    connection.write(null,fnf(),data);
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
                    connection.write(null,fnf(),"sent on connect");
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

    // Fire and Forget callback
    public static Callback<Void> fnf()
    {
        return new FutureCallback<Void>();
    }

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
        __serverWebSocket.connection.write(null,fnf(),data);

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
            __serverWebSocket.connection.write(null,fnf(),"Don't send");
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
