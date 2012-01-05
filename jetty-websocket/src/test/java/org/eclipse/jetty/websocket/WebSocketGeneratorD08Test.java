/*******************************************************************************
 * Copyright (c) 2011 Intalio, Inc.
 * ======================================================================
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *   The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 *
 *   The Apache License v2.0 is available at
 *   http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 *******************************************************************************/
package org.eclipse.jetty.websocket;

import static junit.framework.Assert.*;

import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.util.StringUtil;
import org.junit.Before;
import org.junit.Test;

public class WebSocketGeneratorD08Test
{
    private ByteArrayBuffer _out;
    private WebSocketGenerator _generator;
    ByteArrayEndPoint _endPoint;
    WebSocketBuffers _buffers;
    byte[] _mask = new byte[4];
    int _m;
    
    public MaskGen _maskGen = new FixedMaskGen(
            new byte[]{(byte)0x00,(byte)0x00,(byte)0x0f,(byte)0xff});

    @Before
    public void setUp() throws Exception
    {
        _endPoint = new ByteArrayEndPoint();
        _out = new ByteArrayBuffer(2048);
        _endPoint.setOut(_out);
        _buffers = new WebSocketBuffers(1024);
        _m=0;
    }

    byte getMasked()
    {
        return (byte)(_out.get()^_mask[_m++%4]);
    }
    
    
    @Test
    public void testOneString() throws Exception
    {
        _generator = new WebSocketGeneratorD08(_buffers, _endPoint,null);

        byte[] data = "Hell\uFF4F W\uFF4Frld".getBytes(StringUtil.__UTF8);
        _generator.addFrame((byte)0x8,(byte)0x04,data,0,data.length);
        _generator.flush();
        assertEquals((byte)0x84,_out.get());
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
    public void testOneBuffer() throws Exception
    {
        _generator = new WebSocketGeneratorD08(_buffers, _endPoint,null);
        
        String string = "Hell\uFF4F W\uFF4Frld";
        byte[] bytes=string.getBytes(StringUtil.__UTF8);
        _generator.addFrame((byte)0x8,(byte)0x04,bytes,0,bytes.length);
        _generator.flush();
        assertEquals((byte)0x84,_out.get());
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
        _generator = new WebSocketGeneratorD08(_buffers, _endPoint,null);
        
        byte[] b=new byte[150];
        for (int i=0;i<b.length;i++)
            b[i]=(byte)('0'+(i%10));

        _generator.addFrame((byte)0x8,(byte)0x4,b,0,b.length);

        _generator.flush();
        assertEquals((byte)0x84,_out.get());
        assertEquals((byte)126,_out.get());
        assertEquals((byte)0,_out.get());
        assertEquals((byte)b.length,_out.get());
        
        for (int i=0;i<b.length;i++)
            assertEquals('0'+(i%10),0xff&_out.get());
    }

    @Test
    public void testOneStringMasked() throws Exception
    {
        _generator = new WebSocketGeneratorD08(_buffers, _endPoint,_maskGen);

        byte[] data = "Hell\uFF4F W\uFF4Frld".getBytes(StringUtil.__UTF8);
        _generator.addFrame((byte)0x8,(byte)0x04,data,0,data.length);
        _generator.flush();
        
        assertEquals((byte)0x84,_out.get());
        assertEquals(15,0x7f&_out.get());
        _out.get(_mask,0,4);
        assertEquals('H',getMasked());
        assertEquals('e',getMasked());
        assertEquals('l',getMasked());
        assertEquals('l',getMasked());
        assertEquals(0xEF,0xff&getMasked());
        assertEquals(0xBD,0xff&getMasked());
        assertEquals(0x8F,0xff&getMasked());
        assertEquals(' ',getMasked());
        assertEquals('W',getMasked());
        assertEquals(0xEF,0xff&getMasked());
        assertEquals(0xBD,0xff&getMasked());
        assertEquals(0x8F,0xff&getMasked());
        assertEquals('r',getMasked());
        assertEquals('l',getMasked());
        assertEquals('d',getMasked());
    }

    @Test
    public void testOneBufferMasked() throws Exception
    {
        _generator = new WebSocketGeneratorD08(_buffers, _endPoint,_maskGen);
        
        String string = "Hell\uFF4F W\uFF4Frld";
        byte[] bytes=string.getBytes(StringUtil.__UTF8);
        _generator.addFrame((byte)0x8,(byte)0x04,bytes,0,bytes.length);
        _generator.flush();
        
        assertEquals((byte)0x84,_out.get());
        assertEquals(15,0x7f&_out.get());
        _out.get(_mask,0,4);
        assertEquals('H',getMasked());
        assertEquals('e',getMasked());
        assertEquals('l',getMasked());
        assertEquals('l',getMasked());
        assertEquals(0xEF,0xff&getMasked());
        assertEquals(0xBD,0xff&getMasked());
        assertEquals(0x8F,0xff&getMasked());
        assertEquals(' ',getMasked());
        assertEquals('W',getMasked());
        assertEquals(0xEF,0xff&getMasked());
        assertEquals(0xBD,0xff&getMasked());
        assertEquals(0x8F,0xff&getMasked());
        assertEquals('r',getMasked());
        assertEquals('l',getMasked());
        assertEquals('d',getMasked());
    }

    @Test
    public void testOneLongBufferMasked() throws Exception
    {
        _generator = new WebSocketGeneratorD08(_buffers, _endPoint,_maskGen);
        
        byte[] b=new byte[150];
        for (int i=0;i<b.length;i++)
            b[i]=(byte)('0'+(i%10));

        _generator.addFrame((byte)0x8,(byte)0x04,b,0,b.length);
        _generator.flush();
        
        assertEquals((byte)0x84,_out.get());
        assertEquals((byte)126,0x7f&_out.get());
        assertEquals((byte)0,_out.get());
        assertEquals((byte)b.length,_out.get());

        _out.get(_mask,0,4);
        
        for (int i=0;i<b.length;i++)
            assertEquals('0'+(i%10),0xff&getMasked());
    }
    
}
