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

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.HttpHeaderValues;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.BufferCache.CachedBuffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.junit.Before;
import org.junit.Test;

public class WebSocketParserD08Test
{
    private MaskedByteArrayBuffer _in;
    private Handler _handler;
    private WebSocketParserD08 _parser;
    private byte[] _mask = new byte[] {(byte)0x00,(byte)0xF0,(byte)0x0F,(byte)0xFF};
    private int _m;

    class MaskedByteArrayBuffer extends ByteArrayBuffer
    {
        MaskedByteArrayBuffer()
        {
            super(4096);
        }
        
        public void sendMask()
        {
            super.poke(putIndex(),_mask,0,4);
            super.setPutIndex(putIndex()+4);
            _m=0;
        }

        @Override
        public int put(Buffer src)
        {
            return put(src.asArray(),0,src.length());
        }

        public void putUnmasked(byte b)
        {
            super.put(b);
        }
        
        @Override
        public void put(byte b)
        {
            super.put((byte)(b^_mask[_m++%4]));
        }

        @Override
        public int put(byte[] b, int offset, int length)
        {
            byte[] mb = new byte[b.length];
            final int end=offset+length;
            for (int i=offset;i<end;i++)
            {
                mb[i]=(byte)(b[i]^_mask[_m++%4]);
            }
            return super.put(mb,offset,length);
        }

        @Override
        public int put(byte[] b)
        {
            return put(b,0,b.length);
        }
        
    };
    
    
    @Before
    public void setUp() throws Exception
    {
        WebSocketBuffers buffers = new WebSocketBuffers(1024);
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        endPoint.setNonBlocking(true);
        _handler = new Handler();
        _parser=new WebSocketParserD08(buffers, endPoint,_handler,true);
        _parser.setFakeFragments(false);
        _in = new MaskedByteArrayBuffer();
        
        endPoint.setIn(_in);
    }

    @Test
    public void testCache() throws Exception
    {
        assertEquals(HttpHeaderValues.UPGRADE_ORDINAL ,((CachedBuffer)HttpHeaderValues.CACHE.lookup("Upgrade")).getOrdinal());
    }

    @Test
    public void testFlagsOppcode() throws Exception
    {
        _in.putUnmasked((byte)0xff);
        _in.putUnmasked((byte)0x80);
        _in.sendMask();

        int progress =_parser.parseNext();

        assertTrue(progress>0);
        assertEquals(0xf,_handler._flags);
        assertEquals(0xf,_handler._opcode);
        assertTrue(_parser.isBufferEmpty());
        _parser.returnBuffer();
        assertTrue(_parser.getBuffer()==null);
    }
    
    @Test
    public void testShortText() throws Exception
    {
        _in.putUnmasked((byte)0x81);
        _in.putUnmasked((byte)(0x80|11));
        _in.sendMask();
        _in.put("Hello World".getBytes(StringUtil.__UTF8));
        // System.err.println("tosend="+TypeUtil.toHexString(_in.asArray()));

        int progress =_parser.parseNext();

        assertEquals(18,progress);
        assertEquals("Hello World",_handler._data.get(0));
        assertEquals(0x8,_handler._flags);
        assertEquals(0x1,_handler._opcode);
        assertTrue(_parser.isBufferEmpty());
        _parser.returnBuffer();
        assertTrue(_parser.getBuffer()==null);
    }
    
    @Test
    public void testShortUtf8() throws Exception
    {
        String string = "Hell\uFF4f W\uFF4Frld";
        byte[] bytes = string.getBytes("UTF-8");

        _in.putUnmasked((byte)0x81);
        _in.putUnmasked((byte)(0x80|bytes.length));
        _in.sendMask();
        _in.put(bytes);

        int progress =_parser.parseNext();

        assertEquals(bytes.length+7,progress);
        assertEquals(string,_handler._data.get(0));
        assertEquals(0x8,_handler._flags);
        assertEquals(0x1,_handler._opcode);
        _parser.returnBuffer();
        assertTrue(_parser.isBufferEmpty());
        assertTrue(_parser.getBuffer()==null);
    }
    
    @Test
    public void testMediumText() throws Exception
    {
        String string = "Hell\uFF4f Medium W\uFF4Frld ";
        for (int i=0;i<4;i++)
            string = string+string;
        string += ". The end.";
        
        byte[] bytes = string.getBytes(StringUtil.__UTF8);
        
        _in.putUnmasked((byte)0x81);
        _in.putUnmasked((byte)(0x80|0x7E));
        _in.putUnmasked((byte)(bytes.length>>8));
        _in.putUnmasked((byte)(bytes.length&0xff));
        _in.sendMask();
        _in.put(bytes);

        int progress =_parser.parseNext();

        assertEquals(bytes.length+9,progress);
        assertEquals(string,_handler._data.get(0));
        assertEquals(0x8,_handler._flags);
        assertEquals(0x1,_handler._opcode);
        _parser.returnBuffer();
        assertTrue(_parser.isBufferEmpty());
        assertTrue(_parser.getBuffer()==null);
    }
    
    @Test
    public void testLongText() throws Exception
    {
        WebSocketBuffers buffers = new WebSocketBuffers(0x20000);
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        WebSocketParserD08 parser=new WebSocketParserD08(buffers, endPoint,_handler,false);
        ByteArrayBuffer in = new ByteArrayBuffer(0x20000);
        endPoint.setIn(in);
        
        String string = "Hell\uFF4f Big W\uFF4Frld ";
        for (int i=0;i<12;i++)
            string = string+string;
        string += ". The end.";
        
        byte[] bytes = string.getBytes("UTF-8");

        _in.sendMask();
        in.put((byte)0x84);
        in.put((byte)0x7F);
        in.put((byte)0x00);
        in.put((byte)0x00);
        in.put((byte)0x00);
        in.put((byte)0x00);
        in.put((byte)0x00);
        in.put((byte)(bytes.length>>16));
        in.put((byte)((bytes.length>>8)&0xff));
        in.put((byte)(bytes.length&0xff));
        in.put(bytes);

        int progress =parser.parseNext();
        parser.returnBuffer();

        assertEquals(bytes.length+11,progress);
        assertEquals(string,_handler._data.get(0));
        assertTrue(parser.isBufferEmpty());
        assertTrue(parser.getBuffer()==null);
    }

    @Test
    public void testShortFragmentTest() throws Exception
    {
        _in.putUnmasked((byte)0x01);
        _in.putUnmasked((byte)0x86);
        _in.sendMask();
        _in.put("Hello ".getBytes(StringUtil.__UTF8));
        _in.putUnmasked((byte)0x80);
        _in.putUnmasked((byte)0x85);
        _in.sendMask();
        _in.put("World".getBytes(StringUtil.__UTF8));

        int progress =_parser.parseNext();

        assertEquals(24,progress);
        assertEquals(0,_handler._data.size());
        assertFalse(_parser.isBufferEmpty());
        assertFalse(_parser.getBuffer()==null);

        progress =_parser.parseNext();
        _parser.returnBuffer();

        assertEquals(1,progress);
        assertEquals("Hello World",_handler._data.get(0));
        assertTrue(_parser.isBufferEmpty());
        assertTrue(_parser.getBuffer()==null);
    }

    @Test
    public void testFrameTooLarge() throws Exception
    {
        // Buffers are only 1024, so this frame is too large
        _parser.setFakeFragments(false);
        
        _in.putUnmasked((byte)0x81);
        _in.putUnmasked((byte)(0x80|0x7E));
        _in.putUnmasked((byte)(2048>>8));
        _in.putUnmasked((byte)(2048&0xff));
        _in.sendMask();

        int progress =_parser.parseNext();

        assertTrue(progress>0);
       
        assertEquals(WebSocketConnectionD08.CLOSE_BADDATA,_handler._code);
        for (int i=0;i<2048;i++)
            _in.put((byte)'a');
        progress =_parser.parseNext();

        assertEquals(2048,progress);
        assertEquals(0,_handler._data.size());
        assertEquals(0,_handler._utf8.length());
        
        _handler._code=0;
        _handler._message=null;

        _in.putUnmasked((byte)0x81);
        _in.putUnmasked((byte)0xFE);
        _in.putUnmasked((byte)(1024>>8));
        _in.putUnmasked((byte)(1024&0xff));
        _in.sendMask();
        for (int i=0;i<1024;i++)
            _in.put((byte)'a');

        progress =_parser.parseNext();
        assertTrue(progress>0);
        assertEquals(1,_handler._data.size());
        assertEquals(1024,_handler._data.get(0).length());
    }

    @Test
    public void testFakeFragement() throws Exception
    {
        // Buffers are only 1024, so this frame will be fake fragmented
        _parser.setFakeFragments(true);
        
        _in.putUnmasked((byte)0x81);
        _in.putUnmasked((byte)(0x80|0x7E));
        _in.putUnmasked((byte)(2048>>8));
        _in.putUnmasked((byte)(2048&0xff));
        _in.sendMask();
        for (int i=0;i<2048;i++)
            _in.put((byte)('a'+i%26));
        
        int progress =_parser.parseNext();
        assertTrue(progress>0);

        assertEquals(2,_handler._frames);
        assertEquals(WebSocketConnectionD08.OP_CONTINUATION,_handler._opcode);
        assertEquals(1,_handler._data.size());
        String mesg=_handler._data.remove(0);

        assertEquals(2048,mesg.length());

        for (int i=0;i<2048;i++)
            assertEquals(('a'+i%26),mesg.charAt(i));
    }

    private class Handler implements WebSocketParser.FrameHandler
    {
        Utf8StringBuilder _utf8 = new Utf8StringBuilder();
        public List<String> _data = new ArrayList<String>();
        private byte _flags;
        private byte _opcode;
        int _code;
        String _message;
        int _frames;

        public void onFrame(byte flags, byte opcode, Buffer buffer)
        {
            _frames++;
            _flags=flags;
            _opcode=opcode;
            if ((flags&0x8)==0)
                _utf8.append(buffer.array(),buffer.getIndex(),buffer.length());
            else if (_utf8.length()==0)
                _data.add(buffer.toString("utf-8"));
            else
            {
                _utf8.append(buffer.array(),buffer.getIndex(),buffer.length());
                _data.add(_utf8.toString());
                _utf8.reset();
            }
        }

        public void close(int code,String message)
        {
            _code=code;
            _message=message;
        }
    }
}
