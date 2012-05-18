// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;

import javax.servlet.ServletInputStream;

import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.BufferUtil;



/* ------------------------------------------------------------ */
/**
 * This class provides an HttpInput stream for the {@link HttpChannel}.
 * The input stream holds a queue of {@link ByteBuffer}s passed to it by
 * calls to {@link #content(ByteBuffer)}.  This class does not copy the buffers, 
 * but simply holds references to them, thus the caller must organise for those 
 * buffers to valid while held by this class.  To assist the caller, there are
 * extensible methods {@link #onContentQueued(ByteBuffer)}, {@link #onContentConsumed(ByteBuffer)}
 * and {@link #onAllContentConsumed()} that can be implemented so that the 
 * creator of HttpInput will know when buffers are queued and dequeued.
 */
public abstract class HttpInput extends ServletInputStream
{
    protected final byte[] _oneByte=new byte[1];

    private final ArrayQueue<ByteBuffer> _inputQ=new ArrayQueue<>();
    private ByteBuffer _content;
    private boolean _inputEOF;
    
    /* ------------------------------------------------------------ */
    public HttpInput()
    {
    }

    /* ------------------------------------------------------------ */
    public void recycle()
    {
        synchronized (_inputQ)
        {
            _inputEOF=false;
            
            if (_content!=null)
                onContentConsumed(_content);
            while ((_content=_inputQ.poll())!=null)
                onContentConsumed(_content);
            if (_content!=null)
                onAllContentConsumed();
            _content=null;
        }
    }
    
    /* ------------------------------------------------------------ */
    /*
     * @see java.io.InputStream#read()
     */
    @Override
    public int read() throws IOException
    {
        int len=read(_oneByte,0,1);
        return len<0?len:_oneByte[0];
    }

    /* ------------------------------------------------------------ */
    @Override
    public int available()
    {
        synchronized (_inputQ.lock())
        {
            ByteBuffer content=_inputQ.peekUnsafe();
            if (content==null)
                return 0;

            return content.remaining();
        }
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see java.io.InputStream#read(byte[], int, int)
     */
    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        synchronized (_inputQ.lock())
        {
            ByteBuffer content=null;            
            while(content==null)
            {
                content=_inputQ.peekUnsafe();
                while (content!=null && !content.hasRemaining())
                {
                    _inputQ.pollUnsafe();
                    content=_inputQ.peekUnsafe();
                }

                if (content==null)
                {
                    // check for EOF
                    if (_inputEOF)
                    {
                        onEof();
                        return -1;
                    }
                    
                    blockForContent();
                }
            }
            
            int l=Math.min(len,content.remaining());
            content.get(b,off,l);
            return l;
        }
    }
    
    protected void blockForContent() throws IOException
    {
        synchronized (_inputQ.lock())
        {
            while(_inputQ.isEmpty())
            {
                try
                {
                    _inputQ.lock().wait();
                }
                catch (InterruptedException e)
                {
                    throw (IOException)new InterruptedIOException().initCause(e);
                }
            }
        }
    }
    
    protected void onContentQueued(ByteBuffer ref)
    {
        _inputQ.lock().notify();
    }
    
    protected void onContentConsumed(ByteBuffer ref)
    {    
    }
    
    protected void onAllContentConsumed()
    {   
    }
    
    protected void onEof()
    {   
    }
    
    public boolean content(ByteBuffer ref)
    {
        synchronized (_inputQ.lock())
        {
            _inputQ.add(ref);
            onContentQueued(ref);
        }              
        return true;
    }

    public void shutdownInput()
    {
        synchronized (_inputQ.lock())
        {                
            _inputEOF=true;
        }
    }
}
