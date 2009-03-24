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

package org.eclipse.jetty.server;

import java.io.IOException;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;

import org.eclipse.jetty.util.ajax.Continuation;

public class Servlet3Continuation implements Continuation, AsyncListener
{
    AsyncContext _asyncContext;
    Request _request;
    Object _object;
    RetryRequest _retry;
    boolean _resumed=false;
    boolean _timeout=false;
    
    Servlet3Continuation(Request request)
    {
        _request=request;
    }
    
    public Object getObject()
    {
        return _object;
    }

    public boolean isExpired()
    {
        return _asyncContext!=null && _timeout;
    }

    public boolean isNew()
    {
        return _retry==null;
    }

    public boolean isPending()
    {
        return _asyncContext!=null && (_request.getAsyncRequest().isSuspended() || !_request.getAsyncRequest().isInitial());
    }

    public boolean isResumed()
    {
        return _asyncContext!=null && _resumed;
    }

    public void reset()
    {
        _resumed=false;
        _timeout=false;
    }

    public void resume()
    {
        if (_asyncContext==null)
            throw new IllegalStateException();
        _resumed=true;
        _asyncContext.dispatch();
    }

    public void setMutex(Object mutex)
    {
    }

    public void setObject(Object o)
    {
        _object=o;
    }

    public boolean suspend(long timeout)
    {
        _asyncContext=_request.startAsync();;
        if (!_request.getAsyncRequest().isInitial()||_resumed||_timeout)
        {
            _resumed=false;
            _timeout=false;
            return _resumed;
        }

        _request.setAsyncTimeout(timeout);
        _request.addAsyncListener(this);
        if (_retry==null)
            _retry=new RetryRequest();
        throw _retry;
        
    }

    public void onComplete(AsyncEvent event) throws IOException
    {
        
    }

    public void onTimeout(AsyncEvent event) throws IOException
    {
        _timeout=true;
        _request.getAsyncRequest().dispatch();
    }

}
