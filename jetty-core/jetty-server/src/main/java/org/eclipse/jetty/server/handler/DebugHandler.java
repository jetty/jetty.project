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

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DateCache;
import org.eclipse.jetty.util.RolloverFileOutputStream;

/**
 * Debug Handler.
 * A lightweight debug handler that can be used in production code.
 * Details of the request and response are written to an output stream
 * and the current thread name is updated with information that will link
 * to the details in that output.
 */
public class DebugHandler extends Handler.Wrapper implements Connection.Listener
{
    private static final DateCache __date = new DateCache("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
    private OutputStream _out;
    private PrintStream _print;
    private boolean _showHeaders;
private final String _attr = String.format("__R%s@%x", this.getClass().getSimpleName(), System.identityHashCode(this));

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
        String name = thread.getName() + ":" + request.getHttpURI();

        String ex = null;
        String rname = findRequestName(request);
        try
        {
            String headers = _showHeaders ? ("\n" + request.getHeaders().toString()) : "";

            log(">> r=%s %s %s %s %s %s",
                rname,
                request.getMethod(),
                request.getHttpURI(),
                request.getConnectionMetaData().getProtocol(),
                request.getConnectionMetaData(),
                headers);
            thread.setName(name);

            return getHandler().handle(request, response, callback);
        }
        catch (Throwable x)
        {
            ex = x + ":" + x.getCause();
            throw x;
        }
        finally
        {
            // TODO this should be done in a completion event
            log("<< r=%s async=false %d%n%s", rname, response.getStatus(), response.getHeaders());
        }
    }

    protected void log(String format, Object... arg)
    {
        if (!isRunning())
            return;

        String s = String.format(format, arg);

        long now = System.currentTimeMillis();
        long ms = now % 1000;
        if (_print != null)
            _print.printf("%s.%03d:%s%n", __date.format(now), ms, s);
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
            // TODO can we avoid creating and catching this exception? see #8024
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
                ((AbstractConnector)connector).addBean(this, false);
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
                ((AbstractConnector)connector).removeBean(this);
        }
    }

    /**
     * Get the out.
     * @return the out
     */
    public OutputStream getOutputStream()
    {
        return _out;
    }

    /**
     * Set the out to set.
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
