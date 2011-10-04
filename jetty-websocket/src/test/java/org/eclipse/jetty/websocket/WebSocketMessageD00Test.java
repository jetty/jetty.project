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
import org.eclipse.jetty.util.StringUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @version $Revision$ $Date$
 */
public class WebSocketMessageD00Test
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
            public WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
            {
                _serverWebSocket = new TestWebSocket();
                _serverWebSocket.onConnect=("onConnect".equals(protocol));
                return _serverWebSocket;
            }
        };
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
                "Sec-WebSocket-Key1: 4 @1  46546xW%0l 1 5\r\n" +
                "Sec-WebSocket-Key2: 12998 5 Y3 1  .P00\r\n" +
                "\r\n"+
                "^n:ds[4U").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "ISO-8859-1"));
        String responseLine = reader.readLine();
        System.err.println(responseLine);
        assertTrue(responseLine.startsWith("HTTP/1.1 101 WebSocket Protocol Handshake"));
        // Read until we find an empty line, which signals the end of the http response
        String line;
        while ((line = reader.readLine()) != null)
            if (line.length() == 0)
                break;
        
        assertTrue(_serverWebSocket.awaitConnected(1000));
        assertNotNull(_serverWebSocket.outbound);
        
        // read the hixie bytes
        char[] hixie=new char[16]; // should be bytes, but we know this example is all ascii
        int h=16;
        int o=0;
        do 
        {
            int l=reader.read(hixie,o,h);
            if (l<0)
                break;
            h-=l;
            o+=l;
        }
        while (h>0);
        assertEquals("8jKS'y:G*Co,Wxa-",new String(hixie,0,16));
        
        // Server sends a big message
        StringBuilder message = new StringBuilder();
        String text = "0123456789ABCDEF";
        for (int i = 0; i < 64 * 1024 / text.length(); ++i)
            message.append(text);
        _serverWebSocket.outbound.sendMessage(message.toString());

        // Read until we get 0xFF
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        while (true)
        {
            int read = input.read();
            baos.write(read);
            if (read == 0xFF)
                break;
        }
        baos.close();
        byte[] bytes = baos.toByteArray();
        String result = StringUtil.printable(bytes);
        assertTrue(result.startsWith("0x00"));
        assertTrue(result.endsWith("0xFF"));
        assertEquals(message.length() + "0x00".length() + "0xFF".length(), result.length());
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
                "Sec-WebSocket-Protocol: onConnect\r\n" +
                "Sec-WebSocket-Key1: 4 @1  46546xW%0l 1 5\r\n" +
                "Sec-WebSocket-Key2: 12998 5 Y3 1  .P00\r\n" +
                "\r\n"+
                "^n:ds[4U").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();
        
        String looking_for="HTTP/1.1 101 WebSocket Protocol Handshake\r\n";

        while(true)
        {
            int b = input.read();
            if (b<0)
                throw new EOFException();

            assertEquals((int)looking_for.charAt(0),b);
            if (looking_for.length()==1)
                break;
            looking_for=looking_for.substring(1);
        }

        String skipping_for="\r\n\r\n";
        int state=0;

        while(true)
        {
            int b = input.read();
            if (b<0)
                throw new EOFException();

            if (b==skipping_for.charAt(state))
            {
                state++;
                if (state==skipping_for.length())
                    break;
            }
            else
                state=0;
        }
        

        assertTrue(_serverWebSocket.awaitConnected(1000));
        assertNotNull(_serverWebSocket.outbound);
        
        looking_for="8jKS'y:G*Co,Wxa-";
        while(true)
        {
            int b = input.read();
            if (b<0)
                throw new EOFException();

            assertEquals((int)looking_for.charAt(0),b);
            if (looking_for.length()==1)
                break;
            looking_for=looking_for.substring(1);
        }
        
        assertEquals(0x00,input.read());
        looking_for="sent on connect";
        while(true)
        {
            int b = input.read();
            if (b<0)
                throw new EOFException();

            assertEquals((int)looking_for.charAt(0),b);
            if (looking_for.length()==1)
                break;
            looking_for=looking_for.substring(1);
        }
        assertEquals(0xff,input.read());
    }


    private static class TestWebSocket implements WebSocket
    {
        boolean onConnect=false;
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile Connection outbound;

        public void onOpen(Connection outbound)
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
            latch.countDown();
        }

        private boolean awaitConnected(long time) throws InterruptedException
        {
            return latch.await(time, TimeUnit.MILLISECONDS);
        }
        
        public void onClose(int code,String message)
        {
        }
    }
}
