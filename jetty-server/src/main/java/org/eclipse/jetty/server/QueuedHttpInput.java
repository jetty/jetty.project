//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import javax.servlet.ServletInputStream;

import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>{@link QueuedHttpInput} provides an implementation of {@link ServletInputStream} for {@link HttpChannel}.</p>
 * <p>{@link QueuedHttpInput} holds a queue of items passed to it by calls to {@link #content(Object)}.</p>
 * <p>{@link QueuedHttpInput} stores the items directly; if the items contain byte buffers, it does not copy them
 * but simply holds references to the item, thus the caller must organize for those buffers to valid while
 * held by this class.</p>
 * <p>To assist the caller, subclasses may override methods {@link #onAsyncRead()},
 * {@link #onContentConsumed(Object)} and {@link #onAllContentConsumed()} that can be implemented so that the
 * caller will know when buffers are queued and consumed.</p>
 */
public abstract class QueuedHttpInput<T> extends HttpInput<T>
{
    private final static Logger LOG = Log.getLogger(QueuedHttpInput.class);

    private final ArrayQueue<T> _inputQ = new ArrayQueue<>(lock());
    
    public QueuedHttpInput()
    {}

    public void recycle()
    {
        synchronized (lock())
        {
            T item = _inputQ.peekUnsafe();
            while (item != null)
            {
                _inputQ.pollUnsafe();
                onContentConsumed(item);

                item = _inputQ.peekUnsafe();
                if (item == null)
                    onAllContentConsumed();
            }
            super.recycle();
        }
    }

    @Override
    protected T nextContent()
    {
        T item = _inputQ.peekUnsafe();

        // Skip empty items at the head of the queue
        while (item != null && remaining(item) == 0)
        {
            _inputQ.pollUnsafe();
            onContentConsumed(item);
            LOG.debug("{} consumed {}", this, item);
            item = _inputQ.peekUnsafe();

            // If that was the last item then notify
            if (item==null)
                onAllContentConsumed();
        }
        return item;
    }
    
    protected abstract void onContentConsumed(T item);

    protected void blockForContent() throws IOException
    {
        synchronized (lock())
        {
            while (_inputQ.isEmpty() && !_state.isEOF())
            {
                try
                {
                    LOG.debug("{} waiting for content", this);
                    lock().wait();
                }
                catch (InterruptedException e)
                {
                    throw (IOException)new InterruptedIOException().initCause(e);
                }
            }
        }
    }


    /* ------------------------------------------------------------ */
    /** Called by this HttpInput to signal all available content has been consumed
     */
    protected void onAllContentConsumed()
    {
    }

    /* ------------------------------------------------------------ */
    /** Add some content to the input stream
     * @param item
     */
    public void content(T item)
    {
        // The buffer is not copied here.  This relies on the caller not recycling the buffer
        // until the it is consumed.  The onContentConsumed and onAllContentConsumed() callbacks are 
        // the signals to the caller that the buffers can be recycled.
        
        synchronized (lock())
        {
            boolean empty=_inputQ.isEmpty();
            
            _inputQ.add(item);

            if (empty)
            {
                if (!onAsyncRead())
                    lock().notify();
            }
            
            LOG.debug("{} queued {}", this, item);
        }
    }
    

    public void earlyEOF()
    {
        synchronized (lock())
        {
            super.earlyEOF();
            lock().notify();
        }
    }
    
    public void messageComplete()
    {
        synchronized (lock())
        {
            super.messageComplete();
            lock().notify();
        }
    }

}
