package org.eclipse.jetty.continuation;

import java.io.IOException;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.DispatcherType;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;


public class Servlet3Continuation implements Continuation
{
    private final ServletRequest _request;
    private final ServletResponse _response;
    private AsyncContext _context;
    private final AsyncListener _listener = new AsyncListener()
    {
        public void onComplete(AsyncEvent event) throws IOException
        {
        }

        public void onTimeout(AsyncEvent event) throws IOException
        {
            _initial=false;
        }
    };

    private volatile boolean _initial=true;
    private volatile boolean _resumed=false;
    private volatile boolean _expired=false;
    private volatile boolean _wrappers=false;
    
    public Servlet3Continuation(ServletRequest request, ServletResponse response)
    {
        _request=request;
        _response=response;
    }
    

    public void addContinuationListener(final ContinuationListener listener)
    {
        _request.addAsyncListener(new AsyncListener()
        {
            public void onComplete(final AsyncEvent event) throws IOException
            {
                listener.onComplete(Servlet3Continuation.this);
            }

            public void onTimeout(AsyncEvent event) throws IOException
            {
                _expired=true;
                listener.onTimeout(Servlet3Continuation.this);
            }
        });
    }

    public void complete()
    {
        AsyncContext context=_context;
        if (context==null)
            throw new IllegalStateException();
        _context.complete();
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
        // TODO - this is not perfect if non continuation API is used directly
        return _initial&&_request.getDispatcherType()!=DispatcherType.ASYNC;
    }

    public boolean isResumed()
    {
        return _resumed;
    }

    public boolean isSuspended()
    {
        return _request.isAsyncStarted();
    }

    public void keepWrappers()
    {
        _wrappers=true;
    }

    public void resume()
    {
        AsyncContext context=_context;
        if (context==null)
            throw new IllegalStateException();
        _resumed=true;
        _context.dispatch();
    }

    public void setTimeout(long timeoutMs)
    {
        _request.setAsyncTimeout(timeoutMs);
    }

    public void suspend()
    {
        _request.addAsyncListener(_listener);
        _resumed=false;
        _expired=false;
        _context=_request.startAsync();
    }

    public boolean wrappersKept()
    {
        return _wrappers;
    }

}
