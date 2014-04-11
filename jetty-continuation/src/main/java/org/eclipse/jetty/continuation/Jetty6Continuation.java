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

package org.eclipse.jetty.continuation;

import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.ServletResponseWrapper;

import org.mortbay.log.Log;
import org.mortbay.log.Logger;

/* ------------------------------------------------------------ */
/**
 * This implementation of Continuation is used by {@link ContinuationSupport}
 * when it detects that the application is deployed in a jetty-6 server.
 * This continuation requires the {@link ContinuationFilter} to be deployed.
 */
public class Jetty6Continuation implements ContinuationFilter.FilteredContinuation
{
    private static final Logger LOG = Log.getLogger(Jetty6Continuation.class.getName());

    // Exception reused for all continuations
    // Turn on debug in ContinuationFilter to see real stack trace.
    private final static ContinuationThrowable __exception = new ContinuationThrowable();

    private final ServletRequest _request;
    private ServletResponse _response;
    private final org.mortbay.util.ajax.Continuation _j6Continuation;

    private Throwable _retry;
    private int _timeout;
    private boolean _initial=true;
    private volatile boolean _completed=false;
    private volatile boolean _resumed=false;
    private volatile boolean _expired=false;
    private boolean _responseWrapped=false;
    private List<ContinuationListener> _listeners;

    public Jetty6Continuation(ServletRequest request, org.mortbay.util.ajax.Continuation continuation)
    {
        if (!ContinuationFilter._initialized)
        {
            LOG.warn("!ContinuationFilter installed",null,null);
            throw new IllegalStateException("!ContinuationFilter installed");
        }
        _request=request;
        _j6Continuation=continuation;
    }

    public void addContinuationListener(final ContinuationListener listener)
    {
        if (_listeners==null)
            _listeners=new ArrayList<ContinuationListener>();
        _listeners.add(listener);
    }

    public void complete()
    {
        synchronized(this)
        {
            if (_resumed)
                throw new IllegalStateException();
            _completed=true;
            if (_j6Continuation.isPending())
                _j6Continuation.resume();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#getAttribute(java.lang.String)
     */
    public Object getAttribute(String name)
    {
        return _request.getAttribute(name);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#removeAttribute(java.lang.String)
     */
    public void removeAttribute(String name)
    {
        _request.removeAttribute(name);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#setAttribute(java.lang.String, java.lang.Object)
     */
    public void setAttribute(String name, Object attribute)
    {
        _request.setAttribute(name,attribute);
    }

    /* ------------------------------------------------------------ */
    public ServletResponse getServletResponse()
    {
        return _response;
    }

    /* ------------------------------------------------------------ */
    public boolean isExpired()
    {
        return _expired;
    }

    /* ------------------------------------------------------------ */
    public boolean isInitial()
    {
        return _initial;
    }

    /* ------------------------------------------------------------ */
    public boolean isResumed()
    {
        return _resumed;
    }

    /* ------------------------------------------------------------ */
    public boolean isSuspended()
    {
        return _retry!=null;
    }

    /* ------------------------------------------------------------ */
    public void resume()
    {
        synchronized(this)
        {
            if (_completed)
                throw new IllegalStateException();
            _resumed=true;
            if (_j6Continuation.isPending())
                _j6Continuation.resume();
        }
    }

    /* ------------------------------------------------------------ */
    public void setTimeout(long timeoutMs)
    {
        _timeout=(timeoutMs>Integer.MAX_VALUE)?Integer.MAX_VALUE:(int)timeoutMs;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#suspend(javax.servlet.ServletResponse)
     */
    public void suspend(ServletResponse response)
    {
        try
        {
            _response=response;
            _responseWrapped=_response instanceof ServletResponseWrapper;
            _resumed=false;
            _expired=false;
            _completed=false;
            _j6Continuation.suspend(_timeout);
        }
        catch(Throwable retry)
        {
            _retry=retry;
        }
    }

    /* ------------------------------------------------------------ */
    public void suspend()
    {
        try
        {
            _response=null;
            _responseWrapped=false;
            _resumed=false;
            _expired=false;
            _completed=false;
            _j6Continuation.suspend(_timeout);
        }
        catch(Throwable retry)
        {
            _retry=retry;
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isResponseWrapped()
    {
        return _responseWrapped;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#undispatch()
     */
    public void undispatch()
    {
        if (isSuspended())
        {
            if (ContinuationFilter.__debug)
                throw new ContinuationThrowable();
            throw __exception;
        }
        throw new IllegalStateException("!suspended");
    }

    /* ------------------------------------------------------------ */
    public boolean enter(ServletResponse response)
    {
        _response=response;
        _expired=!_j6Continuation.isResumed();

        if (_initial)
            return true;

        _j6Continuation.reset();

        if (_expired)
        {
            if (_listeners!=null)
            {
                for (ContinuationListener l: _listeners)
                    l.onTimeout(this);
            }
        }

        return !_completed;
    }

    /* ------------------------------------------------------------ */
    public boolean exit()
    {
        _initial=false;

        Throwable th=_retry;
        _retry=null;
        if (th instanceof Error)
            throw (Error)th;
        if (th instanceof RuntimeException)
            throw (RuntimeException)th;

        if (_listeners!=null)
        {
            for (ContinuationListener l: _listeners)
                l.onComplete(this);
        }

        return true;
    }
}
