package org.eclipse.jetty.websocket;

import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.util.StringUtil;
import org.junit.Before;
import org.junit.Test;

import static junit.framework.Assert.assertEquals;

/**
 * @version $Revision: 1441 $ $Date: 2010-04-02 12:28:17 +0200 (Fri, 02 Apr 2010) $
 */
public class WebSocketGeneratorTest
{
    private ByteArrayBuffer _out;
    private WebSocketGenerator _generator;

    @Before
    public void setUp() throws Exception
    {
        WebSocketBuffers buffers = new WebSocketBuffers(1024);
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        _generator = new WebSocketGenerator(buffers, endPoint);
        _out = new ByteArrayBuffer(2048);
        endPoint.setOut(_out);
    }

    @Test
    public void testOneString() throws Exception
    {
        _generator.addFrame((byte)0x04,"Hell\uFF4F W\uFF4Frld",0);
        _generator.flush();
        assertEquals(4,_out.get());
        assertEquals('H',_out.get());
        assertEquals('e',_out.get());
        assertEquals('l',_out.get());
        assertEquals('l',_out.get());
        assertEquals(0xEF,0xff&_out.get());
        assertEquals(0xBD,0xff&_out.get());
        assertEquals(0x8F,0xff&_out.get());
        assertEquals(' ',_out.get());
        assertEquals('W',_out.get());
        assertEquals(0xEF,0xff&_out.get());
        assertEquals(0xBD,0xff&_out.get());
        assertEquals(0x8F,0xff&_out.get());
        assertEquals('r',_out.get());
        assertEquals('l',_out.get());
        assertEquals('d',_out.get());
        assertEquals(0xff,0xff&_out.get());
    }

    @Test
    public void testOneBuffer() throws Exception
    {
        _generator.addFrame((byte)0x84,"Hell\uFF4F W\uFF4Frld".getBytes(StringUtil.__UTF8),0);
        _generator.flush();
        assertEquals(0x84,0xff&_out.get());
        assertEquals(15,0xff&_out.get());
        assertEquals('H',_out.get());
        assertEquals('e',_out.get());
        assertEquals('l',_out.get());
        assertEquals('l',_out.get());
        assertEquals(0xEF,0xff&_out.get());
        assertEquals(0xBD,0xff&_out.get());
        assertEquals(0x8F,0xff&_out.get());
        assertEquals(' ',_out.get());
        assertEquals('W',_out.get());
        assertEquals(0xEF,0xff&_out.get());
        assertEquals(0xBD,0xff&_out.get());
        assertEquals(0x8F,0xff&_out.get());
        assertEquals('r',_out.get());
        assertEquals('l',_out.get());
        assertEquals('d',_out.get());
    }

    @Test
    public void testOneLongBuffer() throws Exception
    {
        byte[] b=new byte[150];
        for (int i=0;i<b.length;i++)
            b[i]=(byte)('0'+(i%10));

        _generator.addFrame((byte)0x85,b,0);

        _generator.flush();
        assertEquals(0x85,0xff&_out.get());
        assertEquals(0x80|(b.length>>7),0xff&_out.get());
        assertEquals(0x7f&b.length,0xff&_out.get());
        for (int i=0;i<b.length;i++)
            assertEquals('0'+(i%10),0xff&_out.get());
    }
}
