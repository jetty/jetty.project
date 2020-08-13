//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.util.concurrent.Future;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannelState;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.component.Graceful;

public class GracefulShutdownHandler extends HandlerWrapper implements Graceful
{
    private HttpChannelState state;

    private final Graceful.Shutdown _shutdown = new Graceful.Shutdown()
    {
        @Override
        protected FutureCallback newShutdownCallback()
        {
            return new FutureCallback(state == null
                    ? true
                    : state.isResponseCompleted());
        }
    };

    private final AsyncListener _onCompletion = new AsyncListener()
    {
        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
            event.getAsyncContext().addListener(this);
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
            confirmShutdown();
        }

        @Override
        public void onComplete(AsyncEvent event) throws IOException
        {
            confirmShutdown();
        }

        private void confirmShutdown()
        {
            FutureCallback shutdown = _shutdown.get();
            if (shutdown != null)
                shutdown.succeeded();
        }
    };

    @Override
    public void handle(String path, Request baseRequest,
            HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException
    {
        state = baseRequest.getHttpChannelState();
        try
        {
            Handler handler = getHandler();
            if (handler != null && !_shutdown.isShutdown() && isStarted())
                handler.handle(path, baseRequest, request, response);
            else
            {
                if (!baseRequest.isHandled())
                    baseRequest.setHandled(true);
                if (!baseRequest.getResponse().isCommitted())
                    response.sendError(HttpStatus.SERVICE_UNAVAILABLE_503);
            }
        }
        finally
        {
            if (state.isSuspended() || state.isDispatched())
            {
                if (state.isInitial())
                {
                    state.addListener(_onCompletion);
                }
            }
            else if (state.isInitial())
            {
                // If we have no more dispatches, should we signal shutdown?
                FutureCallback shutdown = _shutdown.get();
                if (shutdown != null)
                {
                    response.flushBuffer();
                    shutdown.succeeded();
                }
            }
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        _shutdown.cancel();
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        _shutdown.cancel();
        super.doStop();
    }

    @Override
    public Future<Void> shutdown()
    {
        return _shutdown.shutdown();
    }

    @Override
    public boolean isShutdown()
    {
        return _shutdown.isShutdown();
    }

}
