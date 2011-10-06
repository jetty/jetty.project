package org.eclipse.jetty.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @version $Revision$ $Date$
 */
public class WebSocketMessageD13Test
{
    private static Server __server;
    private static Connector __connector;
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
            public WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
            {
                __textCount.set(0);
                __serverWebSocket = new TestWebSocket();
                __serverWebSocket._onConnect=("onConnect".equals(protocol));
                __serverWebSocket._echo=("echo".equals(protocol));
                __serverWebSocket._aggregate=("aggregate".equals(protocol));
                __serverWebSocket._latch=("latch".equals(protocol));
                if (__serverWebSocket._latch)
                    __latch=new CountDownLatch(1);
                return __serverWebSocket;
            }
        };
        wsHandler.getWebSocketFactory().setBufferSize(8192);
        wsHandler.getWebSocketFactory().setMaxIdleTime(1000);
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

    
    @Test
    public void testHash()
    {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",WebSocketConnectionD13.hashKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }
    
    @Test
    public void testServerSendBigStringMessage() throws Exception
    {
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: chat, superchat\r\n"+
                 "Sec-WebSocket-Version: "+WebSocketConnectionD13.VERSION+"\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
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
        for (int i = 0; i < (0x2000) / text.length(); i++)
            message.append(text);
        String data=message.toString();
        __serverWebSocket.connection.sendMessage(data);

        assertEquals(WebSocketConnectionD13.OP_TEXT,input.read());
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
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: onConnect\r\n" +
                 "Sec-WebSocket-Version: "+WebSocketConnectionD13.VERSION+"\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
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
    public void testIdentityExtension() throws Exception
    {
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: onConnect\r\n" +
                 "Sec-WebSocket-Version: "+WebSocketConnectionD13.VERSION+"\r\n"+
                 "Sec-WebSocket-Extensions: identity;param=0\r\n"+
                 "Sec-WebSocket-Extensions: identity;param=1, identity ; param = '2' ; other = ' some = value ' \r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
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
    public void testFragmentExtension() throws Exception
    {
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: onConnect\r\n" +
                 "Sec-WebSocket-Version: "+WebSocketConnectionD13.VERSION+"\r\n"+
                 "Sec-WebSocket-Extensions: fragment;maxLength=4;minFragments=7\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
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
    public void testDeflateFrameExtension() throws Exception
    {
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: echo\r\n" +
                 "Sec-WebSocket-Version: "+WebSocketConnectionD13.VERSION+"\r\n"+
                 "Sec-WebSocket-Extensions: x-deflate-frame;minLength=64\r\n"+
                 "Sec-WebSocket-Extensions: fragment;minFragments=2\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
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
        text=text+text+text+text;
        text=text+text+text+text;
        text=text+text+text+text+'X';
        byte[] data=text.getBytes("utf-8");
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        byte[] buf=new byte[data.length];
        
        buf[0]=(byte)((byte)0x7e);
        buf[1]=(byte)(data.length>>8);
        buf[2]=(byte)(data.length&0xff);
        
        int l=deflater.deflate(buf,3,buf.length-3);

        assertTrue(deflater.finished());
        
        output.write(0xC1);
        output.write((byte)(0x80|(0xff&(l+3))));
        output.write(0x00);
        output.write(0x00);
        output.write(0x00);
        output.write(0x00);
        output.write(buf,0,l+3);
        output.flush();
        
        assertEquals(0x40+WebSocketConnectionD13.OP_TEXT,input.read());
        assertEquals(0x20+3,input.read());
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
        assertEquals(0x21+3,input.read());
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
    public void testServerEcho() throws Exception
    {
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: echo\r\n" +
                 "Sec-WebSocket-Version: "+WebSocketConnectionD13.VERSION+"\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
        output.flush();
        output.write(0x84);
        output.write(0x8f);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        byte[] bytes="this is an echo".getBytes(StringUtil.__ISO_8859_1);
        for (int i=0;i<bytes.length;i++)
            output.write(bytes[i]^0xff);
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
    public void testBlockedConsumer() throws Exception
    {
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();

        byte[] bytes="This is a long message of text that we will send again and again".getBytes(StringUtil.__ISO_8859_1);
        byte[] mesg=new byte[bytes.length+6];
        mesg[0]=(byte)(0x80+WebSocketConnectionD13.OP_TEXT);
        mesg[1]=(byte)(0x80+bytes.length);
        mesg[2]=(byte)0xff;
        mesg[3]=(byte)0xff;
        mesg[4]=(byte)0xff;
        mesg[5]=(byte)0xff;
        for (int i=0;i<bytes.length;i++)
            mesg[6+i]=(byte)(bytes[i]^0xff);
        
        final int count = 100000;

        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                        "Host: server.example.com\r\n"+
                        "Upgrade: websocket\r\n"+
                        "Connection: Upgrade\r\n"+
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                        "Sec-WebSocket-Origin: http://example.com\r\n"+
                        "Sec-WebSocket-Protocol: latch\r\n" +
                        "Sec-WebSocket-Version: "+WebSocketConnectionD13.VERSION+"\r\n"+
                "\r\n").getBytes("ISO-8859-1"));
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
        __serverWebSocket.connection.setMaxIdleTime(60000);

        // Send and receive 1 message
        output.write(mesg);
        output.flush();
        while(__textCount.get()==0)
            Thread.sleep(10);

        // unblock the latch in 4s
        new Thread()
        {
            public void run()
            {
                try
                {
                    Thread.sleep(4000);
                    __latch.countDown();
                    //System.err.println("latched");
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
        }.start();
        
        // Send enough messages to fill receive buffer
        long max=0;
        long start=System.currentTimeMillis();
        for (int i=0;i<count;i++)
        {
            output.write(mesg);
            if (i%100==0)
            {
                // System.err.println(">>> "+i);
                output.flush();
                
                long now=System.currentTimeMillis();
                long duration=now-start;
                start=now;
                if (max<duration)
                    max=duration;
            }
        }

        Thread.sleep(50);
        while(__textCount.get()<count+1)
        {
            System.err.println(__textCount.get()+"<"+(count+1));
            Thread.sleep(10);
        }
        assertEquals(count+1,__textCount.get()); // all messages
        assertTrue(max>2000); // was blocked
    }
    
    @Test
    public void testBlockedProducer() throws Exception
    {
        final Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        
        final int count = 100000;

        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                        "Host: server.example.com\r\n"+
                        "Upgrade: websocket\r\n"+
                        "Connection: Upgrade\r\n"+
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                        "Sec-WebSocket-Origin: http://example.com\r\n"+
                        "Sec-WebSocket-Protocol: latch\r\n" +
                        "Sec-WebSocket-Version: "+WebSocketConnectionD13.VERSION+"\r\n"+
                "\r\n").getBytes("ISO-8859-1"));
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
        __serverWebSocket.connection.setMaxIdleTime(60000);
        __latch.countDown();

        // wait 2s and then consume messages
        final AtomicLong totalB=new AtomicLong();
        new Thread()
        {
            public void run()
            {
                try
                {
                    Thread.sleep(2000);

                    byte[] recv = new byte[32*1024];

                    int len=0;
                    while (len>=0)
                    {
                        totalB.addAndGet(len);
                        len=socket.getInputStream().read(recv,0,recv.length);
                        Thread.sleep(10);
                    }
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
        }.start();
        
        
        // Send enough messages to fill receive buffer
        long max=0;
        long start=System.currentTimeMillis();
        String mesg="How Now Brown Cow";
        for (int i=0;i<count;i++)
        {
            __serverWebSocket.connection.sendMessage(mesg);
            if (i%100==0)
            {
                output.flush();
                
                long now=System.currentTimeMillis();
                long duration=now-start;
                start=now;
                if (max<duration)
                    max=duration;
            }
        }
        
        while(totalB.get()<(count*(mesg.length()+2)))
            Thread.sleep(100);
        
        assertEquals(count*(mesg.length()+2),totalB.get()); // all messages
        assertTrue(max>1000); // was blocked
    }

    @Test
    public void testServerPingPong() throws Exception
    {
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        // Make sure the read times out if there are problems with the implementation
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: echo\r\n" +
                 "Sec-WebSocket-Version: "+WebSocketConnectionD13.VERSION+"\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
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
    public void testMaxTextSizeFalseFrag() throws Exception
    {
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: other\r\n" +
                 "Sec-WebSocket-Version: "+WebSocketConnectionD13.VERSION+"\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        socket.setSoTimeout(1000);
        InputStream input = socket.getInputStream();
        
        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);
        
        __serverWebSocket.getConnection().setMaxTextMessageSize(10*1024);
        __serverWebSocket.getConnection().setAllowFrameFragmentation(true);
        
        output.write(0x81);
        output.write(0x80|0x7E);
        output.write((byte)((16*1024)>>8));
        output.write((byte)((16*1024)&0xff));
        output.write(0x00);
        output.write(0x00);
        output.write(0x00);
        output.write(0x00);

        for (int i=0;i<(16*1024);i++)
            output.write('X');
        output.flush();
        

        assertEquals(0x80|WebSocketConnectionD13.OP_CLOSE,input.read());
        assertEquals(33,input.read());
        int code=(0xff&input.read())*0x100+(0xff&input.read());
        assertEquals(WebSocketConnectionD13.CLOSE_MESSAGE_TOO_LARGE,code);
        lookFor("Text message size > 10240 chars",input);
    }

    @Test
    public void testMaxTextSize() throws Exception
    {
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: other\r\n" +
                 "Sec-WebSocket-Version: "+WebSocketConnectionD13.VERSION+"\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        socket.setSoTimeout(1000);
        InputStream input = socket.getInputStream();
        
        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);
        
        __serverWebSocket.getConnection().setMaxTextMessageSize(15);
        
        output.write(0x01);
        output.write(0x8a);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        byte[] bytes="0123456789".getBytes(StringUtil.__ISO_8859_1);
        for (int i=0;i<bytes.length;i++)
            output.write(bytes[i]^0xff);
        output.flush();

        output.write(0x80);
        output.write(0x8a);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        for (int i=0;i<bytes.length;i++)
            output.write(bytes[i]^0xff);
        output.flush();

        assertEquals(0x80|WebSocketConnectionD13.OP_CLOSE,input.read());
        assertEquals(30,input.read());
        int code=(0xff&input.read())*0x100+(0xff&input.read());
        assertEquals(WebSocketConnectionD13.CLOSE_MESSAGE_TOO_LARGE,code);
        lookFor("Text message size > 15 chars",input);
    }


    @Test
    public void testMaxTextSize2() throws Exception
    {
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: other\r\n" +
                 "Sec-WebSocket-Version: "+WebSocketConnectionD13.VERSION+"\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        socket.setSoTimeout(100000);
        InputStream input = socket.getInputStream();
        
        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);
        
        __serverWebSocket.getConnection().setMaxTextMessageSize(15);
        
        output.write(0x01);
        output.write(0x94);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        byte[] bytes="01234567890123456789".getBytes(StringUtil.__ISO_8859_1);
        for (int i=0;i<bytes.length;i++)
            output.write(bytes[i]^0xff);
        output.flush();
        
        
        
        assertEquals(0x80|WebSocketConnectionD13.OP_CLOSE,input.read());
        assertEquals(30,input.read());
        int code=(0xff&input.read())*0x100+(0xff&input.read());
        assertEquals(WebSocketConnectionD13.CLOSE_MESSAGE_TOO_LARGE,code);
        lookFor("Text message size > 15 chars",input);
    }

    @Test
    public void testBinaryAggregate() throws Exception
    {
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: aggregate\r\n" +
                 "Sec-WebSocket-Version: "+WebSocketConnectionD13.VERSION+"\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        socket.setSoTimeout(1000);
        InputStream input = socket.getInputStream();
        
        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);
        __serverWebSocket.getConnection().setMaxBinaryMessageSize(1024);
        
        output.write(WebSocketConnectionD13.OP_BINARY);
        output.write(0x8a);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        byte[] bytes="0123456789".getBytes(StringUtil.__ISO_8859_1);
        for (int i=0;i<bytes.length;i++)
            output.write(bytes[i]^0xff);
        output.flush();

        output.write(0x80);
        output.write(0x8a);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        for (int i=0;i<bytes.length;i++)
            output.write(bytes[i]^0xff);
        output.flush();
        
        assertEquals(0x80+WebSocketConnectionD13.OP_BINARY,input.read());
        assertEquals(20,input.read());
        lookFor("01234567890123456789",input);
    }
    
    @Test
    public void testMaxBinarySize() throws Exception
    {
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: other\r\n" +
                 "Sec-WebSocket-Version: "+WebSocketConnectionD13.VERSION+"\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        socket.setSoTimeout(100000);
        InputStream input = socket.getInputStream();
        
        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);
        
        __serverWebSocket.getConnection().setMaxBinaryMessageSize(15);
        
        output.write(0x02);
        output.write(0x8a);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        byte[] bytes="0123456789".getBytes(StringUtil.__ISO_8859_1);
        for (int i=0;i<bytes.length;i++)
            output.write(bytes[i]^0xff);
        output.flush();

        output.write(0x80);
        output.write(0x8a);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        for (int i=0;i<bytes.length;i++)
            output.write(bytes[i]^0xff);
        output.flush();

        
        assertEquals(0x80|WebSocketConnectionD13.OP_CLOSE,input.read());
        assertEquals(19,input.read());
        int code=(0xff&input.read())*0x100+(0xff&input.read());
        assertEquals(WebSocketConnectionD13.CLOSE_MESSAGE_TOO_LARGE,code);
        lookFor("Message size > 15",input);
    }

    @Test
    public void testCloseCode() throws Exception
    {
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: chat\r\n" +
                 "Sec-WebSocket-Version: "+WebSocketConnectionD13.VERSION+"\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        socket.setSoTimeout(100000);
        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        __serverWebSocket.getConnection().setMaxBinaryMessageSize(15);

        output.write(0x88);
        output.write(0x82);
        output.write(0x00);
        output.write(0x00);
        output.write(0x00);
        output.write(0x00);
        output.write(0x81);
        output.write(0xFF);
        output.flush();

        assertEquals(0x80|WebSocketConnectionD13.OP_CLOSE,input.read());
        assertEquals(2,input.read());
        int code=(0xff&input.read())*0x100+(0xff&input.read());
        assertEquals(0x81FF,code);
    }

    @Test
    public void testNotUTF8() throws Exception
    {
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: chat\r\n" +
                 "Sec-WebSocket-Version: "+WebSocketConnectionD13.VERSION+"\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        socket.setSoTimeout(100000);
        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);

        __serverWebSocket.getConnection().setMaxBinaryMessageSize(15);

        output.write(0x81);
        output.write(0x82);
        output.write(0x00);
        output.write(0x00);
        output.write(0x00);
        output.write(0x00);
        output.write(0xc3);
        output.write(0x28);
        output.flush();

        assertEquals(0x80|WebSocketConnectionD13.OP_CLOSE,input.read());
        assertEquals(15,input.read());
        int code=(0xff&input.read())*0x100+(0xff&input.read());
        assertEquals(WebSocketConnectionD13.CLOSE_BAD_PAYLOAD,code);
        lookFor("Invalid UTF-8",input);
    }

    @Test
    public void testMaxBinarySize2() throws Exception
    {
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: other\r\n" +
                 "Sec-WebSocket-Version: "+WebSocketConnectionD13.VERSION+"\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        socket.setSoTimeout(100000);
        InputStream input = socket.getInputStream();
        
        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);
        
        __serverWebSocket.getConnection().setMaxBinaryMessageSize(15);
        
        output.write(0x02);
        output.write(0x94);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        byte[] bytes="01234567890123456789".getBytes(StringUtil.__ISO_8859_1);
        for (int i=0;i<bytes.length;i++)
            output.write(bytes[i]^0xff);
        output.flush();
        
        assertEquals(0x80|WebSocketConnectionD13.OP_CLOSE,input.read());
        assertEquals(19,input.read());
        int code=(0xff&input.read())*0x100+(0xff&input.read());
        assertEquals(WebSocketConnectionD13.CLOSE_MESSAGE_TOO_LARGE,code);
        lookFor("Message size > 15",input);
    }

    @Test
    public void testIdle() throws Exception
    {
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: onConnect\r\n" +
                 "Sec-WebSocket-Version: "+WebSocketConnectionD13.VERSION+"\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
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
        assertEquals(1000/0x100,input.read());
        assertEquals(1000%0x100,input.read());
        lookFor("Idle",input);

        // respond to close
        output.write(0x88^0xff);
        output.write(0x80^0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.flush();
        
        
        assertTrue(__serverWebSocket.awaitDisconnected(5000));
        try
        {
            __serverWebSocket.connection.sendMessage("Don't send");
            assertTrue(false);
        }
        catch(IOException e)
        {
            assertTrue(true);
        }
    }

    @Test
    public void testClose() throws Exception
    {
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                        "Host: server.example.com\r\n"+
                        "Upgrade: websocket\r\n"+
                        "Connection: Upgrade\r\n"+
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                        "Sec-WebSocket-Origin: http://example.com\r\n"+
                        "Sec-WebSocket-Protocol: onConnect\r\n" +
                        "Sec-WebSocket-Version: "+WebSocketConnectionD13.VERSION+"\r\n"+
                "\r\n").getBytes("ISO-8859-1"));
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
            __serverWebSocket.connection.sendMessage("Don't send");
            assertTrue(false);
        }
        catch(IOException e)
        {
            assertTrue(true);
        }    
    }
    
    @Test
    public void testParserAndGenerator() throws Exception
    {
        String message = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";
        final AtomicReference<String> received = new AtomicReference<String>();
        ByteArrayEndPoint endp = new ByteArrayEndPoint(new byte[0],4096);
        
        WebSocketGeneratorD13 gen = new WebSocketGeneratorD13(new WebSocketBuffers(8096),endp,null);
        
        byte[] data = message.getBytes(StringUtil.__UTF8);
        gen.addFrame((byte)0x8,(byte)0x4,data,0,data.length);
        
        endp = new ByteArrayEndPoint(endp.getOut().asArray(),4096);
                
        WebSocketParserD13 parser = new WebSocketParserD13(new WebSocketBuffers(8096),endp,new WebSocketParser.FrameHandler()
        {
            public void onFrame(byte flags, byte opcode, Buffer buffer)
            {
                received.set(buffer.toString());
            }

            public void close(int code,String message)
            {
            }

        },false);
        
        parser.parseNext();
        
        assertEquals(message,received.get());
    }
    
    @Test
    public void testParserAndGeneratorMasked() throws Exception
    {
        String message = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";
        final AtomicReference<String> received = new AtomicReference<String>();
        ByteArrayEndPoint endp = new ByteArrayEndPoint(new byte[0],4096);

        MaskGen maskGen = new RandomMaskGen();
        
        WebSocketGeneratorD13 gen = new WebSocketGeneratorD13(new WebSocketBuffers(8096),endp,maskGen);
        byte[] data = message.getBytes(StringUtil.__UTF8);
        gen.addFrame((byte)0x8,(byte)0x1,data,0,data.length);
        
        endp = new ByteArrayEndPoint(endp.getOut().asArray(),4096);
                
        WebSocketParserD13 parser = new WebSocketParserD13(new WebSocketBuffers(8096),endp,new WebSocketParser.FrameHandler()
        {
            public void onFrame(byte flags, byte opcode, Buffer buffer)
            {
                received.set(buffer.toString());
            }

            public void close(int code,String message)
            {
            }
        },true);
        
        parser.parseNext();
        
        assertEquals(message,received.get());
    }
    
    
    private void lookFor(String string,InputStream in)
        throws IOException
    {
        String orig=string;
        Utf8StringBuilder scanned=new Utf8StringBuilder();
        try
        {
            while(true)
            {
                int b = in.read();
                if (b<0)
                    throw new EOFException();
                scanned.append((byte)b);
                assertEquals("looking for\""+orig+"\" in '"+scanned+"'",(int)string.charAt(0),b);
                if (string.length()==1)
                    break;
                string=string.substring(1);
            }
        }
        catch(IOException e)
        {
            System.err.println("IOE while looking for \""+orig+"\" in '"+scanned+"'");
            throw e;
        }
    }

    private void skipTo(String string,InputStream in)
    throws IOException
    {
        int state=0;

        while(true)
        {
            int b = in.read();
            if (b<0)
                throw new EOFException();

            if (b==string.charAt(state))
            {
                state++;
                if (state==string.length())
                    break;
            }
            else
                state=0;
        }
    }
    

    private static class TestWebSocket implements WebSocket.OnFrame, WebSocket.OnBinaryMessage, WebSocket.OnTextMessage
    {
        protected boolean _latch;
        boolean _onConnect=false;
        boolean _echo=true;
        boolean _aggregate=false;
        private final CountDownLatch connected = new CountDownLatch(1);
        private final CountDownLatch disconnected = new CountDownLatch(1);
        private volatile FrameConnection connection;

        public FrameConnection getConnection()
        {
            return connection;
        }

        public void onHandshake(FrameConnection connection)
        {
            this.connection = connection;
        }
        
        public void onOpen(Connection connection)
        {
            if (_onConnect)
            {
                try
                {
                    connection.sendMessage("sent on connect");
                }
                catch(IOException e)
                {
                    e.printStackTrace();
                }
            }
            connected.countDown();
        }

        private boolean awaitConnected(long time) throws InterruptedException
        {
            return connected.await(time, TimeUnit.MILLISECONDS);
        }

        private boolean awaitDisconnected(long time) throws InterruptedException
        {
            return disconnected.await(time, TimeUnit.MILLISECONDS);
        }
        
        public void onClose(int code,String message)
        {
            disconnected.countDown();
        }

        public boolean onFrame(byte flags, byte opcode, byte[] data, int offset, int length)
        {            
            if (_echo)
            {
                switch(opcode)
                {
                    case WebSocketConnectionD13.OP_CLOSE:
                    case WebSocketConnectionD13.OP_PING:
                    case WebSocketConnectionD13.OP_PONG:
                        break;
                        
                    default:
                        try
                        {
                            connection.sendFrame(flags,opcode,data,offset,length);
                        }
                        catch (IOException e)
                        {
                            e.printStackTrace();
                        }
                }
            }
            return false;
        }

        public void onMessage(byte[] data, int offset, int length)
        {
            if (_aggregate)
            {
                try
                {
                    connection.sendMessage(data,offset,length);
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
        }

        public void onMessage(String data)
        {
            __textCount.incrementAndGet();
            if (_latch)
            {
                try
                {
                    __latch.await();
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
            
            if (_aggregate)
            {
                try
                {
                    connection.sendMessage(data);
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
        }

    }
}
