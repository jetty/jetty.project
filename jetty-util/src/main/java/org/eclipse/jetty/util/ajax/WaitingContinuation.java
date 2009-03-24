// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.ajax;

import org.eclipse.jetty.util.log.Log;

public class WaitingContinuation implements org.eclipse.jetty.util.ajax.Continuation
{
    Object _mutex;
    Object _object;
    boolean _new=true;
    boolean _resumed=false;
    boolean _pending=false;
    boolean _expired=false;

    public WaitingContinuation()
    {
        _mutex=this;
    }
    
    public WaitingContinuation(Object mutex)
    {
        _mutex=mutex==null?this:mutex;
    }
    
    public void resume()
    {
        synchronized (_mutex)
        {
            _resumed=true;
            _mutex.notify();
        }
    }
    
    public void reset()
    {
        synchronized (_mutex)
        {
            _resumed=false;
            _pending=false;
            _expired=false;
            _mutex.notify();
        }
    }

    public boolean isNew()
    {
        return _new;
    }

    public boolean suspend(long timeout)
    {
        synchronized (_mutex)
        {
            _new=false;
            _pending=true;
            boolean result;
            try
            {
                if (!_resumed && timeout>=0)
                {
                    if (timeout==0)
                        _mutex.wait();
                    else if (timeout>0)
                        _mutex.wait(timeout);
                        
                }
            }
            catch (InterruptedException e)
            {
                _expired=true;
                Log.ignore(e);
            }
            finally
            {
                result=_resumed;
                _resumed=false;
                _pending=false;
            }
            
            return result;
        }
    }
    
    public boolean isPending()
    {
        synchronized (_mutex)
        {
            return _pending;
        }
    }
    
    public boolean isResumed()
    {
        synchronized (_mutex)
        {
            return _resumed;
        }
    }
    
    public boolean isExpired()
    {
        synchronized (_mutex)
        {
            return _expired;
        }
    }

    public Object getObject()
    {
        return _object;
    }

    public void setObject(Object object)
    {
        _object = object;
    }

    public Object getMutex()
    {
        return _mutex;
    }

    public void setMutex(Object mutex)
    {
        if (_pending && mutex!=_mutex)
            throw new IllegalStateException();
        _mutex = mutex==null ? this : mutex; 
    }

    public String toString()
    {
        synchronized (this)
        {
            return "WaitingContinuation@"+hashCode()+
            (_new?",new":"")+
            (_pending?",pending":"")+
            (_resumed?",resumed":"")+
            (_expired?",expired":"");
        }
    }
}
