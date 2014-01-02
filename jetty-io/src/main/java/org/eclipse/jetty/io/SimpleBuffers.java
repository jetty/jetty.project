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

package org.eclipse.jetty.io;

/* ------------------------------------------------------------ */
/** SimpleBuffers.
 * Simple implementation of Buffers holder.
 * 
 *
 */
public class SimpleBuffers implements Buffers
{   
    final Buffer _header;
    final Buffer _buffer;
    boolean _headerOut;
    boolean _bufferOut;
    
    /* ------------------------------------------------------------ */
    /**
     * 
     */
    public SimpleBuffers(Buffer header, Buffer buffer)
    {
        _header=header;
        _buffer=buffer;
    }

    /* ------------------------------------------------------------ */
    public Buffer getBuffer()
    {
        synchronized(this)
        {
            if (_buffer!=null && !_bufferOut)
            {
                _bufferOut=true;
                return _buffer;
            }
            
            if (_buffer!=null && _header!=null && _header.capacity()==_buffer.capacity() && !_headerOut)
            {
                _headerOut=true;
                return _header;
            }
            
            if (_buffer!=null)
                return new ByteArrayBuffer(_buffer.capacity());
            return new ByteArrayBuffer(4096);
        }
    }

    /* ------------------------------------------------------------ */
    public Buffer getHeader()
    {
        synchronized(this)
        {
            if (_header!=null && !_headerOut)
            {
                _headerOut=true;
                return _header;
            }
            
            if (_buffer!=null && _header!=null && _header.capacity()==_buffer.capacity() && !_bufferOut)
            {
                _bufferOut=true;
                return _buffer;
            }
            
            if (_header!=null)
                return new ByteArrayBuffer(_header.capacity());
            return new ByteArrayBuffer(4096);
        }
    }

    /* ------------------------------------------------------------ */
    public Buffer getBuffer(int size)
    {
        synchronized(this)
        {
            if (_header!=null && _header.capacity()==size)
                return getHeader();
            if (_buffer!=null && _buffer.capacity()==size)
                return getBuffer();
            return null;            
        }
    }

    /* ------------------------------------------------------------ */
    public void returnBuffer(Buffer buffer)
    {
        synchronized(this)
        {
            buffer.clear();
            if (buffer==_header)
                _headerOut=false;
            if (buffer==_buffer)
                _bufferOut=false;
        }
    }


}
