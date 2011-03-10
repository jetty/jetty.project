package org.eclipse.jetty.websocket;

import static junit.framework.Assert.assertEquals;

import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.util.StringUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * @version $Revision$ $Date$
 */
public class WebSocketGeneratorD00Test
{
    private ByteArrayBuffer _out;
    private WebSocketGenerator _generator;

    @Before
    public void setUp() throws Exception
    {
        WebSocketBuffers buffers = new WebSocketBuffers(1024);
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        _generator = new WebSocketGeneratorD01(buffers, endPoint);
        _out = new ByteArrayBuffer(4096);
        endPoint.setOut(_out);
    }

    @Test
    public void testOneString() throws Exception
    {
        byte[] data="Hell\uFF4F W\uFF4Frld".getBytes(StringUtil.__UTF8);
        _generator.addFrame((byte)0x0,(byte)0x04,data,0,data.length,0);
        _generator.flush();
        assertEquals(4,_out.get());
        assertEquals(15,_out.get());
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
    public void testOneMediumBuffer() throws Exception
    {
        byte[] b=new byte[501];
        for (int i=0;i<b.length;i++)
            b[i]=(byte)('0'+(i%10));

        _generator.addFrame((byte)0x0,(byte)0xf,b,0,b.length,0);

        _generator.flush();
        assertEquals(0x0f,_out.get());
        assertEquals(0x7e,_out.get());
        assertEquals((b.length>>8),0xff&_out.get());
        assertEquals(0xff&b.length,0xff&_out.get());
        for (int i=0;i<b.length;i++)
            assertEquals('0'+(i%10),0xff&_out.get());
    }

    @Test
    public void testFragmentBuffer() throws Exception
    {
        byte[] b=new byte[3001];
        for (int i=0;i<b.length;i++)
            b[i]=(byte)('0'+(i%10));

        _generator.addFrame((byte)0x0,(byte)0xf,b,0,b.length,0);

        _generator.flush();
        assertEquals(0x8f,_out.get()&0xff);
        assertEquals(0x7e,_out.get()&0xff);

        int m=0;
        
        int frag=((_out.get()&0xff)<<8)|_out.get()&0xff;
        for (int i=0;i<frag;i++)
        {
            assertEquals("b="+i,'0'+(m%10),0xff&_out.get());
            m++;
        }

        assertEquals(0x8f,_out.get()&0xff);
        assertEquals(0x7e,_out.get()&0xff);
        frag=((_out.get()&0xff)<<8)|_out.get()&0xff;
        for (int i=0;i<frag;i++)
        {
            assertEquals("b="+i,'0'+(m%10),0xff&_out.get());
            m++;
        }
        
        assertEquals(0x0f,_out.get()&0xff);
        assertEquals(0x7e,_out.get()&0xff);
        frag=((_out.get()&0xff)<<8)|_out.get()&0xff;
        for (int i=0;i<frag;i++)
        {
            assertEquals("b="+i,'0'+(m%10),0xff&_out.get());
            m++;
        }
        assertEquals(b.length,m);
        
    }
}
