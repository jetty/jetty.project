//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import javax.servlet.ServletInputStream;

import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.ArrayQueue;



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
public class HttpInput extends ServletInputStream
{
    private final byte[] _oneByte=new byte[1];
    private final ArrayQueue<ByteBuffer> _inputQ=new ArrayQueue<>();
    private boolean _earlyEOF;
    private boolean _inputEOF;

    /* ------------------------------------------------------------ */
    public HttpInput()
    {
    }

    /* ------------------------------------------------------------ */
    public Object lock()
    {
        return _inputQ.lock();
    }

    /* ------------------------------------------------------------ */
    public void recycle()
    {
        synchronized (lock())
        {
            ByteBuffer content=_inputQ.peekUnsafe();;
            while(content!=null)
            {
                content.clear();
                _inputQ.pollUnsafe();
                onContentConsumed(content);
                
                content=_inputQ.peekUnsafe();
                if (content==null)
                    onAllContentConsumed();
            }
            
            _inputEOF=false;
            _earlyEOF=false;

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
        synchronized (lock())
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
        synchronized (lock())
        {
            ByteBuffer content=null;
            while(content==null)
            {
                content=_inputQ.peekUnsafe();
                while (content!=null && !content.hasRemaining())
                {
                    _inputQ.pollUnsafe();
                    onContentConsumed(content);
                    content=_inputQ.peekUnsafe();
                }

                if (content==null)
                {
                    onAllContentConsumed();
                    
                    if (_earlyEOF)
                        throw new EofException();
                    
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
        synchronized (lock())
        {
            while (_inputQ.isEmpty())
            {
                try
                {
                    lock().wait();
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
        lock().notify();
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
        synchronized (lock())
        {
            // The buffer is not copied here.  This relies on the caller not recycling the buffer
            // until the it is consumed.  The onAllContentConsumed() callback is the signal to the 
            // caller that the buffers can be recycled.
            _inputQ.add(ref);
            onContentQueued(ref);
        }
        return true;
    }

    public void earlyEOF()
    {
        synchronized (lock())
        {
            _earlyEOF=true;
        }
    }

    public void shutdown()
    {
        synchronized (lock())
        {
            _inputEOF=true;
        }
    }

    public boolean isShutdown()
    {
        synchronized (lock())
        {
            return _inputEOF;
        }
    }

    public void consumeAll()
    {
        synchronized (lock())
        {
            while(!_inputEOF&&!_earlyEOF)
            {
                ByteBuffer content=_inputQ.peekUnsafe();
                while(content!=null)
                {
                    content.clear();
                    _inputQ.pollUnsafe();
                    onContentConsumed(content);

                    content=_inputQ.peekUnsafe();
                    if (content==null)
                        onAllContentConsumed();
                }
                
                try
                {
                    blockForContent();
                }
                catch(IOException e)
                {
                    e.printStackTrace();
                    throw new RuntimeIOException(e);
                }
            }
        }
    }
}
