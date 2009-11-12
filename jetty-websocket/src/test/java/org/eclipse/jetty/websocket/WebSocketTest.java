package org.eclipse.jetty.websocket;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;

import junit.framework.TestCase;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;

public class WebSocketTest extends TestCase
{
    TestWebSocket _websocket;
    LocalConnector _connector;
    Server _server;
    WebSocketHandler _handler;
    
    
    
    /* ------------------------------------------------------------ */
    @Override
    protected void setUp() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector();
        _server.addConnector(_connector);
        _handler= new WebSocketHandler()
        {
            @Override
            protected WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
            {
                _websocket = new TestWebSocket();
                return _websocket;
            }
        };
        _handler.setHandler(new DefaultHandler());
        _server.setHandler(_handler);
        
        _server.start();    
    }
    
    
    public void testNoWebSocket() throws Exception
    {
        String response = _connector.getResponses(
                "GET /foo HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n",false);
        
        assertTrue(response.startsWith("HTTP/1.1 404 "));
    }

    /* ------------------------------------------------------------ */
    public void testOpenWebSocket() throws Exception
    {
        String response = _connector.getResponses(
                "GET /demo HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Upgrade: WebSocket\r\n" +
                "Connection: Upgrade\r\n" +
                "\r\n",false);
        
        assertTrue(response.startsWith("HTTP/1.1 101 Web Socket Protocol Handshake"));
        assertTrue(response.contains("Upgrade: WebSocket"));
        assertTrue(response.contains("Connection: Upgrade"));
    }

    /* ------------------------------------------------------------ */
    public void testSendReceiveUtf8WebSocket() throws Exception
    {
        ByteArrayBuffer buffer = new ByteArrayBuffer(1024);
        
        buffer.put(
                ("GET /demo HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Upgrade: WebSocket\r\n" +
                "Connection: Upgrade\r\n" +
                "\r\n").getBytes(StringUtil.__ISO_8859_1));
        
        buffer.put((byte)0);
        buffer.put("Hello World".getBytes(StringUtil.__UTF8));
        buffer.put((byte)0xFF);
        
        ByteArrayBuffer out = _connector.getResponses(buffer,true);
        
        String response = StringUtil.printable(out.asArray());
        
        assertTrue(response.startsWith("HTTP/1.1 101 Web Socket Protocol Handshake"));
        assertTrue(response.contains("Upgrade: WebSocket"));
        assertTrue(response.contains("Connection: Upgrade"));
        assertTrue(response.contains("0x00Roger That0xFF"));
        
        assertEquals("Hello World",_websocket._utf8);
    }
    

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class TestWebSocket implements WebSocket
    {
        Outbound _outbound;
        Buffer _binary;
        String _utf8;
        boolean _disconnected;

        public void onConnect(Outbound outbound)
        {
            _outbound=outbound;
            try
            {
                _outbound.sendMessage(SENTINEL_FRAME,"Roger That");
            }
            catch (IOException e)
            {
                Log.warn(e);
            }
        }
        
        public void onMessage(byte frame, byte[] data,int offset, int length)
        {
            _binary=new ByteArrayBuffer(data,offset,length).duplicate(Buffer.READONLY);
        }

        public void onMessage(byte frame, String data)
        {
            _utf8=data;
        }

        public void onDisconnect()
        {
            _disconnected=true;
        }
    }
    
}
