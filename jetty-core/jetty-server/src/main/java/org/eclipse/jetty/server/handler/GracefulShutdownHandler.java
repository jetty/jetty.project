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

    @Override
    public boolean process(Request request, Response response, Callback callback) throws Exception
    {
        Handler handler = getHandler();
        if (handler == null || !isStarted() || isShutdown())
        {
            if (!response.isCommitted())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Service Unavailable (before wrapped process): {}", request.getHttpURI());
                response.getHeaders().put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);
                Response.writeError(request, response, callback, HttpStatus.SERVICE_UNAVAILABLE_503);
            }
            return true;
        }

        try
        {
            dispatchedStats.increment();

            // TODO: what to do if wrapped handler uses request.demand(Runnable) ??
            // TODO: should we wrap the incoming Callback to handle decrement logic better??

            // Count incoming request
            return super.process(request, response, callback);
        }
        catch(Throwable t)
        {
            if (isShutdown())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Service Unavailable (after wrapped process): {}", request.getHttpURI());
                if (!response.isCommitted())
                {
                    // writeError will fail the callback
                    response.getHeaders().put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);
                    Response.writeError(request, response, callback, HttpStatus.SERVICE_UNAVAILABLE_503);
                }
                else
                {
                    request.getConnectionMetaData().getConnection().close();
                }
            }
            else
            {
                callback.failed(t);
            }
            throw t;
        }
        finally
        {
            dispatchedStats.decrement();

            if (shutdown.isShutdown())
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
