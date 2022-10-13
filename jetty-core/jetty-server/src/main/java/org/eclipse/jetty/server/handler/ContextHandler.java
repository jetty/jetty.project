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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EventListener;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.AliasCheck;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SymlinkAllowedResourceAliasChecker;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.component.ClassLoaderDump;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContextHandler extends Handler.Wrapper implements Attributes, Graceful, AliasCheck
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

    public static String getServerInfo()
    {
        return "jetty/" + Server.getVersion();
    }

    // TODO should persistent attributes be an Attributes.Layer over server attributes?
    private final Attributes _persistentAttributes = new Mapped();
    private final Context _context;
    private final List<ContextScopeListener> _contextListeners = new CopyOnWriteArrayList<>();
    private final List<VHost> _vhosts = new ArrayList<>();

    private String _displayName;
    private String _contextPath = "/";
    private Resource _baseResource;
    private ClassLoader _classLoader;
    private Request.Processor _errorProcessor;
    private boolean _allowNullPathInContext;
    private Index<ProtectedTargetType> _protectedTargets = Index.empty(false);
    private final List<AliasCheck> _aliasChecks = new CopyOnWriteArrayList<>();

    public enum Availability
    {
        STOPPED,        // stopped and can't be made unavailable nor shutdown
        STARTING,       // starting inside of doStart. It may go to any of the next states.
        AVAILABLE,      // running normally
        UNAVAILABLE,    // Either a startup error or explicit call to setAvailable(false)
        SHUTDOWN,       // graceful shutdown
    }

    /**
     * The type of protected target match
     * @see #_protectedTargets
     */
    private enum ProtectedTargetType
    {
        EXACT,
        PREFIX
    }

    public static ContextHandler getContextHandler(Request request)
    {
        ContextRequest contextRequest = Request.as(request, ContextRequest.class);
        return (contextRequest == null) ? null : contextRequest.getContext().getContextHandler();
    }

    private final AtomicReference<Availability> _availability = new AtomicReference<>(Availability.STOPPED);

    public ContextHandler()
    {
        this(null);
    }

    public ContextHandler(String contextPath)
    {
        this(null, contextPath);
    }

    @Deprecated
    public ContextHandler(Handler.Container parent, String contextPath)
    {
        _context = newContext();
        if (contextPath != null)
            setContextPath(contextPath);
        if (parent != null)
            parent.addHandler(this);

        if (File.separatorChar == '/')
            addAliasCheck(new SymlinkAllowedResourceAliasChecker(this));
    }

    protected Context newContext()
    {
        return new Context();
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
    public ContextHandler.Context getContext()
    {
        return _context;
    }

    /**
     * @return the allowNullPathInfo true if /context is not redirected to /context/
     */
    @ManagedAttribute("Checks if the /context is not redirected to /context/")
    public boolean getAllowNullPathInContext()
    {
        return _allowNullPathInContext;
    }

    /**
     * @param allowNullPathInContext true if /context is not redirected to /context/
     */
    public void setAllowNullPathInContext(boolean allowNullPathInContext)
    {
        _allowNullPathInContext = allowNullPathInContext;
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
    public void addVirtualHosts(String... virtualHosts)
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
    public void removeVirtualHosts(String... virtualHosts)
    {
        List<String> vhosts = new ArrayList<>(getVirtualHosts());
        if (virtualHosts == null || virtualHosts.length == 0 || vhosts.isEmpty())
            return; // do nothing

        for (String vh : virtualHosts)
        {
            vhosts.remove(normalizeHostname(vh));
        }
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
     * and none of the connectors matched the handler would not match regardless of any hostname entries.  If there is one or more connectorname only
     * entries and one or more host only entries but no hostname and connector entries we assume the old behavior and will log a warning.  The warning
     * can be removed by removing the host entries that were previously being ignored, or modifying to include a hostname and connectorname entry.
     */
    @ManagedAttribute(value = "Virtual hosts accepted by the context", readonly = true)
    public List<String> getVirtualHosts()
    {
        return _vhosts.stream().map(VHost::getName).collect(Collectors.toList());
    }

    @Override
    public Object getAttribute(String name)
    {
        return _persistentAttributes.getAttribute(name);
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        return _persistentAttributes.getAttributeNameSet();
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
        if (_classLoader == null || !(_classLoader instanceof URLClassLoader loader))
            return null;

        String classpath = URIUtil.streamOf(loader)
            .map(URI::toASCIIString)
            .collect(Collectors.joining(File.pathSeparator));
        if (StringUtil.isBlank(classpath))
            return null;
        return classpath;
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
        CompletableFuture<Void> completableFuture = new CompletableFuture<>();
        completableFuture.complete(null);
        return completableFuture;
    }

    /**
     * @return false if this context is unavailable (sends 503)
     */
    public boolean isAvailable()
    {
        return _availability.get() == Availability.AVAILABLE && isStarted();
    }

    /**
     * Set Available status.
     *
     * @param available true to set as enabled
     */
    public void setAvailable(boolean available)
    {
        // Only supported state transitions are:
        //   UNAVAILABLE --true---> AVAILABLE
        //   STARTING -----false--> UNAVAILABLE
        //   AVAILABLE ----false--> UNAVAILABLE
        if (available)
        {
            while (true)
            {
                Availability availability = _availability.get();
                switch (availability)
                {
                    case AVAILABLE:
                        break;
                    case UNAVAILABLE:
                        if (!_availability.compareAndSet(availability, Availability.AVAILABLE))
                            continue;
                        break;
                    default:
                        throw new IllegalStateException(availability.toString());
                }
                break;
            }
        }
        else
        {
            while (true)
            {
                Availability availability = _availability.get();
                switch (availability)
                {
                    case STARTING:
                    case AVAILABLE:
                        if (!_availability.compareAndSet(availability, Availability.UNAVAILABLE))
                            continue;
                        break;
                    default:
                        break;
                }
                break;
            }
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        if (getContextPath() == null)
            throw new IllegalStateException("Null contextPath");

        _availability.set(Availability.STARTING);
        try
        {
            _context.call(super::doStart, null);
            _availability.compareAndSet(Availability.STARTING, Availability.AVAILABLE);
            LOG.info("Started {}", this);
        }
        finally
        {
            _availability.compareAndSet(Availability.STARTING, Availability.UNAVAILABLE);
        }
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

        String host = normalizeHostname(request.getHttpURI().getHost());
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
        String path = request.getPathInContext();
        if (!path.startsWith(_context.getContextPath()))
            return null;
        if ("/".equals(_context.getContextPath()))
            return path;
        if (path.length() == _context.getContextPath().length())
            return "";
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

        if (pathInContext.isEmpty() && !getAllowNullPathInContext())
            return this::processMovedPermanently;

        // TODO check availability and maybe return a 503
        if (!isAvailable() && isStarted())
            return this::processUnavailable;

        ContextRequest contextRequest = wrap(request, pathInContext);
        // wrap might fail (eg ServletContextHandler could not match a servlet)
        if (contextRequest == null)
            return null;

        Request.Processor processor = processByContextHandler(contextRequest);
        if (processor != null)
            return processor;

        return contextRequest.wrapProcessor(_context.get(contextRequest, contextRequest));
    }

    protected void processMovedPermanently(Request request, Response response, Callback callback)
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

    protected void processUnavailable(Request request, Response response, Callback callback)
    {
        Response.writeError(request, response, callback, HttpStatus.SERVICE_UNAVAILABLE_503, null);
    }

    protected Request.Processor processByContextHandler(ContextRequest contextRequest)
    {
        if (!_allowNullPathInContext && StringUtil.isEmpty(contextRequest.getPathInContext()))
        {
            return (request, response, callback) ->
            {
                // context request must end with /
                String queryString = request.getHttpURI().getQuery();
                Response.sendRedirect(request, response, callback,
                    HttpStatus.MOVED_TEMPORARILY_302,
                    request.getHttpURI().getPath() + (queryString == null ? "/" : ("/?" + queryString)),
                    true);
            };
        }
        return null;
    }

    /**
     * @param contextPath The _contextPath to set.
     */
    public void setContextPath(String contextPath)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _contextPath = URIUtil.canonicalPath(contextPath);
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
    public Resource getBaseResource()
    {
        return _baseResource;
    }

    /**
     * Set the base resource for this context.
     *
     * @param resourceBase The Path of the base resource for the context.
     */
    public void setBaseResource(Resource resourceBase)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _baseResource = resourceBase;
    }

    public void setBaseResource(Path path)
    {
        if (path == null)
        {
            // allow user to unset variable
            setBaseResource((Resource)null);
            return;
        }

        Resource resource = ResourceFactory.of(this).newResource(path);
        setBaseResource(resource);
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

    /**
     * Check the target when a target within a context is determined. If
     * the target is protected, 404 is returned.
     *
     * @param target the target to test
     * @return true if target is a protected target
     */
    public boolean isProtectedTarget(String target)
    {
        if (target == null || _protectedTargets.isEmpty())
            return false;

        if (target.startsWith("//"))
            target = URIUtil.compactPath(target);

        ProtectedTargetType type = _protectedTargets.getBest(target);

        return type == ProtectedTargetType.PREFIX ||
            type == ProtectedTargetType.EXACT && _protectedTargets.get(target) == ProtectedTargetType.EXACT;
    }

    /**
     * @param targets Array of URL prefix. Each prefix is in the form /path and will match either /path exactly or /path/anything
     */
    public void setProtectedTargets(String[] targets)
    {
        Index.Builder<ProtectedTargetType> builder = new Index.Builder<>();
        if (targets != null)
        {
            for (String t : targets)
            {
                if (!t.startsWith("/"))
                    throw new IllegalArgumentException("Bad protected target: " + t);

                builder.with(t, ProtectedTargetType.EXACT);
                builder.with(t + "/", ProtectedTargetType.PREFIX);
                builder.with(t + "?", ProtectedTargetType.PREFIX);
                builder.with(t + "#", ProtectedTargetType.PREFIX);
                builder.with(t + ";", ProtectedTargetType.PREFIX);
            }
        }
        _protectedTargets = builder.caseSensitive(false).build();
    }

    public String[] getProtectedTargets()
    {
        if (_protectedTargets == null)
            return null;

        return _protectedTargets.keySet().stream()
            .filter(s -> _protectedTargets.get(s) == ProtectedTargetType.EXACT)
            .toArray(String[]::new);
    }

    /**
     * Add an AliasCheck instance to possibly permit aliased resources
     *
     * @param check The alias checker
     */
    public void addAliasCheck(AliasCheck check)
    {
        _aliasChecks.add(check);
        if (check instanceof LifeCycle)
            addManaged((LifeCycle)check);
        else
            addBean(check);
    }

    /**
     * @return Immutable list of Alias checks
     */
    public List<AliasCheck> getAliasChecks()
    {
        return Collections.unmodifiableList(_aliasChecks);
    }

    /**
     * @param checks list of AliasCheck instances
     */
    public void setAliasChecks(List<AliasCheck> checks)
    {
        clearAliasChecks();
        checks.forEach(this::addAliasCheck);
    }

    /**
     * clear the list of AliasChecks
     */
    public void clearAliasChecks()
    {
        _aliasChecks.forEach(this::removeBean);
        _aliasChecks.clear();
    }

    @Override
    public boolean checkAlias(String pathInContext, Resource resource)
    {
        // Is the resource aliased?
        if (resource.isAlias())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Aliased resource: {} -> {}", resource, resource.getTargetURI());

            // alias checks
            for (AliasCheck check : _aliasChecks)
            {
                if (check.checkAlias(pathInContext, resource))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Aliased resource: {} approved by {}", resource, check);
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public String toString()
    {
        List<String> vhosts = getVirtualHosts();
        StringBuilder b = new StringBuilder();

        b.append(TypeUtil.toShortName(getClass())).append('@').append(Integer.toString(hashCode(), 16));
        b.append('{');
        if (getDisplayName() != null)
            b.append(getDisplayName()).append(',');
        b.append(getContextPath());
        b.append(",b=").append(getBaseResource());
        b.append(",a=").append(_availability.get());

        if (!vhosts.isEmpty())
        {
            b.append(",vh=[");
            b.append(String.join(",", vhosts));
            b.append(']');
        }
        Handler nestedHandler = getHandler();
        if (nestedHandler != null)
        {
            b.append(",h=");
            b.append(nestedHandler);
        }
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

    public class Context extends Attributes.Layer implements org.eclipse.jetty.server.Context
    {
        public Context()
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
            Request.Processor processor = ContextHandler.this.getErrorProcessor();
            if (processor == null)
                processor = getServer().getErrorProcessor();
            return processor;
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
        public Resource getBaseResource()
        {
            return _baseResource;
        }

        @Override
        public List<String> getVirtualHosts()
        {
            return ContextHandler.this.getVirtualHosts();
        }

        public <T> T get(Supplier<T> supplier, Request request)
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

        public void call(Invocable.Callable callable, Request request) throws Exception
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

        public void accept(Consumer<Throwable> consumer, Throwable t, Request request)
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

        public void run(Runnable runnable, Request request)
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

        @Override
        public void execute(Runnable runnable)
        {
            getServer().getContext().execute(() -> run(runnable));
        }

        protected DecoratedObjectFactory getDecoratedObjectFactory()
        {
            DecoratedObjectFactory factory = ContextHandler.this.getBean(DecoratedObjectFactory.class);
            if (factory != null)
                return factory;
            return getServer().getBean(DecoratedObjectFactory.class);
        }

        @Override
        public <T> T decorate(T o)
        {
            DecoratedObjectFactory factory = getDecoratedObjectFactory();
            if (factory != null)
                return factory.decorate(o);
            return o;
        }

        @Override
        public void destroy(Object o)
        {
            DecoratedObjectFactory factory = getDecoratedObjectFactory();
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
        default void enterScope(org.eclipse.jetty.server.Context context, Request request) {}

        /**
         * @param context The context being exited
         * @param request A request that is applicable to the scope, or null
         */
        default void exitScope(org.eclipse.jetty.server.Context context, Request request) {}
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

        String getName()
        {
            if (_vConnector != null)
                return '@' + _vConnector;
            else
                return _vHost;
        }

        @Override
        public String toString()
        {
            return "VHost{" +
                "_vHost='" + _vHost + '\'' +
                ", _wild=" + _wild +
                ", _vConnector='" + _vConnector + '\'' +
                '}';
        }
    }
}
