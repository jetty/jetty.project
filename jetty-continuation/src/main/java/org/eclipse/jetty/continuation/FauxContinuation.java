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

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.ServletResponseWrapper;

import org.eclipse.jetty.continuation.ContinuationFilter.FilteredContinuation;


/* ------------------------------------------------------------ */
/**
 * A blocking implementation of Continuation.
 * This implementation of Continuation is used by the {@link ContinuationFilter}
 * when there are is no native or asynchronous continuation type available. 
 */
class FauxContinuation implements FilteredContinuation
{
    // common exception used for all continuations.  
    // Turn on debug in ContinuationFilter to see real stack trace.
    private final static ContinuationThrowable __exception = new ContinuationThrowable();
    
    private static final int __HANDLING=1;   // Request dispatched to filter/servlet
    private static final int __SUSPENDING=2;   // Suspend called, but not yet returned to container
    private static final int __RESUMING=3;     // resumed while suspending
    private static final int __COMPLETING=4;   // resumed while suspending or suspended
    private static final int __SUSPENDED=5;    // Suspended and parked
    private static final int __UNSUSPENDING=6;
    private static final int __COMPLETE=7;

    private final ServletRequest _request;
    private ServletResponse _response;
    
    private int _state=__HANDLING;
    private boolean _initial=true;
    private boolean _resumed=false;
    private boolean _timeout=false;
    private boolean _responseWrapped=false;
    private  long _timeoutMs=30000; // TODO configure
    
    private ArrayList<ContinuationListener> _listeners; 

    FauxContinuation(final ServletRequest request)
    {
        _request=request;
    }

    /* ------------------------------------------------------------ */
    public void onComplete()
    {
        if (_listeners!=null)
            for (ContinuationListener l:_listeners)
                l.onComplete(this);
    }
    
    /* ------------------------------------------------------------ */
    public void onTimeout()
    {
        if (_listeners!=null)
            for (ContinuationListener l:_listeners)
                l.onTimeout(this);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#isResponseWrapped()
     */
    public boolean isResponseWrapped()
    {
        return _responseWrapped;
    }

    /* ------------------------------------------------------------ */
    public boolean isInitial()
    {
        synchronized(this)
        {
            return _initial;
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isResumed()
    {
        synchronized(this)
        {
            return _resumed;
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isSuspended()
    {
        synchronized(this)
        {
            switch(_state)
            {
                case __HANDLING:
                    return false;
                case __SUSPENDING:
                case __RESUMING:
                case __COMPLETING:
                case __SUSPENDED:
                    return true;
                case __UNSUSPENDING:
                default:
                    return false;   
            }
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isExpired()
    {
        synchronized(this)
        {
            return _timeout;
        }
    }

    /* ------------------------------------------------------------ */
    public void setTimeout(long timeoutMs)
    {
        _timeoutMs = timeoutMs;
    }

    /* ------------------------------------------------------------ */
    public void suspend(ServletResponse response)
    {
        _response=response;
        _responseWrapped=response instanceof ServletResponseWrapper;
        suspend();
    }
    
    /* ------------------------------------------------------------ */
    public void suspend()
    {
        synchronized (this)
        {
            switch(_state)
            {
                case __HANDLING:
                    _timeout=false;
                    _resumed=false;
                    _state=__SUSPENDING;
                    return;

                case __SUSPENDING:
                case __RESUMING:
                    return;

                case __COMPLETING:
                case __SUSPENDED:
                case __UNSUSPENDING:
                    throw new IllegalStateException(this.getStatusString());

                default:
                    throw new IllegalStateException(""+_state);
            }

        }
    }


    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see org.mortbay.jetty.Suspendor#resume()
     */
    public void resume()
    {
        synchronized (this)
        {
            switch(_state)
            {
                case __HANDLING:
                    _resumed=true;
                    return;
                    
                case __SUSPENDING:
                    _resumed=true;
                    _state=__RESUMING;
                    return;

                case __RESUMING:
                case __COMPLETING:
                    return;
                    
                case __SUSPENDED:
                    fauxResume();
                    _resumed=true;
                    _state=__UNSUSPENDING;
                    break;
                    
                case __UNSUSPENDING:
                    _resumed=true;
                    return;
                    
                default:
                    throw new IllegalStateException(this.getStatusString());
            }
        }
        
    }
    

    /* ------------------------------------------------------------ */
    public void complete()
    {
        // just like resume, except don't set _resumed=true;
        synchronized (this)
        {
            switch(_state)
            {
                case __HANDLING:
                    throw new IllegalStateException(this.getStatusString());
                    
                case __SUSPENDING:
                    _state=__COMPLETING;
                    break;
                    
                case __RESUMING:
                    break;

                case __COMPLETING:
                    return;
                    
                case __SUSPENDED:
                    _state=__COMPLETING;
                    fauxResume();
                    break;
                    
                case __UNSUSPENDING:
                    return;
                    
                default:
                    throw new IllegalStateException(this.getStatusString());
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#getServletResponse()
     */
    public boolean enter(ServletResponse response)
    {
        _response=response;
        return true;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.continuation.Continuation#getServletResponse()
     */
    public ServletResponse getServletResponse()
    {
        return _response;
    }
    

    /* ------------------------------------------------------------ */
    void handling()
    {
        synchronized (this)
        {
            _responseWrapped=false;
            switch(_state)
            {
                case __HANDLING:
                    throw new IllegalStateException(this.getStatusString());

                case __SUSPENDING:
                case __RESUMING:
                    throw new IllegalStateException(this.getStatusString());

                case __COMPLETING:
                    return;

                case __SUSPENDED:
                    fauxResume();
                case __UNSUSPENDING:
                    _state=__HANDLING;
                    return;

                default:
                    throw new IllegalStateException(""+_state);
            }

        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return true if handling is complete
     */
    public boolean exit()
    {
        synchronized (this)
        {
            switch(_state)
            {
                case __HANDLING:
                    _state=__COMPLETE;
                    onComplete();
                    return true;

                case __SUSPENDING:
                    _initial=false;
                    _state=__SUSPENDED;
                    fauxSuspend(); // could block and change state.
                    if (_state==__SUSPENDED || _state==__COMPLETING)
                    {
                        onComplete();
                        return true;
                    }
                    
                    _initial=false;
                    _state=__HANDLING;
                    return false; 

                case __RESUMING:
                    _initial=false;
                    _state=__HANDLING;
                    return false; 

                case __COMPLETING:
                    _initial=false;
                    _state=__COMPLETE;
                    onComplete();
                    return true;

                case __SUSPENDED:
                case __UNSUSPENDING:
                default:
                    throw new IllegalStateException(this.getStatusString());
            }
        }
    }

    /* ------------------------------------------------------------ */
    protected void expire()
    {
        // just like resume, except don't set _resumed=true;

        synchronized (this)
        {
            _timeout=true;
        }
        
        onTimeout();
        
        synchronized (this)
        {
            switch(_state)
            {
                case __HANDLING:
                    return;
                    
                case __SUSPENDING:
                    _timeout=true;
                    _state=__RESUMING;
                    fauxResume();
                    return;
                    
                case __RESUMING:
                    return;
                    
                case __COMPLETING:
                    return;
                    
                case __SUSPENDED:
                    _timeout=true;
                    _state=__UNSUSPENDING;
                    break;
                    
                case __UNSUSPENDING:
                    _timeout=true;
                    return;
                    
                default:
                    throw new IllegalStateException(this.getStatusString());
            }
        }
    }

    private void fauxSuspend()
    {
        long expire_at = System.currentTimeMillis()+_timeoutMs;
        long wait=_timeoutMs;
        while (_timeoutMs>0 && wait>0)
        {
            try
            {
                this.wait(wait);
            }
            catch (InterruptedException e)
            {
                break;
            }
            wait=expire_at-System.currentTimeMillis();
        }

        if (_timeoutMs>0 && wait<=0)
            expire();
    }
    
    private void fauxResume()
    {
        _timeoutMs=0;
        this.notifyAll();
    }
    
    @Override
    public String toString()
    {
        return getStatusString();
    }
    
    String getStatusString()
    {
        synchronized (this)
        {
            return
            ((_state==__HANDLING)?"HANDLING":
                    (_state==__SUSPENDING)?"SUSPENDING":
                        (_state==__SUSPENDED)?"SUSPENDED":
                            (_state==__RESUMING)?"RESUMING":
                                (_state==__UNSUSPENDING)?"UNSUSPENDING":
                                    (_state==__COMPLETING)?"COMPLETING":
                                    ("???"+_state))+
            (_initial?",initial":"")+
            (_resumed?",resumed":"")+
            (_timeout?",timeout":"");
        }
    }

    
    public void addContinuationListener(ContinuationListener listener)
    {
        if (_listeners==null)
            _listeners=new ArrayList<ContinuationListener>();
        _listeners.add(listener);
        
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
}
