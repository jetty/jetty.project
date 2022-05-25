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

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Locale;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.util.DateCache;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Context Listener that produces additional debug.
 * This listener if added to a ContextHandler, will produce additional debug information to
 * either/or a specific log stream or the standard debug log.
 * The events produced by {@link ServletContextListener}, {@link ServletRequestListener},
 * {@link AsyncListener} and {@link ContextHandler.ContextScopeListener} are logged.
 */
@ManagedObject("Debug Listener")
public class DebugListener extends AbstractLifeCycle implements ServletContextListener
{
    private static final Logger LOG = LoggerFactory.getLogger(DebugListener.class);
    private static final DateCache __date = new DateCache("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);

    private final String _attr = String.format("__R%s@%x", this.getClass().getSimpleName(), System.identityHashCode(this));

    private final PrintStream _out;
    private boolean _renameThread;
    private boolean _showHeaders;
    private boolean _dumpContext;

    public DebugListener()
    {
        this(null, false, false, false);
    }

    public DebugListener(@Name("renameThread") boolean renameThread, @Name("showHeaders") boolean showHeaders, @Name("dumpContext") boolean dumpContext)
    {
        this(null, renameThread, showHeaders, dumpContext);
    }

    public DebugListener(@Name("outputStream") OutputStream out, @Name("renameThread") boolean renameThread, @Name("showHeaders") boolean showHeaders, @Name("dumpContext") boolean dumpContext)
    {
        _out = out == null ? null : new PrintStream(out);
        _renameThread = renameThread;
        _showHeaders = showHeaders;
        _dumpContext = dumpContext;
    }

    @ManagedAttribute("Rename thread within context scope")
    public boolean isRenameThread()
    {
        return _renameThread;
    }

    public void setRenameThread(boolean renameThread)
    {
        _renameThread = renameThread;
    }

    @ManagedAttribute("Show request headers")
    public boolean isShowHeaders()
    {
        return _showHeaders;
    }

    public void setShowHeaders(boolean showHeaders)
    {
        _showHeaders = showHeaders;
    }

    @ManagedAttribute("Dump contexts at start")
    public boolean isDumpContext()
    {
        return _dumpContext;
    }

    public void setDumpContext(boolean dumpContext)
    {
        _dumpContext = dumpContext;
    }

    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        sce.getServletContext().addListener(_servletRequestListener);
        ContextHandler handler = ContextHandler.getContextHandler(sce.getServletContext());
        handler.addEventListener(_contextScopeListener);
        String cname = findContextName(sce.getServletContext());
        log("^  ctx=%s %s", cname, sce.getServletContext());
        if (_dumpContext)
        {
            if (_out == null)
            {
                handler.dumpStdErr();
                System.err.println(Dumpable.KEY);
            }
            else
            {
                try
                {
                    handler.dump(_out);
                    _out.println(Dumpable.KEY);
                }
                catch (Exception e)
                {
                    LOG.warn("Unable to dump {}", handler, e);
                }
            }
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
        String cname = findContextName(sce.getServletContext());
        log("v  ctx=%s %s", cname, sce.getServletContext());
    }

    protected String findContextName(ServletContext context)
    {
        if (context == null)
            return null;
        String n = (String)context.getAttribute(_attr);
        if (n == null)
        {
            n = String.format("%s@%x", context.getContextPath(), context.hashCode());
            context.setAttribute(_attr, n);
        }
        return n;
    }

    protected String findRequestName(ServletRequest request)
    {
        if (request == null)
            return null;
        HttpServletRequest r = (HttpServletRequest)request;
        try
        {
            String n = (String)request.getAttribute(_attr);
            if (n == null)
            {
                n = String.format("%s@%x", r.getRequestURI(), request.hashCode());
                request.setAttribute(_attr, n);
            }
            return n;
        }
        catch (IllegalStateException e)
        {
            // TODO can we avoid creating and catching this exception? see #8024
            // Handle the case when the request has already been completed
            return String.format("%s@%x", r.getRequestURI(), request.hashCode());
        }
    }

    protected void log(String format, Object... arg)
    {
        if (!isRunning())
            return;

        String s = String.format(format, arg);

        long now = System.currentTimeMillis();
        long ms = now % 1000;
        if (_out != null)
            _out.printf("%s.%03d:%s%n", __date.formatNow(now), ms, s);
        if (LOG.isDebugEnabled())
            LOG.debug(s);
    }

    final AsyncListener _asyncListener = new AsyncListener()
    {
        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
            String cname = findContextName(((AsyncContextEvent)event).getServletContext());
            String rname = findRequestName(event.getAsyncContext().getRequest());
            log("!  ctx=%s r=%s onTimeout %s", cname, rname, ((AsyncContextEvent)event).getHttpChannelState());
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
            String cname = findContextName(((AsyncContextEvent)event).getServletContext());
            String rname = findRequestName(event.getAsyncContext().getRequest());
            log("!  ctx=%s r=%s onStartAsync %s", cname, rname, ((AsyncContextEvent)event).getHttpChannelState());
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
            String cname = findContextName(((AsyncContextEvent)event).getServletContext());
            String rname = findRequestName(event.getAsyncContext().getRequest());
            log("!! ctx=%s r=%s onError %s %s", cname, rname, event.getThrowable(), ((AsyncContextEvent)event).getHttpChannelState());
        }

        @Override
        public void onComplete(AsyncEvent event) throws IOException
        {
            AsyncContextEvent ace = (AsyncContextEvent)event;
            String cname = findContextName(ace.getServletContext());
            String rname = findRequestName(ace.getAsyncContext().getRequest());

            Request br = Request.getBaseRequest(ace.getAsyncContext().getRequest());
            Response response = br.getResponse();
            String headers = _showHeaders ? ("\n" + response.getHttpFields().toString()) : "";

            log("!  ctx=%s r=%s onComplete %s %d%s", cname, rname, ace.getHttpChannelState(), response.getStatus(), headers);
        }
    };

    final ServletRequestListener _servletRequestListener = new ServletRequestListener()
    {
        @Override
        public void requestInitialized(ServletRequestEvent sre)
        {
            String cname = findContextName(sre.getServletContext());
            HttpServletRequest r = (HttpServletRequest)sre.getServletRequest();

            String rname = findRequestName(r);
            DispatcherType d = r.getDispatcherType();
            if (d == DispatcherType.REQUEST)
            {
                Request br = Request.getBaseRequest(r);

                String headers = _showHeaders ? ("\n" + br.getMetaData().getFields().toString()) : "";

                StringBuffer url = r.getRequestURL();
                if (r.getQueryString() != null)
                    url.append('?').append(r.getQueryString());
                log(">> %s ctx=%s r=%s %s %s %s %s %s%s", d,
                    cname,
                    rname,
                    d,
                    r.getMethod(),
                    url.toString(),
                    r.getProtocol(),
                    br.getHttpChannel(),
                    headers);
            }
            else
                log(">> %s ctx=%s r=%s", d, cname, rname);
        }

        @Override
        public void requestDestroyed(ServletRequestEvent sre)
        {
            String cname = findContextName(sre.getServletContext());
            HttpServletRequest r = (HttpServletRequest)sre.getServletRequest();
            String rname = findRequestName(r);
            DispatcherType d = r.getDispatcherType();
            if (sre.getServletRequest().isAsyncStarted())
            {
                sre.getServletRequest().getAsyncContext().addListener(_asyncListener);
                log("<< %s ctx=%s r=%s async=true", d, cname, rname);
            }
            else
            {
                Request br = Request.getBaseRequest(r);
                String headers = _showHeaders ? ("\n" + br.getResponse().getHttpFields().toString()) : "";
                log("<< %s ctx=%s r=%s async=false %d%s", d, cname, rname, Request.getBaseRequest(r).getResponse().getStatus(), headers);
            }
        }
    };

    final ContextHandler.ContextScopeListener _contextScopeListener = new ContextHandler.ContextScopeListener()
    {
        @Override
        public void enterScope(ContextHandler.APIContext context, Request request, Object reason)
        {
            String cname = findContextName(context);
            if (request == null)
                log(">  ctx=%s %s", cname, reason);
            else
            {
                String rname = findRequestName(request);

                if (_renameThread)
                {
                    Thread thread = Thread.currentThread();
                    thread.setName(String.format("%s#%s", thread.getName(), rname));
                }

                log(">  ctx=%s r=%s %s", cname, rname, reason);
            }
        }

        @Override
        public void exitScope(ContextHandler.APIContext context, Request request)
        {
            String cname = findContextName(context);
            if (request == null)
                log("<  ctx=%s", cname);
            else
            {
                String rname = findRequestName(request);

                log("<  ctx=%s r=%s", cname, rname);
                if (_renameThread)
                {
                    Thread thread = Thread.currentThread();
                    if (thread.getName().endsWith(rname))
                        thread.setName(thread.getName().substring(0, thread.getName().length() - rname.length() - 1));
                }
            }
        }
    };
}
