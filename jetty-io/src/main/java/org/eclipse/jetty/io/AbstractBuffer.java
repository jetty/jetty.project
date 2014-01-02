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
import java.nio.charset.Charset;

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * 
 *  
 */
public abstract class AbstractBuffer implements Buffer
{
    private static final Logger LOG = Log.getLogger(AbstractBuffer.class);

    private final static boolean __boundsChecking = Boolean.getBoolean("org.eclipse.jetty.io.AbstractBuffer.boundsChecking");
    
    protected final static String 
    __IMMUTABLE = "IMMUTABLE", 
    __READONLY = "READONLY",
    __READWRITE = "READWRITE", 
    __VOLATILE = "VOLATILE";
    
    protected int _access;
    protected boolean _volatile;

    protected int _get;
    protected int _put;
    protected int _hash;
    protected int _hashGet;
    protected int _hashPut;
    protected int _mark;
    protected String _string;
    protected View _view;

    /**
     * Constructor for BufferView
     * 
     * @param access 0==IMMUTABLE, 1==READONLY, 2==READWRITE
     */
    public AbstractBuffer(int access, boolean isVolatile)
    {
        if (access == IMMUTABLE && isVolatile)
                throw new IllegalArgumentException("IMMUTABLE && VOLATILE");
        setMarkIndex(-1);
        _access = access;
        _volatile = isVolatile;
    }

    /*
     * @see org.eclipse.io.Buffer#toArray()
     */
    public byte[] asArray()
    {
        byte[] bytes = new byte[length()];
        byte[] array = array();
        if (array != null)
            System.arraycopy(array, getIndex(), bytes, 0, bytes.length);
        else
            peek(getIndex(), bytes, 0, length());
        return bytes;
    }

    public ByteArrayBuffer duplicate(int access)
    {
        Buffer b=this.buffer();
        if (this instanceof Buffer.CaseInsensitve || b instanceof Buffer.CaseInsensitve)
            return new ByteArrayBuffer.CaseInsensitive(asArray(), 0, length(),access);
        else
            return new ByteArrayBuffer(asArray(), 0, length(), access);
    }
    
    /*
     * @see org.eclipse.io.Buffer#asNonVolatile()
     */
    public Buffer asNonVolatileBuffer()
    {
        if (!isVolatile()) return this;
        return duplicate(_access);
    }

    public Buffer asImmutableBuffer()
    {
        if (isImmutable()) return this;
        return duplicate(IMMUTABLE);
    }

    /*
     * @see org.eclipse.util.Buffer#asReadOnlyBuffer()
     */
    public Buffer asReadOnlyBuffer()
    {
        if (isReadOnly()) return this;
        return new View(this, markIndex(), getIndex(), putIndex(), READONLY);
    }

    public Buffer asMutableBuffer()
    {
        if (!isImmutable()) return this;
        
        Buffer b=this.buffer();
        if (b.isReadOnly())
        {
            return duplicate(READWRITE);
        }
        return new View(b, markIndex(), getIndex(), putIndex(), _access);
    }

    public Buffer buffer()
    {
        return this;
    }

    public void clear()
    {
        setMarkIndex(-1);
        setGetIndex(0);
        setPutIndex(0);
    }

    public void compact()
    {
        if (isReadOnly()) throw new IllegalStateException(__READONLY);
        int s = markIndex() >= 0 ? markIndex() : getIndex();
        if (s > 0)
        {
            byte array[] = array();
            int length = putIndex() - s;
            if (length > 0)
            {
                if (array != null)
                    System.arraycopy(array(), s, array(), 0, length);
                else
                    poke(0, peek(s, length));
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
        
        // reject non buffers;
        if (obj == null || !(obj instanceof Buffer)) return false;
        Buffer b = (Buffer) obj;

        if (this instanceof Buffer.CaseInsensitve ||  b instanceof Buffer.CaseInsensitve)
            return equalsIgnoreCase(b);
        
        // reject different lengths
        if (b.length() != length()) return false;

        // reject AbstractBuffer with different hash value
        if (_hash != 0 && obj instanceof AbstractBuffer)
        {
            AbstractBuffer ab = (AbstractBuffer) obj;
            if (ab._hash != 0 && _hash != ab._hash) return false;
        }

        // Nothing for it but to do the hard grind.
        int get=getIndex();
        int bi=b.putIndex();
        for (int i = putIndex(); i-->get;)
        {
            byte b1 = peek(i);
            byte b2 = b.peek(--bi);
            if (b1 != b2) return false;
        }
        return true;
    }

    public boolean equalsIgnoreCase(Buffer b)
    {
        if (b==this)
            return true;
        
        // reject different lengths
        if (b.length() != length()) return false;

        // reject AbstractBuffer with different hash value
        if (_hash != 0 && b instanceof AbstractBuffer)
        {
            AbstractBuffer ab = (AbstractBuffer) b;
            if (ab._hash != 0 && _hash != ab._hash) return false;
        }

        // Nothing for it but to do the hard grind.
        int get=getIndex();
        int bi=b.putIndex();
        
        byte[] array = array();
        byte[] barray= b.array();
        if (array!=null && barray!=null)
        {
            for (int i = putIndex(); i-->get;)
            {
                byte b1 = array[i];
                byte b2 = barray[--bi];
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
                byte b1 = peek(i);
                byte b2 = b.peek(--bi);
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

    public byte get()
    {
        return peek(_get++);
    }

    public int get(byte[] b, int offset, int length)
    {
        int gi = getIndex();
        int l=length();
        if (l==0)
            return -1;
        
        if (length>l)
            length=l;
        
        length = peek(gi, b, offset, length);
        if (length>0)
            setGetIndex(gi + length);
        return length;
    }

    public Buffer get(int length)
    {
        int gi = getIndex();
        Buffer view = peek(gi, length);
        setGetIndex(gi + length);
        return view;
    }

    public final int getIndex()
    {
        return _get;
    }

    public boolean hasContent()
    {
        return _put > _get;
    }
    
    @Override
    public int hashCode()
    {
        if (_hash == 0 || _hashGet!=_get || _hashPut!=_put) 
        {
            int get=getIndex();
            byte[] array = array();
            if (array==null)
            {
                for (int i = putIndex(); i-- >get;)
                {
                    byte b = peek(i);
                    if ('a' <= b && b <= 'z') 
                        b = (byte) (b - 'a' + 'A');
                    _hash = 31 * _hash + b;
                }
            }
            else
            {
                for (int i = putIndex(); i-- >get;)
                {
                    byte b = array[i];
                    if ('a' <= b && b <= 'z') 
                        b = (byte) (b - 'a' + 'A');
                    _hash = 31 * _hash + b;
                }
            }
            if (_hash == 0) 
                _hash = -1;
            _hashGet=_get;
            _hashPut=_put;
            
        }
        return _hash;
    }

    public boolean isImmutable()
    {
        return _access <= IMMUTABLE;
    }

    public boolean isReadOnly()
    {
        return _access <= READONLY;
    }

    public boolean isVolatile()
    {
        return _volatile;
    }

    public int length()
    {
        return _put - _get;
    }

    public void mark()
    {
        setMarkIndex(_get - 1);
    }

    public void mark(int offset)
    {
        setMarkIndex(_get + offset);
    }

    public int markIndex()
    {
        return _mark;
    }

    public byte peek()
    {
        return peek(_get);
    }

    public Buffer peek(int index, int length)
    {
        if (_view == null)
        {
            _view = new View(this, -1, index, index + length, isReadOnly() ? READONLY : READWRITE);
        }
        else
        {
            _view.update(this.buffer());
            _view.setMarkIndex(-1);
            _view.setGetIndex(0);
            _view.setPutIndex(index + length);
            _view.setGetIndex(index);
            
        }
        return _view;
    }

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
        byte[] dst_array = array();
        if (src_array != null && dst_array != null)
            System.arraycopy(src_array, src.getIndex(), dst_array, index, length);
        else if (src_array != null)
        {
            int s=src.getIndex();
            for (int i=0;i<length;i++)
                poke(index++,src_array[s++]);
        }
        else if (dst_array != null)
        {
            int s=src.getIndex();
            for (int i=0;i<length;i++)
                dst_array[index++]=src.peek(s++);
        }
        else
        {
            int s=src.getIndex();
            for (int i=0;i<length;i++)
                poke(index++,src.peek(s++));
        }
        
        return length;
    }
    

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
        
        byte[] dst_array = array();
        if (dst_array != null)
            System.arraycopy(b, offset, dst_array, index, length);
        else
        {
            int s=offset;
            for (int i=0;i<length;i++)
                poke(index++,b[s++]);
        }
        return length;
    }

    public int put(Buffer src)
    {
        int pi = putIndex();
        int l=poke(pi, src);
        setPutIndex(pi + l);
        return l;
    }

    public void put(byte b)
    {
        int pi = putIndex();
        poke(pi, b);
        setPutIndex(pi + 1);
    }

    public int put(byte[] b, int offset, int length)
    {
        int pi = putIndex();
        int l = poke(pi, b, offset, length);
        setPutIndex(pi + l);
        return l;
    }
    
    public int put(byte[] b)
    {
        int pi = putIndex();
        int l = poke(pi, b, 0, b.length);
        setPutIndex(pi + l);
        return l;
    }

    public final int putIndex()
    {
        return _put;
    }

    public void reset()
    {
        if (markIndex() >= 0) setGetIndex(markIndex());
    }

    public void rewind()
    {
        setGetIndex(0);
        setMarkIndex(-1);
    }

    public void setGetIndex(int getIndex)
    {
        /* bounds checking
        if (isImmutable()) 
            throw new IllegalStateException(__IMMUTABLE);
        if (getIndex < 0)
            throw new IllegalArgumentException("getIndex<0: " + getIndex + "<0");
        if (getIndex > putIndex())
            throw new IllegalArgumentException("getIndex>putIndex: " + getIndex + ">" + putIndex());
         */
        _get = getIndex;
        _hash=0;
    }

    public void setMarkIndex(int index)
    {
        /*
        if (index>=0 && isImmutable()) 
            throw new IllegalStateException(__IMMUTABLE);
        */
        _mark = index;
    }

    public void setPutIndex(int putIndex)
    {
        /* bounds checking
        if (isImmutable()) 
            throw new IllegalStateException(__IMMUTABLE);
        if (putIndex > capacity())
                throw new IllegalArgumentException("putIndex>capacity: " + putIndex + ">" + capacity());
        if (getIndex() > putIndex)
                throw new IllegalArgumentException("getIndex>putIndex: " + getIndex() + ">" + putIndex);
         */
        _put = putIndex;
        _hash=0;
    }

    public int skip(int n)
    {
        if (length() < n) n = length();
        setGetIndex(getIndex() + n);
        return n;
    }

    public Buffer slice()
    {
        return peek(getIndex(), length());
    }

    public Buffer sliceFromMark()
    {
        return sliceFromMark(getIndex() - markIndex() - 1);
    }

    public Buffer sliceFromMark(int length)
    {
        if (markIndex() < 0) return null;
        Buffer view = peek(markIndex(), length);
        setMarkIndex(-1);
        return view;
    }

    public int space()
    {
        return capacity() - _put;
    }

    public String toDetailString()
    {
        StringBuilder buf = new StringBuilder();
        buf.append("[");
        buf.append(super.hashCode());
        buf.append(",");
        buf.append(this.buffer().hashCode());
        buf.append(",m=");
        buf.append(markIndex());
        buf.append(",g=");
        buf.append(getIndex());
        buf.append(",p=");
        buf.append(putIndex());
        buf.append(",c=");
        buf.append(capacity());
        buf.append("]={");
        if (markIndex() >= 0)
        {
            for (int i = markIndex(); i < getIndex(); i++)
            {
                byte b =  peek(i);
                TypeUtil.toHex(b,buf);
            }
            buf.append("}{");
        }
        int count = 0;
        for (int i = getIndex(); i < putIndex(); i++)
        {
            byte b =  peek(i);
            TypeUtil.toHex(b,buf);
            if (count++ == 50)
            {
                if (putIndex() - i > 20)
                {
                    buf.append(" ... ");
                    i = putIndex() - 20;
                }
            }
        }
        buf.append('}');
        return buf.toString();
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        if (isImmutable())
        {
            if (_string == null) 
                _string = new String(asArray(), 0, length());
            return _string;
        }
        return new String(asArray(), 0, length());
    }

    /* ------------------------------------------------------------ */
    public String toString(String charset)
    {
        try
        {
            byte[] bytes=array();
            if (bytes!=null)
                return new String(bytes,getIndex(),length(),charset);
            return new String(asArray(), 0, length(),charset);
            
        }
        catch(Exception e)
        {
            LOG.warn(e);
            return new String(asArray(), 0, length());
        }
    }

    /* ------------------------------------------------------------ */
    public String toString(Charset charset)
    {
        try
        {
            byte[] bytes=array();
            if (bytes!=null)
                return new String(bytes,getIndex(),length(),charset);
            return new String(asArray(), 0, length(),charset);
        }
        catch(Exception e)
        {
            LOG.warn(e);
            return new String(asArray(), 0, length());
        }
    }

    /* ------------------------------------------------------------ */
    public String toDebugString()
    {
        return getClass()+"@"+super.hashCode();
    }

    /* ------------------------------------------------------------ */
    public void writeTo(OutputStream out)
    	throws IOException
    {
        byte[] array = array();
        
        if (array!=null)
        {
            out.write(array,getIndex(),length());
        }
        else
        {
            int len = this.length();
            byte[] buf=new byte[len>1024?1024:len];
            int offset=_get;
            while (len>0)
            {
                int l=peek(offset,buf,0,len>buf.length?buf.length:len);
                out.write(buf,0,l);
                offset+=l;
                len-=l;
            }
        } 
        clear();
    }
    
    /* ------------------------------------------------------------ */
    public int readFrom(InputStream in,int max) throws IOException
    {
        byte[] array = array();
        int s=space();
        if (s>max)
            s=max;

        if (array!=null)
        {
            int l=in.read(array,_put,s);
            if (l>0)
                _put+=l;
            return l;
        }
        else
        {
            byte[] buf=new byte[s>1024?1024:s];
            int total=0;
            while (s>0)
            {
                int l=in.read(buf,0,buf.length);
                if (l<0)
                    return total>0?total:-1;
                int p=put(buf,0,l);
                assert l==p;
                s-=l;
            }
            return total; 
        }
    }
}
