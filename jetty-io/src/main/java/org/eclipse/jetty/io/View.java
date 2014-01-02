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

/**
 * A View on another buffer.  Allows operations that do not change the _content or
 * indexes of the backing buffer.
 * 
 * 
 * 
 */
public class View extends AbstractBuffer
{
    Buffer _buffer;

    /**
     * @param buffer The <code>Buffer</code> on which we are presenting a <code>View</code>.
     * @param mark The initial value of the {@link Buffer#markIndex mark index}
     * @param get The initial value of the {@link Buffer#getIndex get index}
     * @param put The initial value of the {@link Buffer#putIndex put index}
     * @param access The access level - one of the constants from {@link Buffer}.
     */
    public View(Buffer buffer, int mark, int get, int put,int access)
    {
        super(READWRITE,!buffer.isImmutable());
        _buffer=buffer.buffer();
        setPutIndex(put);
        setGetIndex(get);
        setMarkIndex(mark);
        _access=access;
    }
    
    public View(Buffer buffer)
    {
        super(READWRITE,!buffer.isImmutable());
        _buffer=buffer.buffer();
        setPutIndex(buffer.putIndex());
        setGetIndex(buffer.getIndex());
        setMarkIndex(buffer.markIndex());
        _access=buffer.isReadOnly()?READONLY:READWRITE;
    }

    public View()
    {
        super(READWRITE,true);
    }
    
    /**
     * Update view to buffer
     */
    public void update(Buffer buffer)
    {
        _access=READWRITE;
        _buffer=buffer.buffer();
        setGetIndex(0);
        setPutIndex(buffer.putIndex());
        setGetIndex(buffer.getIndex());
        setMarkIndex(buffer.markIndex());
        _access=buffer.isReadOnly()?READONLY:READWRITE;
    }

    public void update(int get, int put)
    {
        int a=_access;
        _access=READWRITE;
        setGetIndex(0);
        setPutIndex(put);
        setGetIndex(get);
        setMarkIndex(-1);
        _access=a;
    }

    /**
     * @return The {@link Buffer#array()} from the underlying buffer.
     */
    public byte[] array()
    {
        return _buffer.array();
    }

    /**
     * @return The {@link Buffer#buffer()} from the underlying buffer.
     */
    @Override
    public Buffer buffer()
    {
        return _buffer.buffer();
    }

    /**
     * @return The {@link Buffer#capacity} of the underlying buffer.
     */
    public int capacity()
    {
        return _buffer.capacity();
    }

    /**
     *  
     */
    @Override
    public void clear()
    {
        setMarkIndex(-1);
        setGetIndex(0);
        setPutIndex(_buffer.getIndex());
        setGetIndex(_buffer.getIndex());
    }

    /**
     *  
     */
    @Override
    public void compact()
    {
        // TODO
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj)
    {
        return  this==obj ||((obj instanceof Buffer)&& obj.equals(this)) || super.equals(obj);
    }

    /**
     * @return Whether the underlying buffer is {@link Buffer#isReadOnly read only}
     */
    @Override
    public boolean isReadOnly()
    {
        return _buffer.isReadOnly();
    }

    /**
     * @return Whether the underlying buffer is {@link Buffer#isVolatile volatile}
     */
    @Override
    public boolean isVolatile()
    {
        return true;
    }

    /**
     * @return The result of calling {@link Buffer#peek(int)} on the underlying buffer
     */
    public byte peek(int index)
    {
        return _buffer.peek(index);
    }

    /**
     * @return The result of calling {@link Buffer#peek(int, byte[], int, int)} on the underlying buffer
     */
    public int peek(int index, byte[] b, int offset, int length)
    {
        return _buffer.peek(index,b,offset,length);
    }

    /**
     * @return The result of calling {@link Buffer#peek(int, int)} on the underlying buffer
     */
    @Override
    public Buffer peek(int index, int length)
    {
        return _buffer.peek(index, length);
    }
    
    /**
     * @param index
     * @param src
     */
    @Override
    public int poke(int index, Buffer src)
    {
        return _buffer.poke(index,src); 
    }

    /**
     * @param index
     * @param b
     */
    public void poke(int index, byte b)
    {
        _buffer.poke(index,b);
    }

    /**
     * @param index
     * @param b
     * @param offset
     * @param length
     */
    @Override
    public int poke(int index, byte[] b, int offset, int length)
    {
        return _buffer.poke(index,b,offset,length);
    }
    
    @Override
    public String toString()
    {
        if (_buffer==null)
            return "INVALID";
        return super.toString();
    }
    
    public static class CaseInsensitive extends View implements Buffer.CaseInsensitve
    {
        public CaseInsensitive()
        {
            super();
        }

        public CaseInsensitive(Buffer buffer, int mark, int get, int put, int access)
        {
            super(buffer,mark,get,put,access);
        }

        public CaseInsensitive(Buffer buffer)
        {
            super(buffer);
        }
        
        @Override
        public boolean equals(Object obj)
        {
            return  this==obj ||((obj instanceof Buffer)&&((Buffer)obj).equalsIgnoreCase(this)) || super.equals(obj);
        }
    }
}
