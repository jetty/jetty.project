//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContextHandler extends Handler.Wrapper implements Attributes
{
    private static final Logger LOG = LoggerFactory.getLogger(ContextHandler.class);
    private static final ThreadLocal<Context> __context = new ThreadLocal<>();

    /**
     * Get the current ServletContext implementation.
     *
     * @return ServletContext implementation
     */
    public static Context getCurrentContext()
    {
        return __context.get();
    }

    private final Attributes _persistentAttributes = new Mapped();
    private final Context _context = new Context();
    private final List<VHost> _vhosts = new ArrayList<>();

    private String _displayName;
    private String _contextPath = "/";
    private Path _resourceBase;
    private ClassLoader _contextLoader;

    public ContextHandler()
    {
        this("/");
    }

    public ContextHandler(String contextPath)
    {
        setContextPath(contextPath);
    }

    @ManagedAttribute(value = "Context")
    public Context getContext()
    {
        return _context;
    }

    /*
     * @see jakarta.servlet.ServletContext#getServletContextName()
     */
    @ManagedAttribute(value = "Display name of the Context")
    public String getDisplayName()
    {
        if (_displayName != null)
            return _displayName;
        if ("/".equals(_contextPath))
            return "ROOT";
        return _contextPath;
    }

    /**
     * @param servletContextName The servletContextName to set.
     */
    public void setDisplayName(String servletContextName)
    {
        _displayName = servletContextName;
    }

    @ManagedAttribute(value = "Context path of the Context")
    public String getContextPath()
    {
        return _contextPath;
    }

    public void setContextPath(String contextPath)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _contextPath = contextPath;
    }

    public Path getResourceBase()
    {
        return _resourceBase;
    }

    public void setResourceBase(Path resourceBase)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _resourceBase = resourceBase;
    }

    public ClassLoader getContextLoader()
    {
        return _contextLoader;
    }

    public void setContextLoader(ClassLoader contextLoader)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _contextLoader = contextLoader;
    }

    public List<String> getVirtualHosts()
    {
        return _vhosts.stream().map(VHost::getVHost).collect(Collectors.toList());
    }

    /**
     * Set the virtual hosts for the context. Only requests that have a matching host header or fully qualified URL will be passed to that context with a
     * virtual host name. A context with no virtual host names or a null virtual host name is available to all requests that are not served by a context with a
     * matching virtual host name.
     *
     * @param vhosts List of virtual hosts that this context responds to. A null/empty list means any hostname is acceptable. Host names may be String
     * representation of IP addresses. Host names may start with '*.' to wildcard one level of names. Hosts and wildcard hosts may be followed with
     * '@connectorname', in which case they will match only if the the {@link Connector#getName()}for the request also matches. If an entry is just
     * '@connectorname' it will match any host if that connector was used.
     */
    public void setVirtualHosts(List<String> vhosts)
    {
        if (isRunning())
            throw new IllegalStateException(getState());
        _vhosts.clear();
        if (vhosts != null && !vhosts.isEmpty())
        {
            for (String vhost : vhosts)
            {
                if (vhost == null)
                    continue;
                boolean wild = false;
                String connector = null;
                int at = vhost.indexOf('@');
                if (at >= 0)
                {
                    connector = vhost.substring(at + 1);
                    vhost = vhost.substring(0, at);
                }

                if (vhost.startsWith("*."))
                {
                    vhost = vhost.substring(1);
                    wild = true;
                }
                _vhosts.add(new VHost(vhost, wild, connector));
            }
        }
    }

    private static class VHost
    {
        private final String _vHost;
        private final boolean _wild;
        private final String _vConnector;

        private VHost(String vHost, boolean wild, String vConnector)
        {
            // TODO there was a normalize step done previously, but it looks like
            //      duplicate work. Needs review.
            _vHost = vHost;
            _wild = wild;
            _vConnector = vConnector;
        }

        String getVHost()
        {
            return _vHost;
        }
    }

    public boolean checkVirtualHost(Request request)
    {
        if (_vhosts.isEmpty())
            return true;

        // TODO is this correct?
        String host = request.getHttpURI().getHost();

        String connectorName = request.getChannel().getMetaConnection().getConnector().getName();

        for (VHost vhost : _vhosts)
        {
            String contextVhost = vhost._vHost;
            String contextVConnector = vhost._vConnector;

            if (contextVConnector != null)
            {
                if (!contextVConnector.equalsIgnoreCase(connectorName))
                    continue;

                if (contextVhost == null)
                {
                    return true;
                }
            }

            if (contextVhost != null)
            {
                if (vhost._wild)
                {
                    // wildcard only at the beginning, and only for one additional subdomain level
                    int index = host.indexOf(".");
                    if (index >= 0 && host.substring(index).equalsIgnoreCase(contextVhost))
                    {
                        return true;
                    }
                }
                else if (host.equalsIgnoreCase(contextVhost))
                {
                    return true;
                }
            }
        }
        return false;
    }

    protected String getPathInContext(Request request)
    {
        String path = request.getHttpURI().getPath();
        if (!path.startsWith(_context.getContextPath()))
            return null;
        if ("/".equals(_context.getContextPath()))
            return path;
        if (path.length() == _context.getContextPath().length())
            return "/";
        if (path.charAt(_context.getContextPath().length()) != '/')
            return null;
        return path.substring(_context.getContextPath().length());
    }

    @Override
    protected void doStart() throws Exception
    {
        _context.call(super::doStart);
    }

    @Override
    protected void doStop() throws Exception
    {
        _context.call(super::doStop);
    }

    @Override
    public void destroy()
    {
        _context.run(super::destroy);
    }

    public boolean isAvailable()
    {
        return isStarted(); // TODO
    }

    @Override
    public boolean handle(Request request, Response response) throws Exception
    {
        Handler next = getHandler();
        if (next == null)
            return false;

        if (!checkVirtualHost(request))
            return false;

        String pathInContext = getPathInContext(request);
        if (pathInContext == null)
            return false;

        if (pathInContext.isEmpty())
        {
            String location = _contextPath + "/";
            if (request.getHttpURI().getParam() != null)
                location += ";" + request.getHttpURI().getParam();
            if (request.getHttpURI().getQuery() != null)
                location += ";" + request.getHttpURI().getQuery();
            response.setStatus(HttpStatus.MOVED_PERMANENTLY_301);
            response.getHeaders().add(new HttpField(HttpHeader.LOCATION, location));
            request.succeeded();
            return true;
        }

        // TODO check availability and maybe return a 503

        ContextRequest scoped = wrap(request, response, pathInContext);
        if (scoped == null)
            return false; // TODO 404? 500? Error dispatch ???

        // TODO make the lambda part of the scope request to save allocation?
        _context.call(() -> next.handle(scoped, new ScopedResponse(response)));
        return true;
    }

    protected ContextRequest wrap(Request request, Response response, String pathInContext)
    {
        return new ContextRequest(_context, request, pathInContext);
    }

    @Override
    public Object setAttribute(String name, Object attribute)
    {
        return _persistentAttributes.setAttribute(name, attribute);
    }

    @Override
    public Object getAttribute(String name)
    {
        return _persistentAttributes.getAttribute(name);
    }

    @Override
    public Set<String> getAttributeNames()
    {
        return _persistentAttributes.getAttributeNames();
    }

    @Override
    public Object removeAttribute(String name)
    {
        return _persistentAttributes.removeAttribute(name);
    }

    @Override
    public void clearAttributes()
    {
        _persistentAttributes.clearAttributes();
    }

    public class Context extends Attributes.Layer
    {
        public Context()
        {
            super(_persistentAttributes);
        }

        @SuppressWarnings("unchecked")
        public <H extends ContextHandler> H getContextHandler()
        {
            return (H)ContextHandler.this;
        }

        public String getContextPath()
        {
            return _contextPath;
        }

        public ClassLoader getClassLoader()
        {
            return _contextLoader;
        }

        public Path getResourceBase()
        {
            return _resourceBase;
        }

        public void call(Invocable.Task task) throws Exception
        {
            Context lastContext = __context.get();
            if (lastContext == this)
                task.run();
            else
            {
                ClassLoader loader = getClassLoader();
                ClassLoader lastLoader = Thread.currentThread().getContextClassLoader();
                try
                {
                    __context.set(this);
                    if (loader != null)
                        Thread.currentThread().setContextClassLoader(loader);

                    task.run();
                }
                finally
                {
                    __context.set(lastContext);
                    if (loader != null)
                        Thread.currentThread().setContextClassLoader(lastLoader);
                }
            }
        }

        public void accept(Consumer<Throwable> consumer, Throwable t)
        {
            Context lastContext = __context.get();
            if (lastContext == this)
                consumer.accept(t);
            else
            {
                ClassLoader loader = getClassLoader();
                ClassLoader lastLoader = Thread.currentThread().getContextClassLoader();
                try
                {
                    __context.set(this);
                    if (loader != null)
                        Thread.currentThread().setContextClassLoader(loader);

                    consumer.accept(t);
                }
                finally
                {
                    __context.set(lastContext);
                    if (loader != null)
                        Thread.currentThread().setContextClassLoader(lastLoader);
                }
            }
        }

        public void run(Runnable task)
        {
            try
            {
                call(task::run);
            }
            catch (Exception e)
            {
                LOG.warn("Failed to run in {}", _displayName, e);
                throw new RuntimeException(e);
            }
        }
    }

    private class ScopedResponse extends Response.Wrapper
    {
        public ScopedResponse(Response response)
        {
            super(response);
        }

        @Override
        public void write(boolean last, Callback callback, ByteBuffer... content)
        {
            Callback contextCallback = new Callback()
            {
                @Override
                public void succeeded()
                {
                    _context.run(callback::succeeded);
                }

                @Override
                public void failed(Throwable t)
                {
                    _context.accept(callback::failed, t);
                }
            };
            super.write(last, contextCallback, content);
        }
    }
}
