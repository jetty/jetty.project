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
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.DateCache;
import org.eclipse.jetty.util.RolloverFileOutputStream;

/**
 * Debug Handler.
 * A lightweight debug handler that can be used in production code.
 * Details of the request and response are written to an output stream
 * and the current thread name is updated with information that will link
 * to the details in that output.
 */
public class DebugHandler extends HandlerWrapper implements Connection.Listener
{
    private DateCache _date = new DateCache("HH:mm:ss", Locale.US);
    private OutputStream _out;
    private PrintStream _print;

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        final Response base_response = baseRequest.getResponse();
        final Thread thread = Thread.currentThread();
        final String old_name = thread.getName();

        boolean retry = false;
        String name = (String)request.getAttribute("org.eclipse.jetty.thread.name");
        if (name == null)
            name = old_name + ":" + baseRequest.getOriginalURI();
        else
            retry = true;

        String ex = null;
        try
        {
            if (retry)
                print(name, "RESUME");
            else
                print(name, "REQUEST " + baseRequest.getRemoteAddr() + " " + request.getMethod() + " " + baseRequest.getHeader("Cookie") + "; " + baseRequest.getHeader("User-Agent"));
            thread.setName(name);

            getHandler().handle(target, baseRequest, request, response);
        }
        catch (IOException ioe)
        {
            ex = ioe.toString();
            throw ioe;
        }
        catch (ServletException servletEx)
        {
            ex = servletEx.toString() + ":" + servletEx.getCause();
            throw servletEx;
        }
        catch (RuntimeException rte)
        {
            ex = rte.toString();
            throw rte;
        }
        catch (Error e)
        {
            ex = e.toString();
            throw e;
        }
        finally
        {
            thread.setName(old_name);
            if (baseRequest.getHttpChannelState().isAsyncStarted())
            {
                request.setAttribute("org.eclipse.jetty.thread.name", name);
                print(name, "ASYNC");
            }
            else
                print(name, "RESPONSE " + base_response.getStatus() + (ex == null ? "" : ("/" + ex)) + " " + base_response.getContentType());
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
