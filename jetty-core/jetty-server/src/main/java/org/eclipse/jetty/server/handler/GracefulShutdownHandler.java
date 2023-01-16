//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler to track active requests and allow them to gracefully complete.
 */
public class GracefulShutdownHandler extends Handler.Wrapper implements Graceful
{
    private static final Logger LOG = LoggerFactory.getLogger(GracefulShutdownHandler.class);

    private CounterStatistic dispatchedStats = new CounterStatistic();
    private Shutdown shutdown;

    class ShutdownTrackingCallback extends Callback.Nested
    {
        final Request request;
        final Response response;

        public ShutdownTrackingCallback(Request request, Response response, Callback callback)
        {
            super(callback);
            this.request = request;
            this.response = response;
            dispatchedStats.increment();
        }

        public void decrement()
        {
            dispatchedStats.decrement();
        }

        @Override
        public void succeeded()
        {
            decrement();
            super.succeeded();
            if (isShutdown())
            {
                shutdown.check();
            }
        }

        @Override
        public void failed(Throwable x)
        {
            decrement();

            Callback callback = Callback.from(getCallback(), shutdown::check);

            if (isShutdown())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Service Unavailable: {}", request.getHttpURI(), x);
                Response.writeError(request, response, callback, HttpStatus.SERVICE_UNAVAILABLE_503);
            }
            else
            {
                Response.writeError(request, response, callback, x);
            }
        }
    }

    private boolean serviceUnavailable(Request request, Response response, Callback callback)
    {
        if (!response.isCommitted())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Service Unavailable (before wrapped process): {}", request.getHttpURI());
            response.getHeaders().put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);
            Response.writeError(request, response, callback, HttpStatus.SERVICE_UNAVAILABLE_503);
            return true;
        }
        return false;
    }

    @Override
    public boolean process(Request request, Response response, Callback callback) throws Exception
    {
        Handler handler = getHandler();
        ShutdownTrackingCallback shutdownCallbackTracking = new ShutdownTrackingCallback(request, response, callback);
        if (handler == null || !isStarted() || isShutdown())
        {
            // always return 503
            if (serviceUnavailable(request, response, shutdownCallbackTracking));
                return true;
        }

        try
        {
            boolean handled = super.process(request, response, shutdownCallbackTracking);
            if (!handled)
                shutdownCallbackTracking.decrement();
            return handled;
        }
        catch (Throwable t)
        {
            shutdownCallbackTracking.failed(t);
            throw t;
        }
        finally
        {
            if (isShutdown())
                shutdown.check();
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        shutdown = new Shutdown(this)
        {
            @Override
            public boolean isShutdownDone()
            {
                return dispatchedStats.getCurrent() == 0;
            }
        };

        super.doStart();
    }

    @Override
    public CompletableFuture<Void> shutdown()
    {
        if (LOG.isInfoEnabled())
            LOG.info("Shutdown requested");
        return shutdown.shutdown();
    }

    @Override
    public boolean isShutdown()
    {
        return shutdown.isShutdown();
    }
}
