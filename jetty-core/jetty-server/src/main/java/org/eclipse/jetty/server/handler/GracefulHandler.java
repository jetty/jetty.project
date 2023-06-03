//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
public class GracefulHandler extends Handler.Wrapper implements Graceful
{
    private static final Logger LOG = LoggerFactory.getLogger(GracefulHandler.class);

    private final LongAdder dispatchedStats = new LongAdder();
    private final Shutdown shutdown;

    public GracefulHandler()
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
     * Flag indicating that Graceful shutdown has been initiated.
     *
     * @return whether the graceful shutdown has been initiated
     * @see Graceful
     */
    @Override
    public boolean isShutdown()
    {
        return shutdown.isShutdown();
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        Handler handler = getHandler();
        if (handler == null || !isStarted())
        {
            // Nothing to do here, skip it
            return false;
        }

        // Increment the counter before the test for isShutdown(), to avoid race conditions.
        ShutdownTrackingCallback shutdownCallback = new ShutdownTrackingCallback(request, response, callback);
        if (isShutdown())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Service Unavailable: {}", request.getHttpURI());
            Response.writeError(request, response, shutdownCallback, HttpStatus.SERVICE_UNAVAILABLE_503);
            return true;
        }

        try
        {
            boolean handled = super.handle(request, response, shutdownCallback);
            if (!handled)
                shutdownCallback.decrement();
            return handled;
        }
        catch (Throwable t)
        {
            Response.writeError(request, response, shutdownCallback, t);
            return true;
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
        if (LOG.isDebugEnabled())
            LOG.debug("Shutdown requested");
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
