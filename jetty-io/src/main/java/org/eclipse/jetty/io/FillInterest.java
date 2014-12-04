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
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadPendingException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/** 
 * A Utility class to help implement {@link EndPoint#fillInterested(Callback)}
 * by keeping state and calling the context and callback objects.
 * 
 */
public abstract class FillInterest
{
    private final static Logger LOG = Log.getLogger(FillInterest.class);
    private final AtomicReference<Callback> _interested = new AtomicReference<>(null);

    /* ------------------------------------------------------------ */
    protected FillInterest()
    {
    }

    /* ------------------------------------------------------------ */
    /** Call to register interest in a callback when a read is possible.
     * The callback will be called either immediately if {@link #needsFill()} 
     * returns true or eventually once {@link #fillable()} is called.
     * @param callback
     * @throws ReadPendingException
     */
    public <C> void register(Callback callback) throws ReadPendingException
    {
        if (callback==null)
            throw new IllegalArgumentException();
        
        if (!_interested.compareAndSet(null,callback))
        {
            LOG.warn("Read pending for "+_interested.get()+" prevented "+callback);
            throw new ReadPendingException();
        }
        try
        {
            if (needsFill())
                fillable();
        }
        catch(IOException e)
        {
            onFail(e);
        }
    }

    /* ------------------------------------------------------------ */
    /** Call to signal that a read is now possible.
     */
    public void fillable()
    {
        Callback callback=_interested.get();
        if (callback!=null && _interested.compareAndSet(callback,null))
            callback.succeeded();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if a read callback has been registered
     */
    public boolean isInterested()
    {
        return _interested.get()!=null;
    }
    
    /* ------------------------------------------------------------ */
    /** Call to signal a failure to a registered interest
     * @return true if the cause was passed to a {@link Callback} instance
     */
    public boolean onFail(Throwable cause)
    {
        Callback callback=_interested.get();
        if (callback!=null && _interested.compareAndSet(callback,null))
        {
            callback.failed(cause);
            return true;
        }
        return false;
    }
    
    /* ------------------------------------------------------------ */
    public void onClose()
    {
        Callback callback=_interested.get();
        if (callback!=null && _interested.compareAndSet(callback,null))
            callback.failed(new ClosedChannelException());
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return String.format("FillInterest@%x{%b,%s}",hashCode(),_interested.get(),_interested.get());
    }
    
    /* ------------------------------------------------------------ */
    /** Register the read interest 
     * Abstract method to be implemented by the Specific ReadInterest to
     * enquire if a read is immediately possible and if not to schedule a future
     * call to {@link #fillable()} or {@link #onFail(Throwable)}
     * @return true if a read is possible
     * @throws IOException
     */
    abstract protected boolean needsFill() throws IOException;
    
    
}
