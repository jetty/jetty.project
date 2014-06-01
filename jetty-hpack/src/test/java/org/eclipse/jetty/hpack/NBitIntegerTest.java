package org.eclipse.jetty.hpack;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.junit.Assert;
import org.junit.Test;

public class NBitIntegerTest
{

    @Test
    public void testEncodeExampleD_1_1()
    {
        ByteBuffer buf = BufferUtil.allocate(16);
        int p=BufferUtil.flipToFill(buf);
        buf.put((byte)0x77);
        buf.put((byte)0xFF);
        NBitInteger.encode5(buf,10);
        BufferUtil.flipToFlush(buf,p);
        
        String r=TypeUtil.toHexString(BufferUtil.toArray(buf));
        
        Assert.assertEquals("77Ea",r);
        
    }
    
    @Test
    public void testDecodeExampleD_1_1()
    {
        ByteBuffer buf = ByteBuffer.wrap(TypeUtil.fromHexString("77EaFF"));
        buf.position(2);
        
        Assert.assertEquals(10,NBitInteger.dencode5(buf));
    }
    

    @Test
    public void testEncodeExampleD_1_2()
    {
        ByteBuffer buf = BufferUtil.allocate(16);
        int p=BufferUtil.flipToFill(buf);
        buf.put((byte)0x88);
        buf.put((byte)0x00);
        NBitInteger.encode5(buf,1337);
        BufferUtil.flipToFlush(buf,p);
        
        String r=TypeUtil.toHexString(BufferUtil.toArray(buf));
        
        Assert.assertEquals("881f9a0a",r);
        
    }
    
    @Test
    public void testDecodeExampleD_1_2()
    {
        ByteBuffer buf = ByteBuffer.wrap(TypeUtil.fromHexString("881f9a0aff"));
        buf.position(2);
        
        Assert.assertEquals(1337,NBitInteger.dencode5(buf));
    }
    
    
    @Test
    public void testEncodeExampleD_1_3()
    {
        ByteBuffer buf = BufferUtil.allocate(16);
        int p=BufferUtil.flipToFill(buf);
        buf.put((byte)0x88);
        buf.put((byte)0xFF);
        NBitInteger.encode8(buf,42);
        BufferUtil.flipToFlush(buf,p);
        
        String r=TypeUtil.toHexString(BufferUtil.toArray(buf));
        
        Assert.assertEquals("88Ff2a",r);
        
    }

    
    @Test
    public void testDecodeExampleD_1_3()
    {
        ByteBuffer buf = ByteBuffer.wrap(TypeUtil.fromHexString("882aFf"));
        buf.position(1);
        
        Assert.assertEquals(42,NBitInteger.dencode8(buf));
    }
    

}
