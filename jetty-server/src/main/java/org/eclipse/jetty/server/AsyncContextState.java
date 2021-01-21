//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.eclipse.jetty.server.handler.ContextHandler;

public class AsyncContextState implements AsyncContext
{
    private final HttpChannel _channel;
    volatile HttpChannelState _state;

    public AsyncContextState(HttpChannelState state)
    {
        _state = state;
        _channel = _state.getHttpChannel();
    }

    public HttpChannel getHttpChannel()
    {
        return _channel;
    }

    HttpChannelState state()
    {
        HttpChannelState state = _state;
        if (state == null)
            throw new IllegalStateException("AsyncContext completed and/or Request lifecycle recycled");
        return state;
    }

    @Override
    public void addListener(final AsyncListener listener, final ServletRequest request, final ServletResponse response)
    {
        AsyncListener wrap = new WrappedAsyncListener(listener, request, response);
        state().addListener(wrap);
    }

    @Override
    public void addListener(AsyncListener listener)
    {
        state().addListener(listener);
    }

    @Override
    public void complete()
    {
        state().complete();
    }

    @Override
    public <T extends AsyncListener> T createListener(Class<T> clazz) throws ServletException
    {
        ContextHandler contextHandler = state().getContextHandler();
        if (contextHandler != null)
            return contextHandler.getServletContext().createInstance(clazz);
        try
        {
            return clazz.getDeclaredConstructor().newInstance();
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }
    }

    @Override
    public void dispatch()
    {
        state().dispatch(null, null);
    }

    @Override
    public void dispatch(String path)
    {
        state().dispatch(null, path);
    }

    @Override
    public void dispatch(ServletContext context, String path)
    {
        state().dispatch(context, path);
    }

    @Override
    public ServletRequest getRequest()
    {
        return state().getAsyncContextEvent().getSuppliedRequest();
    }

    @Override
    public ServletResponse getResponse()
    {
        return state().getAsyncContextEvent().getSuppliedResponse();
    }

    @Override
    public long getTimeout()
    {
        return state().getTimeout();
    }

    @Override
    public boolean hasOriginalRequestAndResponse()
    {
        HttpChannel channel = state().getHttpChannel();
        return channel.getRequest() == getRequest() && channel.getResponse() == getResponse();
    }

    @Override
    public void setTimeout(long arg0)
    {
        state().setTimeout(arg0);
    }

    @Override
    public void start(final Runnable task)
    {
        final HttpChannel channel = state().getHttpChannel();
        channel.execute(new Runnable()
        {
            @Override
            public void run()
            {
                ContextHandler.Context context = state().getAsyncContextEvent().getContext();
                if (context == null)
                    task.run();
                else
                    context.getContextHandler().handle(channel.getRequest(), task);
            }
        });
    }

    public void reset()
    {
        _state = null;
    }

    public HttpChannelState getHttpChannelState()
    {
        return state();
    }

    public static class WrappedAsyncListener implements AsyncListener
    {
        private final AsyncListener _listener;
        private final ServletRequest _request;
        private final ServletResponse _response;

        public WrappedAsyncListener(AsyncListener listener, ServletRequest request, ServletResponse response)
        {
            _listener = listener;
            _request = request;
            _response = response;
        }

        public AsyncListener getListener()
        {
            return _listener;
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
            _listener.onTimeout(new AsyncEvent(event.getAsyncContext(), _request, _response, event.getThrowable()));
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
            _listener.onStartAsync(new AsyncEvent(event.getAsyncContext(), _request, _response, event.getThrowable()));
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
            _listener.onError(new AsyncEvent(event.getAsyncContext(), _request, _response, event.getThrowable()));
        }

        @Override
        public void onComplete(AsyncEvent event) throws IOException
        {
            _listener.onComplete(new AsyncEvent(event.getAsyncContext(), _request, _response, event.getThrowable()));
        }
    }
}
