package org.eclipse.jetty.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @version $Revision$ $Date$
 */
public class WebSocketMessageD01Test
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
    public void testServerSendBigStringMessage() throws Exception
    {
        Socket socket = new Socket("localhost", _connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /test HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Upgrade: WebSocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Draft: 1\r\n" +
                "Sec-WebSocket-Key1: 4 @1  46546xW%0l 1 5\r\n" +
                "Sec-WebSocket-Key2: 12998 5 Y3 1  .P00\r\n" +
                "\r\n"+
                "^n:ds[4U").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();
        
        lookFor("HTTP/1.1 101 WebSocket Protocol Handshake\r\n",input);
        skipTo("\r\n\r\n",input);

        assertTrue(_serverWebSocket.awaitConnected(1000));
        assertNotNull(_serverWebSocket.outbound);
        
        lookFor("8jKS'y:G*Co,Wxa-",input);
        
        // Server sends a big message
        StringBuilder message = new StringBuilder();
        String text = "0123456789ABCDEF";
        for (int i = 0; i < (0x2000) / text.length(); i++)
            message.append(text);
        String data=message.toString();
        _serverWebSocket.outbound.sendMessage(data);

        assertEquals(0x80,input.read());
        assertEquals(0x7e,input.read());
        assertEquals(0x1f,input.read());
        assertEquals(0xf6,input.read());
        lookFor(data.substring(0,0x1ff6),input);
        assertEquals(0x00,input.read());
        assertEquals(0x0A,input.read());
        lookFor(data.substring(0x1ff6),input);
    }

    @Test
    public void testServerSendOnConnect() throws Exception
    {
        Socket socket = new Socket("localhost", _connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /test HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Upgrade: WebSocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Draft: 1\r\n" +
                "Sec-WebSocket-Protocol: onConnect\r\n" +
                "Sec-WebSocket-Key1: 4 @1  46546xW%0l 1 5\r\n" +
                "Sec-WebSocket-Key2: 12998 5 Y3 1  .P00\r\n" +
                "\r\n"+
                "^n:ds[4U").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();
        lookFor("HTTP/1.1 101 WebSocket Protocol Handshake\r\n",input);
        skipTo("\r\n\r\n",input);

        assertTrue(_serverWebSocket.awaitConnected(1000));
        assertNotNull(_serverWebSocket.outbound);
        
        lookFor("8jKS'y:G*Co,Wxa-",input);
        assertEquals(0x00,input.read());
        assertEquals(0x0f,input.read());
        lookFor("sent on connect",input);
    }

    @Test
    public void testIdle() throws Exception
    {
        Socket socket = new Socket("localhost", _connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /test HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Upgrade: WebSocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Draft: 1\r\n" +
                "Sec-WebSocket-Protocol: onConnect\r\n" +
                "Sec-WebSocket-Key1: 4 @1  46546xW%0l 1 5\r\n" +
                "Sec-WebSocket-Key2: 12998 5 Y3 1  .P00\r\n" +
                "\r\n"+
                "^n:ds[4U").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();
        
        lookFor("HTTP/1.1 101 WebSocket Protocol Handshake\r\n",input);
        skipTo("\r\n\r\n",input);

        assertTrue(_serverWebSocket.awaitConnected(1000));
        assertNotNull(_serverWebSocket.outbound);
        
        lookFor("8jKS'y:G*Co,Wxa-",input);
        assertEquals(0x00,input.read());
        assertEquals(0x0f,input.read());
        lookFor("sent on connect",input);
        
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
                ("GET /test HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Upgrade: WebSocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Draft: 1\r\n" +
                "Sec-WebSocket-Protocol: onConnect\r\n" +
                "Sec-WebSocket-Key1: 4 @1  46546xW%0l 1 5\r\n" +
                "Sec-WebSocket-Key2: 12998 5 Y3 1  .P00\r\n" +
                "\r\n"+
                "^n:ds[4U").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();
        
        lookFor("HTTP/1.1 101 WebSocket Protocol Handshake\r\n",input);
        skipTo("\r\n\r\n",input);

        assertTrue(_serverWebSocket.awaitConnected(1000));
        assertNotNull(_serverWebSocket.outbound);
        
        lookFor("8jKS'y:G*Co,Wxa-",input);
        assertEquals(0x00,input.read());
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
    private void lookFor(String string,InputStream in)
        throws IOException
    {
        while(true)
        {
            int b = in.read();
            if (b<0)
                throw new EOFException();

            assertEquals((int)string.charAt(0),b);
            if (string.length()==1)
                break;
            string=string.substring(1);
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

        public void onMessage(byte frame, String data)
        {
        }

        public void onMessage(byte frame, byte[] data, int offset, int length)
        {
        }

        public void onDisconnect()
        {
            disconnected.countDown();
        }

        public void onFragment(boolean more, byte opcode, byte[] data, int offset, int length)
        {
        }
    }
}
