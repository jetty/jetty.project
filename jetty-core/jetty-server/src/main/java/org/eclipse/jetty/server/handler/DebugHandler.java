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

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DateCache;
import org.eclipse.jetty.util.RolloverFileOutputStream;

/**
 * Debug Handler.
 * <p>
 * A lightweight debug handler that can be used in production code.
 * Details of the request and response are written to an output stream
 * and the current thread name is updated with information that will link
 * to the details in that output.
 * </p>
 * <p>
 * Note that due to async processing, the logging of request processing may
 * appear out of order.
 * </p>
 */
public class DebugHandler extends Handler.Wrapper implements Connection.Listener
{
    private static final DateCache __date = new DateCache("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
    private OutputStream _out;
    private PrintStream _print;
    private boolean _showHeaders;
    private final String _attr = String.format("__R%s@%x", this.getClass().getSimpleName(), System.identityHashCode(this));

    /**
     * Callback class used to manage possibly async handling by downstream handlers.
     * The
     */
    private class HandlingCallback extends Callback.Nested
    {
        private final Request _request;
        private final Response _response;
        private final AtomicBoolean _completing = new AtomicBoolean(false);

        private HandlingCallback(Callback callback, Request request, Response response)
        {
            super(callback);
            _request = request;
            _response = response;
        }

        /**
         * Called when the handler chain has completed. We log either the completion of the request
         * or an in-progress message if the request is still being processed asynchronously (ie this
         * callback's {@link #succeeded()} or {@link #failed(Throwable)} methods have not yet been called).
         */
        private void onHandlingCompleted(Throwable throwable)
        {
            if (_completing.compareAndSet(false, true))
            {
                logContinuation();
            }
            else
            {
                logCompletion(throwable);
            }
        }

        /**
         * Called when this callback completes, either with success or failure. We output a
         * request completion log message only in the case where we have been called back
         * asynchronously after the handler chain has finished.
         * @param throwable null if the handling succeeded or otherwise the failure that occurred
         */
        private void onCallbackCompleted(Throwable throwable)
        {
            //If we can set the value to true, we know that handling has not yet finalized and onHandlingCompleted(Throwable)
            //will be called and will log the completion message. On the other hand, if we are not able to set it, then this means that
            //handling has already finished, so we must log the completion message.
            if (!_completing.compareAndSet(false, true))
                logCompletion(throwable);
        }

        private void logCompletion(Throwable throwable)
        {
            log("<< r=%s async=false %d %s%n%s", findRequestName(_request), _response.getStatus(), (throwable == null ? "" : throwable.toString()), _response.getHeaders());
        }

        private void logContinuation()
        {
            log("|| r=%s async=true", findRequestName(_request));
        }

        @Override
        public void succeeded()
        {
            onCallbackCompleted(null);
            super.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            onCallbackCompleted(x);
            super.failed(x);
        }
    }

    public DebugHandler()
    {
        this(null);
    }

    public DebugHandler(Handler handler)
    {
        super(handler);
    }

    public boolean isShowHeaders()
    {
        return _showHeaders;
    }

    public void setShowHeaders(boolean showHeaders)
    {
        _showHeaders = showHeaders;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        Thread thread = Thread.currentThread();
        String name = thread.getName() + ":" + request.getHttpURI().getPathQuery();
        boolean willHandle = false;
        Throwable ex = null;
        String rname = findRequestName(request);
        HandlingCallback handlingCallback = new HandlingCallback(callback, request, response);

        try
        {
            thread.setName(name);
            String headers = _showHeaders ? ("\n" + request.getHeaders().toString()) : "";

            log(">> r=%s %s %s %s %s %s",
                rname,
                request.getMethod(),
                request.getHttpURI(),
                request.getConnectionMetaData().getProtocol(),
                request.getConnectionMetaData(),
                headers);

            willHandle = getHandler().handle(request, response, handlingCallback);
            return willHandle;
        }
        catch (Throwable x)
        {
            ex = x;
            throw x;
        }
        finally
        {
            if (!willHandle)
            {
                //Log that the request was not going to be handled
                log("!! r=%s not handled", rname);
            }
            else
            {
                handlingCallback.onHandlingCompleted(ex);
            }
        }
    }

    protected void log(String format, Object... arg)
    {
        if (!isRunning())
            return;

        String s = String.format(format, arg);

        String threadName = Thread.currentThread().getName();
        long now = System.currentTimeMillis();
        long ms = now % 1000;
        if (_print != null)
            _print.printf("%s.%03d:%s:%s%n", __date.format(now), ms, threadName, s);
    }

    protected String findRequestName(Request request)
    {
        if (request == null)
            return null;

        try
        {
            String n = (String)request.getAttribute(_attr);
            if (n == null)
            {
                n = String.format("%s@%x", request.getHttpURI(), request.hashCode());
                request.setAttribute(_attr, n);
            }
            return n;
        }
        catch (IllegalStateException e)
        {
            // Handle the case when the request has already been completed
            return String.format("%s@%x", request.getHttpURI(), request.hashCode());
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        if (_out == null)
            _out = new RolloverFileOutputStream("./logs/yyyy_mm_dd.debug.log", true);
        _print = new PrintStream(_out);

        for (Connector connector : getServer().getConnectors())
        {
            if (connector instanceof AbstractConnector)
                connector.addBean(this, false);
        }

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        _print.close();
        for (Connector connector : getServer().getConnectors())
        {
            if (connector instanceof AbstractConnector)
                connector.removeBean(this);
        }
    }

    /**
     * Get the out.
     *
     * @return the out
     */
    public OutputStream getOutputStream()
    {
        return _out;
    }

    /**
     * Set the out to set.
     *
     * @param out the out to set
     */
    public void setOutputStream(OutputStream out)
    {
        _out = out;
    }

    @Override
    public void onOpened(Connection connection)
    {
        log("%s OPENED %s", Thread.currentThread().getName(), connection.toString());
    }

    @Override
    public void onClosed(Connection connection)
    {
        log("%s CLOSED %s", Thread.currentThread().getName(), connection.toString());
    }
}
