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

package org.eclipse.jetty.io.nio;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

import org.eclipse.jetty.io.AbstractBuffer;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------------------------- */
/** 
 * 
 * 
 */
public class DirectNIOBuffer extends AbstractBuffer implements NIOBuffer
{ 	
    private static final Logger LOG = Log.getLogger(DirectNIOBuffer.class);

    protected final ByteBuffer _buf;
    private ReadableByteChannel _in;
    private InputStream _inStream;
    private WritableByteChannel _out;
    private OutputStream _outStream;

    public DirectNIOBuffer(int size)
    {
        super(READWRITE,NON_VOLATILE);
        _buf = ByteBuffer.allocateDirect(size);
        _buf.position(0);
        _buf.limit(_buf.capacity());
    }
    
    public DirectNIOBuffer(ByteBuffer buffer,boolean immutable)
    {
        super(immutable?IMMUTABLE:READWRITE,NON_VOLATILE);
        if (!buffer.isDirect())
            throw new IllegalArgumentException();
        _buf = buffer;
        setGetIndex(buffer.position());
        setPutIndex(buffer.limit());
    }

    /**
     * @param file
     */
    public DirectNIOBuffer(File file) throws IOException
    {
        super(READONLY,NON_VOLATILE);
        FileInputStream fis = null;
        FileChannel fc = null;
        try
        {
            fis = new FileInputStream(file);
            fc = fis.getChannel();
            _buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, file.length());
            setGetIndex(0);
            setPutIndex((int)file.length());
            _access=IMMUTABLE;
        }
        finally
        {
            if (fc != null) try {fc.close();} catch (IOException e){LOG.ignore(e);}
            IO.close(fis);
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isDirect()
    {
        return true;
    }

    /* ------------------------------------------------------------ */
    public byte[] array()
    {
        return null;
    }

    /* ------------------------------------------------------------ */
    public int capacity()
    {
        return _buf.capacity();
    }

    /* ------------------------------------------------------------ */
    public byte peek(int position)
    {
        return _buf.get(position);
    }

    public int peek(int index, byte[] b, int offset, int length)
    {
        int l = length;
        if (index+l > capacity())
        {
            l=capacity()-index;
            if (l==0)
                return -1;
        }
        
        if (l < 0) 
            return -1;
        try
        {
            _buf.position(index);
            _buf.get(b,offset,l);
        }
        finally
        {
            _buf.position(0);
        }
        
        return l;
    }

    public void poke(int index, byte b)
    {
        if (isReadOnly()) throw new IllegalStateException(__READONLY);
        if (index < 0) throw new IllegalArgumentException("index<0: " + index + "<0");
        if (index > capacity())
                throw new IllegalArgumentException("index>capacity(): " + index + ">" + capacity());
        _buf.put(index,b);
    }

    @Override
    public int poke(int index, Buffer src)
    {
        if (isReadOnly()) throw new IllegalStateException(__READONLY);

        byte[] array=src.array();
        if (array!=null)
        {
            return poke(index,array,src.getIndex(),src.length());
        }
        else
        {
            Buffer src_buf=src.buffer();
            if (src_buf instanceof DirectNIOBuffer)
            {
                ByteBuffer src_bytebuf = ((DirectNIOBuffer)src_buf)._buf;
                if (src_bytebuf==_buf)
                    src_bytebuf=_buf.duplicate();
                try
                {   
                    _buf.position(index);
                    int space = _buf.remaining();
                    
                    int length=src.length();
                    if (length>space)    
                        length=space;
                    
                    src_bytebuf.position(src.getIndex());
                    src_bytebuf.limit(src.getIndex()+length);
                    
                    _buf.put(src_bytebuf);
                    return length;
                }
                finally
                {
                    _buf.position(0);
                    src_bytebuf.limit(src_bytebuf.capacity());
                    src_bytebuf.position(0);
                }
            }
            else
                return super.poke(index,src);
        }
    }
    
    @Override
    public int poke(int index, byte[] b, int offset, int length)
    {
        if (isReadOnly()) throw new IllegalStateException(__READONLY);

        if (index < 0) throw new IllegalArgumentException("index<0: " + index + "<0");

        if (index + length > capacity())
        {
            length=capacity()-index;
            if (length<0)
                throw new IllegalArgumentException("index>capacity(): " + index + ">" + capacity());
        }

        try
        {
            _buf.position(index);
            
            int space=_buf.remaining();
            
            if (length>space)
                length=space;
            if (length>0)
                _buf.put(b,offset,length);
            return length;
        }
        finally
        {
            _buf.position(0);
        }
    }
    
    /* ------------------------------------------------------------ */
    public ByteBuffer getByteBuffer()
    {
        return _buf;
    }

    /* ------------------------------------------------------------ */
    @Override
    public int readFrom(InputStream in, int max) throws IOException
    {
        if (_in==null || !_in.isOpen() || in!=_inStream)
        {
            _in=Channels.newChannel(in);
            _inStream=in;
        }

        if (max<0 || max>space())
            max=space();
        int p = putIndex();
        
        try
        {
            int len=0, total=0, available=max;
            int loop=0;
            while (total<max) 
            {
                _buf.position(p);
                _buf.limit(p+available);
                len=_in.read(_buf);
                if (len<0)
                {
                    _in=null;
                    _inStream=in;
                    break;
                }
                else if (len>0)
                {
                    p += len;
                    total += len;
                    available -= len;
                    setPutIndex(p);
                    loop=0;
                }
                else if (loop++>1)
                    break;
                if (in.available()<=0)
                    break;
            }
            if (len<0 && total==0)
                return -1;
            return total;
            
        }
        catch(IOException e)
        {
            _in=null;
            _inStream=in;
            throw e;
        }
        finally
        {
            if (_in!=null && !_in.isOpen())
            {
                _in=null;
                _inStream=in;
            }
            _buf.position(0);
            _buf.limit(_buf.capacity());
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void writeTo(OutputStream out) throws IOException
    {
        if (_out==null || !_out.isOpen() || out!=_outStream)
        {
            _out=Channels.newChannel(out);
            _outStream=out;
        }

        synchronized (_buf)
        {
            try
            {
                int loop=0;
                while(hasContent() && _out.isOpen())
                {
                    _buf.position(getIndex());
                    _buf.limit(putIndex());
                    int len=_out.write(_buf);
                    if (len<0)
                        break;
                    else if (len>0)
                    {
                        skip(len);
                        loop=0;
                    }
                    else if (loop++>1)
                        break;
                }

            }
            catch(IOException e)
            {
                _out=null;
                _outStream=null;
                throw e;
            }
            finally
            {
                if (_out!=null && !_out.isOpen())
                {
                    _out=null;
                    _outStream=null;
                }
                _buf.position(0);
                _buf.limit(_buf.capacity());
            }
        }
    }

    
    
}
