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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;

import org.eclipse.jetty.util.StringUtil;

/* ------------------------------------------------------------------------------- */
/**
 * 
 */
public class ByteArrayBuffer extends AbstractBuffer
{
    // Set a maximum size to a write for the writeTo method, to ensure that very large content is not
    // written as a single write (which may fall foul to write timeouts if consumed slowly).
    final static int MAX_WRITE=Integer.getInteger("org.eclipse.jetty.io.ByteArrayBuffer.MAX_WRITE",128*1024);
    final protected byte[] _bytes;

    protected ByteArrayBuffer(int size, int access, boolean isVolatile)
    {
        this(new byte[size],0,0,access, isVolatile);
    }
    
    public ByteArrayBuffer(byte[] bytes)
    {
        this(bytes, 0, bytes.length, READWRITE);
    }

    public ByteArrayBuffer(byte[] bytes, int index, int length)
    {
        this(bytes, index, length, READWRITE);
    }

    public ByteArrayBuffer(byte[] bytes, int index, int length, int access)
    {
        super(READWRITE, NON_VOLATILE);
        _bytes = bytes;
        setPutIndex(index + length);
        setGetIndex(index);
        _access = access;
    }

    public ByteArrayBuffer(byte[] bytes, int index, int length, int access, boolean isVolatile)
    {
        super(READWRITE, isVolatile);
        _bytes = bytes;
        setPutIndex(index + length);
        setGetIndex(index);
        _access = access;
    }

    public ByteArrayBuffer(int size)
    {
        this(new byte[size], 0, 0, READWRITE);
        setPutIndex(0);
    }

    public ByteArrayBuffer(String value)
    {
        super(READWRITE,NON_VOLATILE);
        _bytes = StringUtil.getBytes(value);
        setGetIndex(0);
        setPutIndex(_bytes.length);
        _access=IMMUTABLE;
        _string = value;
    }
    
    public ByteArrayBuffer(String value,boolean immutable)
    {
        super(READWRITE,NON_VOLATILE);
        _bytes = StringUtil.getBytes(value);
        setGetIndex(0);
        setPutIndex(_bytes.length);
        if (immutable)
        {
            _access=IMMUTABLE;
            _string = value;
        }
    }

    public ByteArrayBuffer(String value,String encoding) throws UnsupportedEncodingException
    {
        super(READWRITE,NON_VOLATILE);
        _bytes = value.getBytes(encoding);
        setGetIndex(0);
        setPutIndex(_bytes.length);
        _access=IMMUTABLE;
        _string = value;
    }

    public byte[] array()
    {
        return _bytes;
    }

    public int capacity()
    {
        return _bytes.length;
    }
    
    @Override
    public void compact()
    {
        if (isReadOnly()) 
            throw new IllegalStateException(__READONLY);
        int s = markIndex() >= 0 ? markIndex() : getIndex();
        if (s > 0)
        {
            int length = putIndex() - s;
            if (length > 0)
            {
                System.arraycopy(_bytes, s,_bytes, 0, length);
            }
            if (markIndex() > 0) setMarkIndex(markIndex() - s);
            setGetIndex(getIndex() - s);
            setPutIndex(putIndex() - s);
        }
    }


    @Override
    public boolean equals(Object obj)
    {
        if (obj==this)
            return true;

        if (obj == null || !(obj instanceof Buffer)) 
            return false;
        
        if (obj instanceof Buffer.CaseInsensitve)
            return equalsIgnoreCase((Buffer)obj);
        

        Buffer b = (Buffer) obj;
        
        // reject different lengths
        if (b.length() != length()) 
            return false;

        // reject AbstractBuffer with different hash value
        if (_hash != 0 && obj instanceof AbstractBuffer)
        {
            AbstractBuffer ab = (AbstractBuffer) obj;
            if (ab._hash != 0 && _hash != ab._hash) 
                return false;
        }

        // Nothing for it but to do the hard grind.
        int get=getIndex();
        int bi=b.putIndex();
        for (int i = putIndex(); i-->get;)
        {
            byte b1 = _bytes[i];
            byte b2 = b.peek(--bi);
            if (b1 != b2) return false;
        }
        return true;
    }


    @Override
    public boolean equalsIgnoreCase(Buffer b)
    {
        if (b==this)
            return true;
        
        // reject different lengths
        if (b==null || b.length() != length()) 
            return false;

        // reject AbstractBuffer with different hash value
        if (_hash != 0 && b instanceof AbstractBuffer)
        {
            AbstractBuffer ab = (AbstractBuffer) b;
            if (ab._hash != 0 && _hash != ab._hash) return false;
        }

        // Nothing for it but to do the hard grind.
        int get=getIndex();
        int bi=b.putIndex();
        byte[] barray=b.array();
        if (barray==null)
        {
            for (int i = putIndex(); i-->get;)
            {
                byte b1 = _bytes[i];
                byte b2 = b.peek(--bi);
                if (b1 != b2)
                {
                    if ('a' <= b1 && b1 <= 'z') b1 = (byte) (b1 - 'a' + 'A');
                    if ('a' <= b2 && b2 <= 'z') b2 = (byte) (b2 - 'a' + 'A');
                    if (b1 != b2) return false;
                }
            }
        }
        else
        {
            for (int i = putIndex(); i-->get;)
            {
                byte b1 = _bytes[i];
                byte b2 = barray[--bi];
                if (b1 != b2)
                {
                    if ('a' <= b1 && b1 <= 'z') b1 = (byte) (b1 - 'a' + 'A');
                    if ('a' <= b2 && b2 <= 'z') b2 = (byte) (b2 - 'a' + 'A');
                    if (b1 != b2) return false;
                }
            }
        }
        return true;
    }

    @Override
    public byte get()
    {
        return _bytes[_get++];
    }

    @Override
    public int hashCode()
    {
        if (_hash == 0 || _hashGet!=_get || _hashPut!=_put) 
        {
            int get=getIndex();
            for (int i = putIndex(); i-- >get;)
            {
                byte b = _bytes[i];
                if ('a' <= b && b <= 'z') 
                    b = (byte) (b - 'a' + 'A');
                _hash = 31 * _hash + b;
            }
            if (_hash == 0) 
                _hash = -1;
            _hashGet=_get;
            _hashPut=_put;
        }
        return _hash;
    }
    
    
    public byte peek(int index)
    {
        return _bytes[index];
    }
    
    public int peek(int index, byte[] b, int offset, int length)
    {
        int l = length;
        if (index + l > capacity())
        {
            l = capacity() - index;
            if (l==0)
                return -1;
        }
        
        if (l < 0) 
            return -1;
        
        System.arraycopy(_bytes, index, b, offset, l);
        return l;
    }

    public void poke(int index, byte b)
    {
        /* 
        if (isReadOnly()) 
            throw new IllegalStateException(__READONLY);
        
        if (index < 0) 
            throw new IllegalArgumentException("index<0: " + index + "<0");
        if (index > capacity())
                throw new IllegalArgumentException("index>capacity(): " + index + ">" + capacity());
        */
        _bytes[index] = b;
    }
    
    @Override
    public int poke(int index, Buffer src)
    {
        _hash=0;
        
        /* 
        if (isReadOnly()) 
            throw new IllegalStateException(__READONLY);
        if (index < 0) 
            throw new IllegalArgumentException("index<0: " + index + "<0");
        */
        
        int length=src.length();
        if (index + length > capacity())
        {
            length=capacity()-index;
            /*
            if (length<0)
                throw new IllegalArgumentException("index>capacity(): " + index + ">" + capacity());
            */
        }
        
        byte[] src_array = src.array();
        if (src_array != null)
            System.arraycopy(src_array, src.getIndex(), _bytes, index, length);
        else 
        {
            int s=src.getIndex();
            for (int i=0;i<length;i++)
                _bytes[index++]=src.peek(s++);
        }
        
        return length;
    }
    

    @Override
    public int poke(int index, byte[] b, int offset, int length)
    {
        _hash=0;
        /*
        if (isReadOnly()) 
            throw new IllegalStateException(__READONLY);
        if (index < 0) 
            throw new IllegalArgumentException("index<0: " + index + "<0");
        */
        
        if (index + length > capacity())
        {
            length=capacity()-index;
            /* if (length<0)
                throw new IllegalArgumentException("index>capacity(): " + index + ">" + capacity());
            */
        }
        
        System.arraycopy(b, offset, _bytes, index, length);
        
        return length;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void writeTo(OutputStream out)
        throws IOException
    {
        int len=length();
        if (MAX_WRITE>0 && len>MAX_WRITE)
        {
            int off=getIndex();
            while(len>0)
            {
                int c=len>MAX_WRITE?MAX_WRITE:len;
                out.write(_bytes,off,c);
                off+=c;
                len-=c;
            }
        }
        else
            out.write(_bytes,getIndex(),len);
        if (!isImmutable())
            clear();
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public int readFrom(InputStream in,int max) throws IOException
    {
        if (max<0||max>space())
            max=space();
        int p = putIndex();
        
        int len=0, total=0, available=max;
        while (total<max) 
        {
            len=in.read(_bytes,p,available);
            if (len<0)
                break;
            else if (len>0)
            {
                p += len;
                total += len;
                available -= len;
                setPutIndex(p);
            }
            if (in.available()<=0)
                break;
        }
        if (len<0 && total==0)
            return -1;
        return total;
    }

    /* ------------------------------------------------------------ */
    @Override
    public int space()
    {
        return _bytes.length - _put;
    }

    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public static class CaseInsensitive extends ByteArrayBuffer implements Buffer.CaseInsensitve
    {
        public CaseInsensitive(String s)
        {
            super(s);
        }
        
        public CaseInsensitive(byte[] b, int o, int l, int rw)
        {
            super(b,o,l,rw);
        }

        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof Buffer && equalsIgnoreCase((Buffer)obj);
        }
        
    }
}
