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
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.Graceful;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler to track active requests and allow them to gracefully complete.
 */
public class GracefulShutdownHandler extends Handler.Wrapper implements Graceful
{
    private static final Logger LOG = LoggerFactory.getLogger(GracefulShutdownHandler.class);

    private final LongAdder dispatchedStats = new LongAdder();
    private final Shutdown shutdown;

    public GracefulShutdownHandler()
    {
        shutdown = new Shutdown(this)
        {
            @Override
            public boolean isShutdownDone()
            {
                long count = dispatchedStats.sum();
                if (LOG.isDebugEnabled())
                    LOG.debug("isShutdownDone: count {}", count);
                return count == 0;
            }
        };
    }

    /**
     * Test during Graceful shutdown to see if we are done with all
     * graceful actions.
     *
     * @return true if graceful is active
     */
    @Override
    public boolean isShutdown()
    {
        return shutdown.isShutdown();
    }

    @Override
    public boolean process(Request request, Response response, Callback callback) throws Exception
    {
        // Increment the counter always, before we test for isShutdown to avoid race.
        ShutdownTrackingCallback shutdownCallback = new ShutdownTrackingCallback(request, response, callback);

        Handler handler = getHandler();
        if (handler == null || !isStarted() || isShutdown())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Service Unavailable: {}", request.getHttpURI());
            Response.writeError(request, response, shutdownCallback, HttpStatus.SERVICE_UNAVAILABLE_503);
            return true;
        }

        try
        {
            boolean handled = super.process(request, response, shutdownCallback);
            if (!handled)
                shutdownCallback.decrement();
            return handled;
        }
        catch (Throwable t)
        {
            shutdownCallback.decrement();
            throw t;
        }
        finally
        {
            if (isShutdown())
                shutdown.check();
        }
    }

    @Override
    public CompletableFuture<Void> shutdown()
    {
        if (LOG.isInfoEnabled())
            LOG.info("Shutdown requested");
        return shutdown.shutdown();
    }

    private class ShutdownTrackingCallback extends Callback.Nested
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
        public void failed(Throwable x)
        {
            decrement();
            super.failed(x);
            if (isShutdown())
                shutdown.check();
        }

        @Override
        public void succeeded()
        {
            decrement();
            super.succeeded();
            if (isShutdown())
                shutdown.check();
        }
    }
}
