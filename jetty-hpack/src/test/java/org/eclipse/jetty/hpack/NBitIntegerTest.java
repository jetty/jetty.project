//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.hpack;

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
        NBitInteger.encode(buf,5,10);
        BufferUtil.flipToFlush(buf,p);
        
        String r=TypeUtil.toHexString(BufferUtil.toArray(buf));
        
        Assert.assertEquals("77Ea",r);
        
    }
    
    @Test
    public void testDecodeExampleD_1_1()
    {
        ByteBuffer buf = ByteBuffer.wrap(TypeUtil.fromHexString("77EaFF"));
        buf.position(2);
        
        Assert.assertEquals(10,NBitInteger.decode(buf,5));
    }
    

    @Test
    public void testEncodeExampleD_1_2()
    {
        ByteBuffer buf = BufferUtil.allocate(16);
        int p=BufferUtil.flipToFill(buf);
        buf.put((byte)0x88);
        buf.put((byte)0x00);
        NBitInteger.encode(buf,5,1337);
        BufferUtil.flipToFlush(buf,p);
        
        String r=TypeUtil.toHexString(BufferUtil.toArray(buf));
        
        Assert.assertEquals("881f9a0a",r);
        
    }
    
    @Test
    public void testDecodeExampleD_1_2()
    {
        ByteBuffer buf = ByteBuffer.wrap(TypeUtil.fromHexString("881f9a0aff"));
        buf.position(2);
        
        Assert.assertEquals(1337,NBitInteger.decode(buf,5));
    }
    
    
    @Test
    public void testEncodeExampleD_1_3()
    {
        ByteBuffer buf = BufferUtil.allocate(16);
        int p=BufferUtil.flipToFill(buf);
        buf.put((byte)0x88);
        buf.put((byte)0xFF);
        NBitInteger.encode(buf,8,42);
        BufferUtil.flipToFlush(buf,p);
        
        String r=TypeUtil.toHexString(BufferUtil.toArray(buf));
        
        Assert.assertEquals("88Ff2a",r);
        
    }

    
    @Test
    public void testDecodeExampleD_1_3()
    {
        ByteBuffer buf = ByteBuffer.wrap(TypeUtil.fromHexString("882aFf"));
        buf.position(1);
        
        Assert.assertEquals(42,NBitInteger.decode(buf,8));
    }
    

}
