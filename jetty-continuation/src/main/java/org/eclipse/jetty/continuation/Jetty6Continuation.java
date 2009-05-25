package org.eclipse.jetty.continuation;

import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;


public class Jetty6Continuation implements ContinuationFilter.PartialContinuation
{
    private final ServletRequest _request;
    private final ServletResponse _response;
    private final org.mortbay.util.ajax.Continuation _j6Continuation;
    
    private Throwable _retry;
    private int _timeout;
    private boolean _initial=true;
    private volatile boolean _completed=false;
    private volatile boolean _resumed=false;
    private volatile boolean _expired=false;
    private boolean _wrappers=false;
    private List<ContinuationListener> _listeners;
    
    public Jetty6Continuation(ServletRequest request, ServletResponse response,org.mortbay.util.ajax.Continuation continuation)
    {
        _request=request;
        _response=response;
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

    public ServletRequest getServletRequest()
    {
        return _request;
    }

    public ServletResponse getServletResponse()
    { 
        return _response;
    }

    public boolean isExpired()
    {
        return _expired;
    }

    public boolean isInitial()
    {
        return _initial;
    }

    public boolean isResumed()
    {
        return _resumed;
    }

    public boolean isSuspended()
    {
        return _retry!=null;
    }

    public void keepWrappers()
    {
        _wrappers=true;
    }

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

    public void setTimeout(long timeoutMs)
    {
        _timeout=(timeoutMs>Integer.MAX_VALUE)?Integer.MAX_VALUE:(int)timeoutMs;
    }

    public void suspend()
    {
        try
        {
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

    public boolean wrappersKept()
    {
        return _wrappers;
    }

    public boolean enter()
    {
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
            
            return !_completed;
        }
        
        return true;
    }

    public void exit()
    {
        _initial=false;
        
        Throwable th=_retry;
        _retry=null;
        if (th instanceof ThreadDeath)
            throw (ThreadDeath)th;
        if (th instanceof Error)
            throw (Error)th;
        if (th instanceof RuntimeException)
            throw (RuntimeException)th;

        if (_listeners!=null)
        {
            for (ContinuationListener l: _listeners)
                l.onComplete(this);
        }
        
    }
}
