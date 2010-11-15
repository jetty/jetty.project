package org.eclipse.jetty.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.security.MessageDigest;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class WebSocketTest
{
    private static TestWebSocket _websocket;
    private static LocalConnector _connector;
    private static Server _server;

    @BeforeClass
    public static void startServer() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector();
        _server.addConnector(_connector);
        WebSocketHandler handler = new WebSocketHandler()
        {
            @Override
            protected WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
            {
                _websocket = new TestWebSocket();
                return _websocket;
            }
        };
        handler.setHandler(new DefaultHandler());
        _server.setHandler(handler);

        _server.start();
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testHixieCrypt() throws Exception
    {
        assertEquals(155712099,WebSocketConnectionD00.hixieCrypt("18x 6]8vM;54 *(5:  {   U1]8  z [  8"));
        assertEquals(173347027,WebSocketConnectionD00.hixieCrypt("1_ tx7X d  <  nw  334J702) 7]o}` 0"));
    }

    @Test
    public void testHixie() throws Exception
    {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] result;
        byte[] expected;
        
        expected=md.digest(TypeUtil.fromHexString("00000000000000000000000000000000"));
        result=WebSocketConnectionD00.doTheHixieHixieShake(
                0 ,0, new byte[8]);
        assertEquals(TypeUtil.toHexString(expected),TypeUtil.toHexString(result));

        expected=md.digest(TypeUtil.fromHexString("01020304050607080000000000000000"));
        result=WebSocketConnectionD00.doTheHixieHixieShake(
                0x01020304,
                0x05060708,
                new byte[8]);
        assertEquals(TypeUtil.toHexString(expected),TypeUtil.toHexString(result));
        
        byte[] random = new byte[8];
        for (int i=0;i<8;i++)
            random[i]=(byte)(0xff&"Tm[K T2u".charAt(i));
        result=WebSocketConnectionD00.doTheHixieHixieShake(
                155712099,173347027,random);
        StringBuilder b = new StringBuilder();

        for (int i=0;i<16;i++)
            b.append((char)result[i]);
        assertEquals("fQJ,fN/4F4!~K~MH",b.toString());
    }
    
    @Test
    public void testNoWebSocket() throws Exception
    {
        String response = _connector.getResponses(
                "GET /foo HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n",false);

        assertTrue(response.startsWith("HTTP/1.1 404 "));
    }

    @Test
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

    @Test
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

    private static class TestWebSocket implements WebSocket
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
                _outbound.sendMessage("Roger That");
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

        public void onFragment(boolean more, byte opcode, byte[] data, int offset, int length)
        {
        }
    }
    

    /* draft 03 nonce proposal
    public static void main(String[] arg) throws Exception
    {
        String cnonce="A23F2BCA452DDE01";
        byte[] cnb = TypeUtil.fromHexString(cnonce);
        
        String snonce="15F0D2278BCD457F";
        byte[] snb = TypeUtil.fromHexString(snonce);
        
        
        MessageDigest md=MessageDigest.getInstance("MD5");
        md.update(cnb);
        md.update("WebSocket".getBytes("ASCII"));
        md.update(snb);
        
        byte[] digest = md.digest();
        
        System.err.println(TypeUtil.toHexString(digest));
        
        md.reset();
        md.update(snb);
        md.update("WebSocket".getBytes("ASCII"));
        md.update(cnb);
        digest = md.digest();
        
        System.err.println(TypeUtil.toHexString(digest));
        
    }
    */
}
