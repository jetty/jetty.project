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
import java.util.concurrent.atomic.AtomicReference;

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
public class WebSocketMessageD06Test
{
    private static Server _server;
    private static Connector _connector;
    private static TestWebSocket _serverWebSocket;

    @BeforeClass
    public static void startServer() throws Exception
    {
        _server = new Server();
        _connector = new SelectChannelConnector();
        _server.addConnector(_connector);
        WebSocketHandler wsHandler = new WebSocketHandler()
        {
            @Override
            protected WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
            {
                _serverWebSocket = new TestWebSocket();
                _serverWebSocket.onConnect=("onConnect".equals(protocol));
                _serverWebSocket.echo=("echo".equals(protocol));
                return _serverWebSocket;
            }
        };
        wsHandler.setMaxIdleTime(1000);
        wsHandler.setHandler(new DefaultHandler());
        _server.setHandler(wsHandler);
        _server.start();
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        _server.stop();
        _server.join();
    }

    
    @Test
    public void testHash()
    {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",WebSocketConnectionD06.hashKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }
    
    @Test
    public void testServerSendBigStringMessage() throws Exception
    {
        Socket socket = new Socket("localhost", _connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: chat, superchat\r\n"+
                 "Sec-WebSocket-Version: 6\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();
        
        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(_serverWebSocket.awaitConnected(1000));
        assertNotNull(_serverWebSocket.outbound);
        
        // Server sends a big message
        StringBuilder message = new StringBuilder();
        String text = "0123456789ABCDEF";
        for (int i = 0; i < (0x2000) / text.length(); i++)
            message.append(text);
        String data=message.toString();
        _serverWebSocket.outbound.sendMessage(data);

        assertEquals(WebSocket.OP_TEXT,input.read());
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
        Socket socket = new Socket("localhost", _connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: onConnect\r\n" +
                 "Sec-WebSocket-Version: 6\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();
        
        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(_serverWebSocket.awaitConnected(1000));
        assertNotNull(_serverWebSocket.outbound);
        
        assertEquals(0x84,input.read());
        assertEquals(0x0f,input.read());
        lookFor("sent on connect",input);
    }

    @Test
    public void testServerEcho() throws Exception
    {
        Socket socket = new Socket("localhost", _connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: echo\r\n" +
                 "Sec-WebSocket-Version: 6\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
        output.flush();
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0x84^0xff);
        output.write(0x0f^0xff);
        byte[] bytes="this is an echo".getBytes(StringUtil.__ISO_8859_1_CHARSET);
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

        assertTrue(_serverWebSocket.awaitConnected(1000));
        assertNotNull(_serverWebSocket.outbound);
        
        assertEquals(0x84,input.read());
        assertEquals(0x0f,input.read());
        lookFor("this is an echo",input);
    }

    @Test
    public void testServerPingPong() throws Exception
    {
        Socket socket = new Socket("localhost", _connector.getLocalPort());
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
                 "Sec-WebSocket-Version: 6\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
        output.flush();
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0x82^0xff);
        output.write(0x00^0xff);
        output.flush();

        InputStream input = socket.getInputStream();
        
        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(_serverWebSocket.awaitConnected(1000));
        assertNotNull(_serverWebSocket.outbound);

        socket.setSoTimeout(1000);
        assertEquals(0x83,input.read());
        assertEquals(0x00,input.read());
    }

    @Test
    public void testIdle() throws Exception
    {
        Socket socket = new Socket("localhost", _connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                 "Host: server.example.com\r\n"+
                 "Upgrade: websocket\r\n"+
                 "Connection: Upgrade\r\n"+
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                 "Sec-WebSocket-Origin: http://example.com\r\n"+
                 "Sec-WebSocket-Protocol: onConnect\r\n" +
                 "Sec-WebSocket-Version: 6\r\n"+
                 "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(10000);

        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);

        assertTrue(_serverWebSocket.awaitConnected(1000));
        assertNotNull(_serverWebSocket.outbound);
        
        assertEquals(0x84,input.read());
        assertEquals(0x0f,input.read());
        lookFor("sent on connect",input);

        assertEquals((byte)0x81,(byte)input.read());
        assertEquals(0x06,input.read());
        assertEquals(1000/0xff,input.read());
        assertEquals(1000%0xff,input.read());
        lookFor("Idle",input);

        // respond to close
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0xff);
        output.write(0x81^0xff);
        output.write(0x00^0xff);
        output.flush();
        
        
        assertTrue(_serverWebSocket.awaitDisconnected(5000));
        try
        {
            _serverWebSocket.outbound.sendMessage("Don't send");
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
        Socket socket = new Socket("localhost", _connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /chat HTTP/1.1\r\n"+
                        "Host: server.example.com\r\n"+
                        "Upgrade: websocket\r\n"+
                        "Connection: Upgrade\r\n"+
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                        "Sec-WebSocket-Origin: http://example.com\r\n"+
                        "Sec-WebSocket-Protocol: onConnect\r\n" +
                        "Sec-WebSocket-Version: 6\r\n"+
                "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();

        lookFor("HTTP/1.1 101 Switching Protocols\r\n",input);
        skipTo("Sec-WebSocket-Accept: ",input);
        lookFor("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",input);
        skipTo("\r\n\r\n",input);


        assertTrue(_serverWebSocket.awaitConnected(1000));
        assertNotNull(_serverWebSocket.outbound);
        
        assertEquals(0x84,input.read());
        assertEquals(0x0f,input.read());
        lookFor("sent on connect",input);
        socket.close();
        
        assertTrue(_serverWebSocket.awaitDisconnected(500));
        

        try
        {
            _serverWebSocket.outbound.sendMessage("Don't send");
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
        
        WebSocketGeneratorD06 gen = new WebSocketGeneratorD06(new WebSocketBuffers(8096),endp,null);
        gen.addFrame((byte)0x4,message,1000);
        
        endp = new ByteArrayEndPoint(endp.getOut().asArray(),4096);
                
        WebSocketParserD06 parser = new WebSocketParserD06(new WebSocketBuffers(8096),endp,new WebSocketParser.FrameHandler()
        {
            public void onFrame(boolean more, byte flags, byte opcode, Buffer buffer)
            {
                received.set(buffer.toString());
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

        WebSocketGeneratorD06.MaskGen maskGen = new WebSocketGeneratorD06.RandomMaskGen();
        
        WebSocketGeneratorD06 gen = new WebSocketGeneratorD06(new WebSocketBuffers(8096),endp,maskGen);
        gen.addFrame((byte)0x4,message,1000);
        
        endp = new ByteArrayEndPoint(endp.getOut().asArray(),4096);
                
        WebSocketParserD06 parser = new WebSocketParserD06(new WebSocketBuffers(8096),endp,new WebSocketParser.FrameHandler()
        {
            public void onFrame(boolean more, byte flags, byte opcode, Buffer buffer)
            {
                received.set(buffer.toString());
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
    

    private static class TestWebSocket implements WebSocket
    {
        boolean onConnect=false;
        boolean echo=true;
        private final CountDownLatch connected = new CountDownLatch(1);
        private final CountDownLatch disconnected = new CountDownLatch(1);
        private volatile Outbound outbound;

        public void onConnect(Outbound outbound)
        {
            this.outbound = outbound;
            if (onConnect)
            {
                try
                {
                    outbound.sendMessage("sent on connect");
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

        public void onMessage(byte opcode, String data)
        {
            if (echo)
            {
                try
                {
                    outbound.sendMessage(opcode,data);
                }
                catch(IOException e)
                {
                    e.printStackTrace();
                }
            }   
        }

        public void onMessage(byte opcode, byte[] data, int offset, int length)
        {
            if (echo)
            {
                try
                {
                    outbound.sendMessage(opcode,data,offset,length);
                }
                catch(IOException e)
                {
                    e.printStackTrace();
                }
            }
        }

        public void onDisconnect()
        {
            disconnected.countDown();
        }

        public void onFragment(boolean more, byte opcode, byte[] data, int offset, int length)
        {
            if (echo)
            {
                try
                {
                    outbound.sendFragment(more,opcode,data,offset,length);
                }
                catch(IOException e)
                {
                    e.printStackTrace();
                }
            }
        }
    }
}
