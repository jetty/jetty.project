// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.ajp;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.View;

/**
 * 
 * 
 * 
 */
public class Ajp13RequestPacket
{
    public static boolean isEmpty(Buffer _buffer)
    {
        return _buffer.length()==0;
    }

    public static int getInt(Buffer _buffer)
    {
        return ((_buffer.get()&0xFF)<<8)|(_buffer.get()&0xFF);
    }

    public static Buffer getString(Buffer _buffer, View tok)
    {
        int len=((_buffer.peek()&0xFF)<<8)|(_buffer.peek(_buffer.getIndex()+1)&0xFF);
        if (len==0xffff)
        {
            _buffer.skip(2);
            return null;
        }
        int start=_buffer.getIndex();
        tok.update(start+2,start+len+2);
        _buffer.skip(len+3);
        return tok;
    }

    public static byte getByte(Buffer _buffer)
    {
        return _buffer.get();
    }

    public static boolean getBool(Buffer _buffer)
    {
        return _buffer.get()>0;
    }

    public static Buffer getMethod(Buffer _buffer)
    {
        return Ajp13PacketMethods.CACHE.get(_buffer.get());
    }

    public static Buffer getHeaderName(Buffer _buffer, View tok)
    {
        int len=((_buffer.peek()&0xFF)<<8)|(_buffer.peek(_buffer.getIndex()+1)&0xFF);
        if ((0xFF00&len)==0xA000)
        {
            _buffer.skip(1);
            return Ajp13RequestHeaders.CACHE.get(_buffer.get());
        }
        int start=_buffer.getIndex();
        tok.update(start+2,start+len+2);
        _buffer.skip(len+3);
        return tok;

    }

    public static Buffer get(Buffer buffer, int length)
    {
        return buffer.get(length);
    }

}
