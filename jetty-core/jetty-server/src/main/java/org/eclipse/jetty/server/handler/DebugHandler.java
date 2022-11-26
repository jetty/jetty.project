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

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Locale;

import org.eclipse.jetty.http.HttpHeader;
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
 * A lightweight debug handler that can be used in production code.
 * Details of the request and response are written to an output stream
 * and the current thread name is updated with information that will link
 * to the details in that output.
 */
public class DebugHandler extends Handler.Wrapper implements Connection.Listener
{
    private final DateCache _date = new DateCache("HH:mm:ss", Locale.US);
    private OutputStream _out;
    private PrintStream _print;

    @Override
    public void process(Request request, Response response, Callback callback) throws Exception
    {
        final Thread thread = Thread.currentThread();
        final String old_name = thread.getName();

        String name = old_name + ":" + request.getHttpURI();

        String ex = null;
        try
        {
            print(name, "REQUEST " + Request.getRemoteAddr(request) +
                " " + request.getMethod() +
                " " + request.getHeaders().get("Cookie") +
                "; " + request.getHeaders().get("User-Agent"));
            thread.setName(name);

            getHandler().process(request, response, callback);
        }
        catch (Throwable x)
        {
            ex = x + ":" + x.getCause();
            throw x;
        }
        finally
        {
            // TODO this should be done in a completion event
            print(name, "RESPONSE " + response.getStatus() + (ex == null ? "" : ("/" + ex)) + " " + response.getHeaders().get(HttpHeader.CONTENT_TYPE));
        }
    }

    private void print(String name, String message)
    {
        long now = System.currentTimeMillis();
        final String d = _date.formatNow(now);
        final int ms = (int)(now % 1000);

        _print.println(d + (ms > 99 ? "." : (ms > 9 ? ".0" : ".00")) + ms + ":" + name + " " + message);
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
     * @return the out
     */
    public OutputStream getOutputStream()
    {
        return _out;
    }

    /**
     * @param out the out to set
     */
    public void setOutputStream(OutputStream out)
    {
        _out = out;
    }

    @Override
    public void onOpened(Connection connection)
    {
        print(Thread.currentThread().getName(), "OPENED " + connection.toString());
    }

    @Override
    public void onClosed(Connection connection)
    {
        print(Thread.currentThread().getName(), "CLOSED " + connection.toString());
    }
}
