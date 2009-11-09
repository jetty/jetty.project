package org.eclipse.jetty.websocket;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.SimpleBuffers;
import org.eclipse.jetty.util.StringUtil;

import junit.framework.TestCase;


/* ------------------------------------------------------------ */
/**
 */
public class WebSocketGeneratorTest extends TestCase
{
    
    Buffers _buffers;
    ByteArrayBuffer _out;
    ByteArrayEndPoint _endp;
    WebSocketGenerator _generator;

    /* ------------------------------------------------------------ */
    @Override
    protected void setUp() throws Exception
    {
        _buffers=new SimpleBuffers(null,new ByteArrayBuffer(1024));
        _endp = new ByteArrayEndPoint();
        _generator = new WebSocketGenerator(_buffers,_endp);
        _out = new ByteArrayBuffer(2048);
        _endp.setOut(_out);
    }
    
    
    public void testOneString() throws Exception
    {
        _generator.addMessage((byte)0x04,"Hell\uFF4F W\uFF4Frld",0);
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
    
    public void testOneBuffer() throws Exception
    {
        _generator.addMessage((byte)0x04,new ByteArrayBuffer("Hell\uFF4F W\uFF4Frld",StringUtil.__UTF8),0);
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
    
    public void testOneLongBuffer() throws Exception
    {
        byte[] b=new byte[150];
        for (int i=0;i<b.length;i++)
            b[i]=(byte)('0'+(i%10));
        
        
        _generator.addMessage((byte)0x05,new ByteArrayBuffer(b),0);
        
        _generator.flush();
        assertEquals(0x85,0xff&_out.get());
        assertEquals(0x80|(b.length>>7),0xff&_out.get());
        assertEquals(0x7f&b.length,0xff&_out.get());
        for (int i=0;i<b.length;i++)
            assertEquals('0'+(i%10),0xff&_out.get());
    }
    
    

}
