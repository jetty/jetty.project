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

package org.eclipse.jetty.core.server.handler;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EventListener;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.eclipse.jetty.core.server.ClassLoaderDump;
import org.eclipse.jetty.core.server.Connector;
import org.eclipse.jetty.core.server.Context;
import org.eclipse.jetty.core.server.Handler;
import org.eclipse.jetty.core.server.Request;
import org.eclipse.jetty.core.server.Response;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContextHandler extends Handler.Wrapper implements Attributes, Graceful
{
    // TODO where should the alias checking go?
    // TODO add protected paths to ServletContextHandler?
    // TODO what about ObjectFactory stuff
    // TODO what about a Context logger?
    // TODO init param stuff to ServletContextHandler

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

    // TODO should persistent attributes be an Attributes.Layer over server attributes?
    private final Attributes _persistentAttributes = new Mapped();
    private final ScopedContext _context = new ScopedContext();
    private final List<ContextScopeListener> _contextListeners = new CopyOnWriteArrayList<>();
    private final List<VHost> _vhosts = new ArrayList<>();

    private String _displayName;
    private String _contextPath = "/";
    private Path _resourceBase;
    private ClassLoader _classLoader;
    private Request.Processor _errorProcessor;
    private boolean _allowNullPathInfo;

    public ContextHandler()
    {
        this("/");
    }

    public ContextHandler(String contextPath)
    {
        setContextPath(contextPath);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent,
            new ClassLoaderDump(getClassLoader()),
            Dumpable.named("context " + this, _context),
            Dumpable.named("handler attributes " + this, _persistentAttributes));
    }

    @ManagedAttribute(value = "Context")
    public Context getContext()
    {
        return _context;
    }

    /**
     * @return the allowNullPathInfo true if /context is not redirected to /context/
     */
    @ManagedAttribute("Checks if the /context is not redirected to /context/")
    public boolean getAllowNullPathInfo()
    {
        return _allowNullPathInfo;
    }

    /**
     * @param allowNullPathInfo true if /context is not redirected to /context/
     */
    public void setAllowNullPathInfo(boolean allowNullPathInfo)
    {
        _allowNullPathInfo = allowNullPathInfo;
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
                _vhosts.add(new VHost(normalizeHostname(vhost), wild, connector));
            }
        }
    }

    /**
     * Either set virtual hosts or add to an existing set of virtual hosts.
     *
     * @param virtualHosts Array of virtual hosts that this context responds to. A null/empty array means any hostname is acceptable. Host names may be String
     * representation of IP addresses. Host names may start with '*.' to wildcard one level of names. Hosts and wildcard hosts may be followed with
     * '@connectorname', in which case they will match only if the the {@link Connector#getName()} for the request also matches. If an entry is just
     * '@connectorname' it will match any host if that connector was used.  Note - In previous versions if one or more connectorname only entries existed
     * and non of the connectors matched the handler would not match regardless of any hostname entries.  If there is one or more connectorname only
     * entries and one or more host only entries but no hostname and connector entries we assume the old behavior and will log a warning.  The warning
     * can be removed by removing the host entries that were previously being ignored, or modifying to include a hostname and connectorname entry.
     */
    public void addVirtualHosts(String[] virtualHosts)
    {
        if (virtualHosts == null || virtualHosts.length == 0) // since this is add, we don't null the old ones
            return;

        List<String> vhosts = new ArrayList<>(getVirtualHosts());
        vhosts.addAll(Arrays.asList(virtualHosts));
        setVirtualHosts(vhosts);
    }

    /**
     * Removes an array of virtual host entries, if this removes all entries the _vhosts will be set to null
     *
     * @param virtualHosts Array of virtual hosts that this context responds to. A null/empty array means any hostname is acceptable. Host names may be String
     * representation of IP addresses. Host names may start with '*.' to wildcard one level of names. Hosts and wildcard hosts may be followed with
     * '@connectorname', in which case they will match only if the the {@link Connector#getName()} for the request also matches. If an entry is just
     * '@connectorname' it will match any host if that connector was used.  Note - In previous versions if one or more connectorname only entries existed
     * and non of the connectors matched the handler would not match regardless of any hostname entries.  If there is one or more connectorname only
     * entries and one or more host only entries but no hostname and connector entries we assume the old behavior and will log a warning.  The warning
     * can be removed by removing the host entries that were previously being ignored, or modifying to include a hostname and connectorname entry.
     */
    public void removeVirtualHosts(String[] virtualHosts)
    {
        List<String> vhosts = new ArrayList<>(getVirtualHosts());
        if (virtualHosts == null || virtualHosts.length == 0 || vhosts.isEmpty())
            return; // do nothing

        for (String vh : virtualHosts)
            vhosts.remove(normalizeHostname(vh));
    }

    /**
     * Get the virtual hosts for the context. Only requests that have a matching host header or fully qualified URL will be passed to that context with a
     * virtual host name. A context with no virtual host names or a null virtual host name is available to all requests that are not served by a context with a
     * matching virtual host name.
     *
     * @return Array of virtual hosts that this context responds to. A null/empty array means any hostname is acceptable. Host names may be String
     * representation of IP addresses. Host names may start with '*.' to wildcard one level of names. Hosts and wildcard hosts may be followed with
     * '@connectorname', in which case they will match only if the the {@link Connector#getName()} for the request also matches. If an entry is just
     * '@connectorname' it will match any host if that connector was used.  Note - In previous versions if one or more connectorname only entries existed
     * and non of the connectors matched the handler would not match regardless of any hostname entries.  If there is one or more connectorname only
     * entries and one or more host only entries but no hostname and connector entries we assume the old behavior and will log a warning.  The warning
     * can be removed by removing the host entries that were previously being ignored, or modifying to include a hostname and connectorname entry.
     */
    @ManagedAttribute(value = "Virtual hosts accepted by the context", readonly = true)
    public List<String> getVirtualHosts()
    {
        return _vhosts.stream().map(VHost::getVHost).collect(Collectors.toList());
    }

    @Override
    public Object getAttribute(String name)
    {
        return _persistentAttributes.getAttribute(name);
    }

    @Override
    public Set<String> getAttributeNamesSet()
    {
        return _persistentAttributes.getAttributeNamesSet();
    }

    @Override
    public Object setAttribute(String name, Object attribute)
    {
        return _persistentAttributes.setAttribute(name, attribute);
    }

    @Override
    public Object removeAttribute(String name)
    {
        return _persistentAttributes.removeAttribute(name);
    }

    public ClassLoader getClassLoader()
    {
        return _classLoader;
    }

    public void setClassLoader(ClassLoader contextLoader)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _classLoader = contextLoader;
    }

    /**
     * Make best effort to extract a file classpath from the context classloader
     *
     * @return Returns the classLoader.
     */
    @ManagedAttribute("The file classpath")
    public String getClassPath()
    {
        // TODO may need to handle one level of parent classloader for API ?
        if (_classLoader == null || !(_classLoader instanceof URLClassLoader))
            return null;
        URLClassLoader loader = (URLClassLoader)_classLoader;
        URL[] urls = loader.getURLs();
        StringBuilder classpath = new StringBuilder();
        for (int i = 0; i < urls.length; i++)
        {
            URL url = urls[i];
            try
            {
                // TODO do this without Resource?
                Resource resource = Resource.newResource(url);
                File file = resource.getFile();
                if (file != null && file.exists())
                {
                    if (classpath.length() > 0)
                        classpath.append(File.pathSeparatorChar);
                    classpath.append(file.getAbsolutePath());
                }
            }
            catch (IOException e)
            {
                LOG.debug("Could not found resource: {}", url, e);
            }
        }
        if (classpath.length() == 0)
            return null;
        return classpath.toString();
    }

    /**
     * @return Returns the contextPath.
     */
    @ManagedAttribute(value = "Context path of the Context")
    public String getContextPath()
    {
        return _contextPath;
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
     * Add a context event listeners.
     *
     * @param listener the event listener to add
     * @return true if the listener was added
     * @see ContextScopeListener
     * @see org.eclipse.jetty.util.component.ContainerLifeCycle#addEventListener(EventListener) 
     */
    @Override
    public boolean addEventListener(EventListener listener)
    {
        if (super.addEventListener(listener))
        {
            if (listener instanceof ContextScopeListener)
            {
                _contextListeners.add((ContextScopeListener)listener);
                if (__context.get() != null)
                    ((ContextScopeListener)listener).enterScope(__context.get(), null);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean removeEventListener(EventListener listener)
    {
        if (super.removeEventListener(listener))
        {
            if (listener instanceof ContextScopeListener)
                _contextListeners.remove(listener);

            return true;
        }
        return false;
    }

    /**
     * @param request A request that is applicable to the scope, or null
     *
     */
    protected void enterScope(Request request)
    {
        for (ContextScopeListener listener : _contextListeners)
        {
            try
            {
                listener.enterScope(_context, request);
            }
            catch (Throwable e)
            {
                LOG.warn("Unable to enter scope", e);
            }
        }
    }

    /**
     * @param request A request that is applicable to the scope, or null
     */
    protected void exitScope(Request request)
    {
        for (int i = _contextListeners.size(); i-- > 0; )
        {
            try
            {
                _contextListeners.get(i).exitScope(_context, request);
            }
            catch (Throwable e)
            {
                LOG.warn("Unable to exit scope", e);
            }
        }
    }

    /**
     * @return true if this context is shutting down
     */
    @ManagedAttribute("true for graceful shutdown, which allows existing requests to complete")
    public boolean isShutdown()
    {
        // TODO
        return false;
    }

    /**
     * Set shutdown status. This field allows for graceful shutdown of a context. A started context may be put into non accepting state so that existing
     * requests can complete, but no new requests are accepted.
     */
    @Override
    public CompletableFuture<Void> shutdown()
    {
        // TODO
        return null;
    }

    /**
     * @return false if this context is unavailable (sends 503)
     */
    public boolean isAvailable()
    {
        // TODO
        return isStarted();
    }

    /**
     * Set Available status.
     *
     * @param available true to set as enabled
     */
    public void setAvailable(boolean available)
    {
        // TODO
    }

    @Override
    protected void doStart() throws Exception
    {
        // TODO lots of stuff in previous doStart. Some might go here, but most probably goes to the ServletContentHandler ?
        _context.call(super::doStart, null);
    }

    @Override
    protected void doStop() throws Exception
    {
        // TODO lots of stuff in previous doStart. Some might go here, but most probably goes to the ServletContentHandler ?
        _context.call(super::doStop, null);
    }

    public boolean checkVirtualHost(Request request)
    {
        if (_vhosts.isEmpty())
            return true;

        // TODO is this correct?
        String host = request.getHttpURI().getHost();

        String connectorName = request.getConnectionMetaData().getConnector().getName();

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
    public void destroy()
    {
        _context.run(super::destroy);
    }

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        if (getHandler() == null)
            return null;

        if (!checkVirtualHost(request))
            return null;

        String pathInContext = getPathInContext(request);
        if (pathInContext == null)
            return null;

        if (pathInContext.isEmpty() && !getAllowNullPathInfo())
            return this::processMovedPermanently;

        // TODO check availability and maybe return a 503

        ContextRequest scoped = wrap(request, pathInContext);
        // wrap might fail (eg ServletContextHandler could not match a servlet)
        if (scoped == null)
            return null;

        return scoped.wrapProcessor(_context.get(scoped, scoped));
    }

    void processMovedPermanently(Request request, Response response, Callback callback)
    {
        String location = _contextPath + "/";
        if (request.getHttpURI().getParam() != null)
            location += ";" + request.getHttpURI().getParam();
        if (request.getHttpURI().getQuery() != null)
            location += ";" + request.getHttpURI().getQuery();

        response.setStatus(HttpStatus.MOVED_PERMANENTLY_301);
        response.getHeaders().add(new HttpField(HttpHeader.LOCATION, location));
        callback.succeeded();
    }

    /**
     * @param contextPath The _contextPath to set.
     */
    public void setContextPath(String contextPath)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _contextPath = contextPath;
    }

    /**
     * @param servletContextName The servletContextName to set.
     */
    public void setDisplayName(String servletContextName)
    {
        _displayName = servletContextName;
    }

    /**
     * @return Returns the base resource as a string.
     */
    @ManagedAttribute("document root for context")
    public Path getResourceBase()
    {
        return _resourceBase;
    }

    /**
     * Set the base resource for this context.
     *
     * @param resourceBase The Path of the base resource for the context.
     */
    public void setResourceBase(Path resourceBase)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _resourceBase = resourceBase;
    }

    /**
     * @return Returns the errorHandler.
     */
    @ManagedAttribute("The error processor to use for the context")
    public Request.Processor getErrorProcessor()
    {
        // TODO, do we need to wrap this so that we can establish the context
        //       Classloader?  Or will the caller already do that?
        return _errorProcessor;
    }

    /**
     * @param errorProcessor The error processor to set.
     */
    public void setErrorProcessor(Request.Processor errorProcessor)
    {
        updateBean(_errorProcessor, errorProcessor, true);
        _errorProcessor = errorProcessor;
    }

    protected ContextRequest wrap(Request request, String pathInContext)
    {
        return new ContextRequest(this, _context, request, pathInContext);
    }

    @Override
    public void clearAttributes()
    {
        _persistentAttributes.clearAttributes();
    }

    @Override
    public String toString()
    {
        List<String> vhosts = getVirtualHosts();
        StringBuilder b = new StringBuilder();
        Package pkg = getClass().getPackage();
        if (pkg != null)
        {
            String p = pkg.getName();
            if (StringUtil.isNotBlank(p))
            {
                String[] ss = p.split("\\.");
                for (String s : ss)
                {
                    b.append(s.charAt(0)).append('.');
                }
            }
        }
        b.append(getClass().getSimpleName()).append('@').append(Integer.toString(hashCode(), 16));
        b.append('{');
        if (getDisplayName() != null)
            b.append(getDisplayName()).append(',');
        b.append(getContextPath()).append(',').append(getResourceBase()).append(',').append(isAvailable());

        for (String vh : vhosts)
            b.append(',').append(vh);
        b.append('}');

        return b.toString();
    }

    private String normalizeHostname(String host)
    {
        // TODO is this needed? if so, should be it somewhere eles?
        if (host == null)
            return null;
        int connectorIndex = host.indexOf('@');
        String connector = null;
        if (connectorIndex > 0)
        {
            host = host.substring(0, connectorIndex);
            connector = host.substring(connectorIndex);
        }

        if (host.endsWith("."))
            host = host.substring(0, host.length() - 1);
        if (connector != null)
            host += connector;

        return host;
    }

    public class ScopedContext extends Attributes.Layer implements Context
    {
        public ScopedContext()
        {
            // TODO Should the ScopedContext attributes be a layer over the ServerContext attributes?
            super(_persistentAttributes);
        }

        @SuppressWarnings("unchecked")
        public <H extends ContextHandler> H getContextHandler()
        {
            return (H)ContextHandler.this;
        }

        @Override
        public Object getAttribute(String name)
        {
            // TODO the Attributes.Layer is a little different to previous
            //      behaviour.  We need to verify if that is OK
            return super.getAttribute(name);
        }

        @Override
        public Request.Processor getErrorProcessor()
        {
            return ContextHandler.this.getErrorProcessor();
        }

        @Override
        public String getContextPath()
        {
            return _contextPath;
        }

        @Override
        public String toString()
        {
            return "%s@%x".formatted(getClass().getSimpleName(), hashCode());
        }

        @Override
        public ClassLoader getClassLoader()
        {
            return _classLoader;
        }

        @Override
        public Path getResourceBase()
        {
            return _resourceBase;
        }

        private <T> T get(Supplier<T> supplier, Request request)
        {
            Context lastContext = __context.get();
            if (lastContext == this)
                return supplier.get();

            ClassLoader loader = getClassLoader();
            ClassLoader lastLoader = Thread.currentThread().getContextClassLoader();
            try
            {
                __context.set(this);
                if (loader != null)
                    Thread.currentThread().setContextClassLoader(loader);

                enterScope(request);
                return supplier.get();
            }
            finally
            {
                exitScope(request);
                __context.set(lastContext);
                if (loader != null)
                    Thread.currentThread().setContextClassLoader(lastLoader);
            }
        }

        void call(Invocable.Callable callable, Request request) throws Exception
        {
            Context lastContext = __context.get();
            if (lastContext == this)
                callable.call();
            else
            {
                ClassLoader loader = getClassLoader();
                ClassLoader lastLoader = Thread.currentThread().getContextClassLoader();
                try
                {
                    __context.set(this);
                    if (loader != null)
                        Thread.currentThread().setContextClassLoader(loader);

                    enterScope(request);
                    callable.call();
                }
                finally
                {
                    exitScope(request);
                    __context.set(lastContext);
                    if (loader != null)
                        Thread.currentThread().setContextClassLoader(lastLoader);
                }
            }
        }

        void accept(Consumer<Throwable> consumer, Throwable t, Request request)
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
                    enterScope(request);
                    consumer.accept(t);
                }
                finally
                {
                    exitScope(request);
                    __context.set(lastContext);
                    if (loader != null)
                        Thread.currentThread().setContextClassLoader(lastLoader);
                }
            }
        }

        @Override
        public void run(Runnable runnable)
        {
            run(runnable, null);
        }

        void run(Runnable runnable, Request request)
        {
            try
            {
                Context lastContext = __context.get();
                if (lastContext == this)
                    runnable.run();
                else
                {
                    ClassLoader loader = getClassLoader();
                    ClassLoader lastLoader = Thread.currentThread().getContextClassLoader();
                    try
                    {
                        __context.set(this);
                        if (loader != null)
                            Thread.currentThread().setContextClassLoader(loader);
                        enterScope(request);
                        runnable.run();
                    }
                    finally
                    {
                        exitScope(request);
                        __context.set(lastContext);
                        if (loader != null)
                            Thread.currentThread().setContextClassLoader(lastLoader);
                    }
                }
            }
            catch (Exception e)
            {
                LOG.warn("Failed to run in {}", _displayName, e);
                throw new RuntimeException(e);
            }
        }

        @Override
        public void execute(Runnable runnable)
        {
            getServer().getContext().execute(() -> run(runnable));
        }

        @Override
        public <T> T decorate(T o)
        {
            // TODO cache factory lookup?
            DecoratedObjectFactory factory = ContextHandler.this.getBean(DecoratedObjectFactory.class);
            if (factory != null)
                return factory.decorate(o);
            factory = getServer().getBean(DecoratedObjectFactory.class);
            if (factory != null)
                return factory.decorate(o);
            return o;
        }

        @Override
        public void destroy(Object o)
        {
            // TODO cache factory lookup?
            DecoratedObjectFactory factory = ContextHandler.this.getBean(DecoratedObjectFactory.class);
            if (factory == null)
                factory = getServer().getBean(DecoratedObjectFactory.class);
            if (factory != null)
                factory.destroy(o);
        }
    }

    /**
     * Listener for all threads entering context scope, including async IO callbacks
     */
    public interface ContextScopeListener extends EventListener
    {
        /**
         * @param context The context being entered
         * @param request A request that is applicable to the scope, or null
         */
        default void enterScope(Context context, Request request) {}

        /**
         * @param context The context being exited
         * @param request A request that is applicable to the scope, or null
         */
        default void exitScope(Context context, Request request) {}
    }

    private static class VHost
    {
        private final String _vHost;
        private final boolean _wild;
        private final String _vConnector;

        private VHost(String vHost, boolean wild, String vConnector)
        {
            _vHost = vHost;
            _wild = wild;
            _vConnector = vConnector;
        }

        String getVHost()
        {
            return _vHost;
        }
    }
}
