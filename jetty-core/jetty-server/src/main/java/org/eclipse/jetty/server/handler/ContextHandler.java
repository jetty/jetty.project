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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EventListener;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.AliasCheck;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SymlinkAllowedResourceAliasChecker;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ClassLoaderDump;
import org.eclipse.jetty.util.component.DumpableAttributes;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.MountedPathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Handler} that scopes a request to a specific {@link Context}.
 */
@ManagedObject
public class ContextHandler extends Handler.Wrapper implements Attributes, AliasCheck
{
    private static final Logger LOG = LoggerFactory.getLogger(ContextHandler.class);
    private static final ThreadLocal<Context> __context = new ThreadLocal<>();

    public static final String MANAGED_ATTRIBUTES = "org.eclipse.jetty.server.context.ManagedAttributes";

    /**
     * The attribute name that is set as a {@link Request} attribute to indicate the request is a cross context
     * dispatch.  The value can be set to a ServletDispatcher type if the target is known to be a servlet context.
     */
    public static final String CROSS_CONTEXT_ATTRIBUTE = "org.eclipse.jetty.CrossContextDispatch";

    /**
     * Get the current Context if any.
     *
     * @return The {@link Context} from a {@link ContextHandler};
     *         or null if the current {@link Thread} is not scoped to a {@link ContextHandler}.
     */
    public static Context getCurrentContext()
    {
        return __context.get();
    }

    /**
     * Get the current Context if any, or else server context if any.
     * @param server The server.
     * @return The {@link Context} from a {@link ContextHandler};
     *         or {@link Server#getContext()} if the current {@link Thread} is not scoped to a {@link ContextHandler}.
     */
    public static Context getCurrentContext(Server server)
    {
        Context context = __context.get();
        return context == null ? (server == null ? null : server.getContext()) : context;
    }

    // Do not remove, invoked via reflection.
    public static ContextHandler getCurrentContextHandler()
    {
        Context context = getCurrentContext();
        return (context instanceof ScopedContext scopedContext) ? scopedContext.getContextHandler() : null;
    }

    public static ContextHandler getContextHandler(Request request)
    {
        ContextRequest contextRequest = Request.as(request, ContextRequest.class);
        if (contextRequest == null)
            return null;
        return contextRequest.getContext() instanceof ScopedContext scoped ? scoped.getContextHandler() : null;
    }

    /*
     * The context (specifically it's attributes and mimeTypes) are not implemented as a layer over
     * the server context, as  this handler's context replaces the context in the request, it does not
     * wrap it. This is so that any cross context dispatch does not inherit attributes and types from
     * the dispatching context.
     */
    private final ScopedContext _context;
    private final Attributes _persistentAttributes = new Mapped();
    private final MimeTypes.Wrapper _mimeTypes = new MimeTypes.Wrapper();
    private final List<ContextScopeListener> _contextListeners = new CopyOnWriteArrayList<>();
    private final List<VHost> _vhosts = new ArrayList<>();

    private String _displayName;
    private String _contextPath = "/";
    private boolean _rootContext = true;
    private Resource _baseResource;
    private ClassLoader _classLoader;
    private Request.Handler _errorHandler;
    private boolean _allowNullPathInContext;
    private Index<ProtectedTargetType> _protectedTargets = Index.empty(false);
    private final List<AliasCheck> _aliasChecks = new CopyOnWriteArrayList<>();
    private File _tempDirectory;
    private boolean _tempDirectoryPersisted = false;
    private boolean _tempDirectoryCreated = false;
    private boolean _createdTempDirectoryName = false;
    private boolean _crossContextDispatchSupported = false;

    public enum Availability
    {
        STOPPED,        // stopped and can't be made unavailable nor shutdown
        STARTING,       // starting inside doStart. It may go to any of the next states.
        AVAILABLE,      // running normally
        UNAVAILABLE,    // Either a startup error or explicit call to setAvailable(false)
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

    private final AtomicReference<Availability> _availability = new AtomicReference<>(Availability.STOPPED);

    public ContextHandler()
    {
        this(null, null);
    }

    public ContextHandler(Handler handler)
    {
        this(handler, null);
    }

    public ContextHandler(String contextPath)
    {
        this(null, contextPath);
    }

    public ContextHandler(Handler handler, String contextPath)
    {
        super(handler);
        _context = newContext();
        if (contextPath != null)
            setContextPath(contextPath);

        if (File.separatorChar == '/')
            addAliasCheck(new SymlinkAllowedResourceAliasChecker(this));

        // If the current classloader (or the one that loaded this class) is different
        // from the Server classloader, then use that as the initial classloader for the context.
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null)
            classLoader = this.getClass().getClassLoader();
        if (classLoader != Server.class.getClassLoader())
            _classLoader = classLoader;
    }

    @Override
    public void setServer(Server server)
    {
        super.setServer(server);
        _mimeTypes.setWrapped(server.getMimeTypes());
    }
    
    protected ScopedContext newContext()
    {
        return new ScopedContext();
    }

    /**
     * @return The temporary directory configured for the context, or null if none configured.
     * @see Context#getTempDirectory()
     */
    @ManagedAttribute(value = "temporary directory location", readonly = true)
    public File getTempDirectory()
    {
        return _tempDirectory;
    }

    /**
     * <p>Set the temporary directory returned by {@link ScopedContext#getTempDirectory()}.  If not set here,
     * then the {@link Server#getTempDirectory()} is returned by {@link ScopedContext#getTempDirectory()}.</p>
     * <p>If {@link #isTempDirectoryPersistent()} is true, the directory set here is used directly but may
     * be created if it does not exist. If {@link #isTempDirectoryPersistent()} is false, then any {@code File} set
     * here will be deleted and recreated as a directory during {@link #start()} and will be deleted during
     * {@link #stop()}.</p>
     * @see #setTempDirectoryPersistent(boolean)
     * @param tempDirectory A directory. If it does not exist, it must be able to be created during start.
     */
    public void setTempDirectory(File tempDirectory)
    {
        if (isStarted())
            throw new IllegalStateException("Started");

        File oldTempDirectory = _tempDirectory;

        if (tempDirectory != null)
        {
            try
            {
                tempDirectory = new File(tempDirectory.getCanonicalPath());
            }
            catch (IOException e)
            {
                LOG.warn("Unable to find canonical path for {}", tempDirectory, e);
            }
        }

        if (oldTempDirectory != null)
        {
            try
            {
                //if we had made up the name of the tmp directory previously, delete it if the new name is different
                if (_createdTempDirectoryName && (tempDirectory == null || (!Files.isSameFile(oldTempDirectory.toPath(), tempDirectory.toPath()))))
                    IO.delete(oldTempDirectory);
            }
            catch (Exception e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Unable to delete old temp directory {}", oldTempDirectory, e);
            }
        }
        _tempDirectory = tempDirectory;
         _createdTempDirectoryName = false;
    }

    /**
     * <p>Set if the temp directory for this context will be kept over a stop and start cycle.</p>
     *
     * @see #setTempDirectory(File)
     * @param persist true to persist the temp directory on shutdown / exit of the context
     */
    public void setTempDirectoryPersistent(boolean persist)
    {
        _tempDirectoryPersisted = persist;
    }

    /**
     * @return true if tmp directory will persist between startups of the context
     */
    public boolean isTempDirectoryPersistent()
    {
        return _tempDirectoryPersisted;
    }

    /**
     * @return A mutable MimeTypes that wraps the {@link Server#getMimeTypes()}
     *         once {@link ContextHandler#setServer(Server)} has been called.
     * @see MimeTypes.Wrapper
     */
    public MimeTypes.Mutable getMimeTypes()
    {
        return _mimeTypes;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent,
            new ClassLoaderDump(getClassLoader()),
            new DumpableAttributes("handler attributes", _persistentAttributes),
            new DumpableAttributes("attributes", _context));
    }

    @ManagedAttribute(value = "Context")
    public ScopedContext getContext()
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
     * Set true if /context is not redirected to /context/.
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
     * representation of IP addresses. Host names may start with {@code "*."} to wildcard one level of names. Hosts and wildcard hosts may be followed with
     * {@code "@connectorname"} (eg: {@code "*.example.org@connectorname"}), in which case they will match only if the {@link Connector#getName()}
     * for the request also matches. If an entry is just {@code "@connectorname"} it will match any host if that connector was used.
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
                _vhosts.add(new VHost(vhost));
            }
        }
    }

    /**
     * Either set virtual hosts or add to an existing set of virtual hosts.
     *
     * @param virtualHosts Array of virtual hosts that this context responds to. A null/empty array means any hostname is acceptable. Host names may be String
     * representation of IP addresses. Host names may start with '*.' to wildcard one level of names. Hosts and wildcard hosts may be followed with
     * {@code "@connectorname"}, in which case they will match only if the {@link Connector#getName()} for the request also matches. If an entry is just
     * {@code "@connectorname"} it will match any host if that connector was used.  Note - In previous versions if one or more connectorname only entries existed
     * and none of the connectors matched the handler would not match regardless of any hostname entries.  If there is one or more connectorname only
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
     * {@code "@connectorname"}, in which case they will match only if the {@link Connector#getName()} for the request also matches. If an entry is just
     * {@code "@connectorname"} it will match any host if that connector was used.  Note - In previous versions if one or more connectorname only entries existed
     * and none of the connectors matched the handler would not match regardless of any hostname entries.  If there is one or more connectorname only
     * entries and one or more host only entries but no hostname and connector entries we assume the old behavior and will log a warning.  The warning
     * can be removed by removing the host entries that were previously being ignored, or modifying to include a hostname and connectorname entry.
     */
    public void removeVirtualHosts(String... virtualHosts)
    {
        List<String> vhosts = new ArrayList<>(getVirtualHosts());
        if (virtualHosts == null || virtualHosts.length == 0 || vhosts.isEmpty())
            return; // do nothing

        for (String vh : virtualHosts)
            _vhosts.remove(new VHost(vh));
    }

    /**
     * Get the virtual hosts for the context. Only requests that have a matching host header or fully qualified URL will be passed to that context with a
     * virtual host name. A context with no virtual host names or a null virtual host name is available to all requests that are not served by a context with a
     * matching virtual host name.
     *
     * @return list of virtual hosts that this context responds to. A null/empty array means any hostname is acceptable. Host names may be String
     * representation of IP addresses. Host names may start with '*.' to wildcard one level of names. Hosts and wildcard hosts may be followed with
     * {@code "@connectorname"}, in which case they will match only if the {@link Connector#getName()} for the request also matches. If an entry is just
     * {@code "@connectorname"} it will match any host if that connector was used.  Note - In previous versions if one or more connectorname only entries existed
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
     * Get if this context support cross context dispatch, either as originator or target.
     * @return True if this context supports cross context dispatch.
     */
    @ManagedAttribute(value = "Cross context dispatch is support by the context")
    public boolean isCrossContextDispatchSupported()
    {
        return _crossContextDispatchSupported;
    }

    /**
     * Set if this context support cross context dispatch, either as originator or target.
     * @param crossContextDispatchSupported True if this context supports cross context dispatch.
     */
    public void setCrossContextDispatchSupported(boolean crossContextDispatchSupported)
    {
        _crossContextDispatchSupported = crossContextDispatchSupported;
    }

    /**
     * If {@link #isCrossContextDispatchSupported() cross context dispatch is supported} by this context
     * then find a context by  {@link #getContextPath() contextPath} that also supports cross context dispatch.
     * If more than one context is found, then those with disjoint {@link #getVirtualHosts() virtual hosts} are
     * excluded and the first remaining context returned.
     *
     * @param path The path that will be served by the context
     * @return The found {@link ContextHandler} or null.
     */
    public ContextHandler getCrossContextHandler(String path)
    {
        if (!isCrossContextDispatchSupported())
            return null;

        List<ContextHandler> contexts = new ArrayList<>();
        for (ContextHandler contextHandler : getServer().getDescendants(ContextHandler.class))
        {
            if (contextHandler == null || !contextHandler.isCrossContextDispatchSupported())
                continue;
            String contextPath = contextHandler.getContextPath();
            if (path.equals(contextPath) ||
                (path.startsWith(contextPath) && path.charAt(contextPath.length()) == '/') ||
                "/".equals(contextPath))
                contexts.add(contextHandler);
        }

        if (contexts.isEmpty())
            return null;
        if (contexts.size() == 1)
            return contexts.get(0);

        // Remove non-matching virtual hosts
        List<String> vhosts = getVirtualHosts();
        if (vhosts != null && !vhosts.isEmpty())
        {
            for (ListIterator<ContextHandler> i = contexts.listIterator(); i.hasNext(); )
            {
                ContextHandler ch = i.next();

                List<String> targetVhosts = ch.getVirtualHosts();
                if (targetVhosts == null || targetVhosts.isEmpty() || Collections.disjoint(vhosts, targetVhosts))
                    i.remove();
            }
        }

        if (contexts.isEmpty())
            return null;

        // return the first longest
        ContextHandler contextHandler = null;
        for (ContextHandler c : contexts)
        {
            if (contextHandler == null || c.getContextPath().length() > contextHandler.getContextPath().length())
                contextHandler = c;
        }
        return contextHandler;
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
            if (listener instanceof ContextScopeListener contextScopeListener)
            {
                _contextListeners.add(contextScopeListener);
                if (__context.get() != null)
                    contextScopeListener.enterScope(__context.get(), null);
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
            if (listener instanceof ContextScopeListener contextScopeListener)
            {
                _contextListeners.remove(contextScopeListener);
                if (__context.get() != null)
                    contextScopeListener.exitScope(__context.get(), null);
            }
            return true;
        }
        return false;
    }

    protected ClassLoader enterScope(Request contextRequest)
    {
        ClassLoader lastLoader = Thread.currentThread().getContextClassLoader();
        __context.set(_context);
        if (_classLoader != null)
            Thread.currentThread().setContextClassLoader(_classLoader);
        notifyEnterScope(contextRequest);
        return lastLoader;
    }

    /**
     * @param request A request that is applicable to the scope, or null
     */
    protected void notifyEnterScope(Request request)
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

    protected void exitScope(Request request, Context lastContext, ClassLoader lastLoader)
    {
        notifyExitScope(request);
        __context.set(lastContext);
        Thread.currentThread().setContextClassLoader(lastLoader);
    }

    /**
     * @param request A request that is applicable to the scope, or null
     */
    protected void notifyExitScope(Request request)
    {
        for (ListIterator<ContextScopeListener> i = TypeUtil.listIteratorAtEnd(_contextListeners); i.hasPrevious();)
        {
            try
            {
                i.previous().exitScope(_context, request);
            }
            catch (Throwable e)
            {
                LOG.warn("Unable to exit scope", e);
            }
        }
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
                    case STARTING, AVAILABLE ->
                    {
                        if (_availability.compareAndSet(availability, Availability.UNAVAILABLE))
                            return;
                    }
                    default ->
                    {
                        return;
                    }
                }
            }
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        if (getContextPath() == null)
            throw new IllegalStateException("Null contextPath");

        Resource baseResource = getBaseResource();
        if (baseResource != null)
        {
            if (!Resources.isReadable(baseResource))
                throw new IllegalArgumentException("Base Resource is not valid: " + baseResource);
            if (baseResource.isAlias())
            {
                URI realUri = baseResource.getRealURI();
                if (realUri == null)
                {
                    LOG.warn("{} Base Resource should not be an alias (100% of requests to context are subject to Security/Alias Checks): {}", getDisplayName(), baseResource);
                }
                else
                {
                    LOG.info("{} Base Resource is an alias: {} -> {}", getDisplayName(), baseResource, realUri.toASCIIString());
                    setAttribute("_baseResource", _baseResource);
                    _baseResource = ResourceFactory.of(this).newResource(realUri);
                }
            }
        }

        _availability.set(Availability.STARTING);
        try
        {
            createTempDirectory();
            _context.call(super::doStart, null);
            _availability.compareAndSet(Availability.STARTING, Availability.AVAILABLE);
            LOG.info("Started {}", this);
        }
        finally
        {
            _availability.compareAndSet(Availability.STARTING, Availability.UNAVAILABLE);
        }
    }

    /**
     * <p>Create the temporary directory. If the directory exists, but is not persistent, then it is
     * first deleted and then recreated.  Once created, this method is a noop if called again before
     * stopping the context.</p>
     */
    protected void createTempDirectory()
    {
        File tempDirectory = getTempDirectory();
        if (tempDirectory != null && !_tempDirectoryCreated)
        {
            _tempDirectoryCreated = true;
            if (isTempDirectoryPersistent())
            {
                // Create the directory if it doesn't exist
                if (!tempDirectory.exists() && !tempDirectory.mkdirs())
                    throw new IllegalArgumentException("Unable to create temp dir: " + tempDirectory);
            }
            else
            {
                // Delete and recreate it to ensure it is empty
                if (tempDirectory.exists() && !IO.delete(tempDirectory))
                    throw new IllegalArgumentException("Failed to delete temp dir: " + tempDirectory);
                if (!tempDirectory.mkdirs())
                    throw new IllegalArgumentException("Unable to create temp dir: " + tempDirectory);

                // ensure it is removed on exist
                tempDirectory.deleteOnExit();
            }

            // is it usable
            if (!tempDirectory.canWrite() || !tempDirectory.isDirectory())
                throw new IllegalArgumentException("Temp dir " + tempDirectory + " not usable: writeable=" + tempDirectory.canWrite() + ", dir=" + tempDirectory.isDirectory());
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        _context.call(super::doStop, null);
        cleanupAfterStop();
        _tempDirectoryCreated = false;
        if (removeAttribute("_baseResource") instanceof Resource baseResource)
            _baseResource = baseResource;
    }

    protected void cleanupAfterStop() throws Exception
    {
        File tempDirectory = getTempDirectory();

        // if we're not persisting the temp dir contents delete it
        if (tempDirectory != null && tempDirectory.exists() && !isTempDirectoryPersistent())
        {
            IO.delete(tempDirectory);
        }

        //if it was jetty that created the tmp dir, it can be reset, otherwise we need to retain the name
        if (_createdTempDirectoryName)
        {
            setTempDirectory(null);
            _createdTempDirectoryName = false;
        }
    }

    /** Generate a reasonable name for the temp directory because one has not been
     * explicitly configured by the user with {@link #setTempDirectory(File)}. The
     * directory may also be created, if it is not persistent. If it is persistent
     * it will be created as necessary by {@link #createTempDirectory()} later
     * during the startup of the context.
     *
     * @throws Exception IllegalStateException if the parent tmp directory does
     * not exist, or IOException if the child tmp directory cannot be created.
     */
    protected void makeTempDirectory()
        throws Exception
    {
        File parent = getServer().getContext().getTempDirectory();
        if (parent == null || !parent.exists() || !parent.canWrite() || !parent.isDirectory())
            throw new IllegalStateException("Parent for temp dir not configured correctly: " + (parent == null ? "null" : "writeable=" + parent.canWrite()));

        boolean persistent = isTempDirectoryPersistent() || "work".equals(parent.toPath().getFileName().toString());

        //Create a name for the temp dir
        String temp = getCanonicalNameForTmpDir();
        File tmpDir;
        if (persistent)
        {
            //if it is to be persisted, make sure it will be the same name
            //by not using File.createTempFile, which appends random digits
            tmpDir = new File(parent, temp);
        }
        else
        {
            // ensure dir will always be unique by having classlib generate random path name
            tmpDir = Files.createTempDirectory(parent.toPath(), temp).toFile();
            tmpDir.deleteOnExit();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Set temp dir {}", tmpDir);
        setTempDirectory(tmpDir);
        setTempDirectoryPersistent(persistent);
        _createdTempDirectoryName = true;
    }

    /**
     * Create a canonical name for a context temp directory.
     * <p>
     * The form of the name is:
     *
     * <pre>"jetty-"+host+"-"+port+"-"+resourceBase+"-_"+context+"-"+virtualhost+"-"+randomdigits+".dir"</pre>
     *
     * host and port uniquely identify the server
     * context and virtual host uniquely identify the context
     * randomdigits ensure every tmp directory is unique
     *
     * @return the canonical name for the context temp directory
     */
    protected String getCanonicalNameForTmpDir()
    {
        StringBuilder canonicalName = new StringBuilder();
        canonicalName.append("jetty-");

        //get the host and the port from the first connector
        Server server = getServer();
        if (server != null)
        {
            Connector[] connectors = server.getConnectors();

            if (connectors.length > 0)
            {
                //Get the host
                String host = null;
                int port = 0;
                if (connectors[0] instanceof NetworkConnector connector)
                {
                    host = connector.getHost();
                    port = connector.getLocalPort();
                    if (port < 0)
                        port = connector.getPort();
                }
                if (host == null)
                    host = "0.0.0.0";
                canonicalName.append(host);
                canonicalName.append("-");
                canonicalName.append(port);
                canonicalName.append("-");
            }
        }

        // Resource base
        try
        {
            Resource resource = getResourceForTempDirName();
            String resourceBaseName = getBaseName(resource);
            canonicalName.append(resourceBaseName);
            canonicalName.append("-");
        }
        catch (Exception e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Can't get resource base name", e);

            canonicalName.append("-"); // empty resourceBaseName segment
        }

        //Context name
        String contextPath = getContextPath();
        contextPath = contextPath.replace('/', '_');
        contextPath = contextPath.replace('\\', '_');
        canonicalName.append(contextPath);

        //Virtual host (if there is one)
        canonicalName.append("-");
        List<String> vhosts = getVirtualHosts();
        if (vhosts == null || vhosts.size() <= 0)
            canonicalName.append("any");
        else
            canonicalName.append(vhosts.get(0));

        // sanitize
        for (int i = 0; i < canonicalName.length(); i++)
        {
            char c = canonicalName.charAt(i);
            if (!Character.isJavaIdentifierPart(c) && "-.".indexOf(c) < 0)
                canonicalName.setCharAt(i, '.');
        }

        canonicalName.append("-");

        return StringUtil.sanitizeFileSystemName(canonicalName.toString());
    }

    /**
     * @return the baseResource for the context to use in the temp dir name
     */
    protected Resource getResourceForTempDirName()
    {
        return getBaseResource();
    }

    /**
     * @param resource the resource whose filename minus suffix to extract
     * @return the filename of the resource without suffix
     */
    protected static String getBaseName(Resource resource)
    {
        // Use File System and File interface if present
        Path resourceFile = resource.getPath();

        if ((resourceFile != null) && (resource instanceof MountedPathResource))
        {
            resourceFile = ((MountedPathResource)resource).getContainerPath();
        }

        if (resourceFile != null)
        {
            Path fileName = resourceFile.getFileName();
            return fileName == null ? "" : fileName.toString();
        }

        // Use URI itself.
        URI uri = resource.getURI();
        if (uri == null)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("Resource has no URI reference: {}", resource);
            }
            return "";
        }

        return URIUtil.getUriLastPathSegment(uri);
    }

    public boolean checkVirtualHost(Request request)
    {
        if (_vhosts.isEmpty())
            return true;

        String host = Request.getServerName(request);
        String connectorName = request.getConnectionMetaData().getConnector().getName();

        for (VHost vhost : _vhosts)
        {
            if (vhost.matches(connectorName, host))
                return true;
        }
        return false;
    }

    @Override
    public void destroy()
    {
        _context.run(super::destroy);
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        Handler handler = getHandler();
        if (handler == null || !isStarted())
            return false;

        if (!checkVirtualHost(request))
            return false;

        // check the path matches the context path
        String path = request.getHttpURI().getCanonicalPath();
        String pathInContext = _context.getPathInContext(path);
        if (pathInContext == null)
            return false;

        if (!isAvailable())
        {
            handleUnavailable(request, response, callback);
            return true;
        }

        if (pathInContext.isEmpty() && !getAllowNullPathInContext())
        {
            handleMovedPermanently(request, response, callback);
            return true;
        }

        ContextRequest contextRequest = wrapRequest(request, response);

        // wrap might return null (eg ServletContextHandler could not match a servlet)
        if (contextRequest == null)
            return false;

        if (handleByContextHandler(pathInContext, contextRequest, response, callback))
            return true;

        // Past this point we are calling the downstream handler in scope.
        Context lastContext = getCurrentContext();
        ClassLoader lastLoader = enterScope(contextRequest);
        ContextResponse contextResponse = wrapResponse(contextRequest, response);
        try
        {
            return handler.handle(contextRequest, contextResponse, callback);
        }
        catch (Throwable t)
        {
            Response.writeError(contextRequest, contextResponse, callback, t);
            return true;
        }
        finally
        {
            // We exit scope here, even though handle() is asynchronous,
            // as we have wrapped all our callbacks to re-enter the scope.
            exitScope(contextRequest, lastContext, lastLoader);
        }
    }

    protected boolean handleByContextHandler(String pathInContext, ContextRequest request, Response response, Callback callback)
    {
        if (isProtectedTarget(pathInContext))
        {
            Response.writeError(request, response, callback, HttpStatus.NOT_FOUND_404, null);
            return true;
        }

        return false;
    }

    protected void handleMovedPermanently(Request request, Response response, Callback callback)
    {
        // TODO: should this be a fully qualified URI? (with scheme and host?)
        String location = _contextPath + "/";
        if (request.getHttpURI().getParam() != null)
            location += ";" + request.getHttpURI().getParam();
        if (request.getHttpURI().getQuery() != null)
            location += "?" + request.getHttpURI().getQuery();

        response.setStatus(HttpStatus.MOVED_PERMANENTLY_301);
        response.getHeaders().add(new HttpField(HttpHeader.LOCATION, location));
        callback.succeeded();
    }

    protected void handleUnavailable(Request request, Response response, Callback callback)
    {
        Response.writeError(request, response, callback, HttpStatus.SERVICE_UNAVAILABLE_503, null);
    }

    /**
     * @param contextPath The _contextPath to set.
     */
    public void setContextPath(String contextPath)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _contextPath = URIUtil.canonicalPath(Objects.requireNonNull(contextPath));
        _rootContext = "/".equals(contextPath);
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
    @ManagedAttribute(value = "document root for context", readonly = true)
    public Resource getBaseResource()
    {
        return _baseResource;
    }

    /**
     * <p>Set the base resource to serve content from for this context,
     * which must exist and be readable when the context is started.</p>
     *
     * @param resourceBase The base resource for the context.
     */
    public void setBaseResource(Resource resourceBase)
    {
        if (isStarted())
            throw new IllegalStateException(getState());

        _baseResource = resourceBase;
        /* Do not test if Resource is valid here, let that happen in doStart.
         * A resource at this point in time might be invalid or doesn't exist (yet).
         * (eg: due to stop behaviors, or Configuration.deconfigure() behaviors),
         */
    }

    /**
     * <p>Set the base resource to serve content from.</p>
     *
     * <p>Note: the {@link Resource} is created from {@link ResourceFactory#of(org.eclipse.jetty.util.component.Container)}
     * which is tied to the lifecycle of this context.</p>
     *
     * @param path The path to create a base resource from.
     * @see #setBaseResource(Resource)
     */
    public void setBaseResourceAsPath(Path path)
    {
        setBaseResource(path == null ? null : ResourceFactory.of(this).newResource(path));
    }

    /**
     * <p>Set the base resource to serve content from.</p>
     *
     * <p>Note: the {@link Resource} is created from {@link ResourceFactory#of(org.eclipse.jetty.util.component.Container)}
     * which is tied to the lifecycle of this context.</p>
     *
     * @param base The path to create a base resource from.
     * @see #setBaseResource(Resource)
     */
    public void setBaseResourceAsString(String base)
    {
        setBaseResource((base == null ? null : ResourceFactory.of(this).newResource(base)));
    }

    /**
     * @return Returns the errorHandler.
     */
    @ManagedAttribute("The error handler to use for the context")
    public Request.Handler getErrorHandler()
    {
        // TODO, do we need to wrap this so that we can establish the context
        //       Classloader?  Or will the caller already do that?
        return _errorHandler;
    }

    /**
     * @param errorHandler The error handler to set.
     */
    public void setErrorHandler(Request.Handler errorHandler)
    {
        updateBean(_errorHandler, errorHandler, true);
        _errorHandler = errorHandler;
    }

    protected ContextRequest wrapRequest(Request request, Response response)
    {
        return new ContextRequest(_context, request);
    }

    protected ContextResponse wrapResponse(ContextRequest request, Response response)
    {
        return new ContextResponse(_context, request, response);
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
     * Set list of AliasCheck instances.
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
                LOG.debug("Aliased resource: {} -> {}", resource, resource.getRealURI());

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
        b.append(",a=").append(_availability);

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

    private static String normalizeVirtualHostname(String host)
    {
        if (host == null)
            return null;
        // names with trailing "." are absolute and not searched for in any local resolv.conf domain
        if (host.endsWith("."))
            host = host.substring(0, host.length() - 1);
        return host;
    }

    public class ScopedContext extends Attributes.Layer implements Context
    {
        public ScopedContext()
        {
            super(_persistentAttributes);
        }

        @SuppressWarnings("unchecked")
        public <H extends ContextHandler> H getContextHandler()
        {
            return (H)ContextHandler.this;
        }

        @Override
        public Request.Handler getErrorHandler()
        {
            Request.Handler handler = ContextHandler.this.getErrorHandler();
            if (handler == null)
                handler = getServer().getErrorHandler();
            return handler;
        }

        @Override
        public String getContextPath()
        {
            return _contextPath;
        }

        @Override
        public MimeTypes getMimeTypes()
        {
            return _mimeTypes;
        }

        @Override
        public String toString()
        {
            return "%s@%x".formatted(getClass().getSimpleName(), ContextHandler.this.hashCode());
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
        public File getTempDirectory()
        {
            File tempDirectory = ContextHandler.this.getTempDirectory();
            if (tempDirectory == null)
                tempDirectory = getServer().getContext().getTempDirectory();
            return tempDirectory;
        }

        @Override
        public List<String> getVirtualHosts()
        {
            return ContextHandler.this.getVirtualHosts();
        }

        public void call(Invocable.Callable callable, Request request) throws Exception
        {
            Context lastContext = __context.get();
            if (lastContext == this)
                callable.call();
            else
            {
                ClassLoader lastLoader = enterScope(request);
                try
                {
                    callable.call();
                }
                finally
                {
                    exitScope(request, lastContext, lastLoader);
                }
            }
        }

        public <T> boolean test(Predicate<T> predicate, T t, Request request)
        {
            Context lastContext = __context.get();
            if (lastContext == this)
                return predicate.test(t);

            ClassLoader lastLoader = enterScope(request);
            try
            {
                return predicate.test(t);
            }
            finally
            {
                exitScope(request, lastContext, lastLoader);
            }
        }

        public void accept(Consumer<Throwable> consumer, Throwable t, Request request)
        {
            Context lastContext = __context.get();
            if (lastContext == this)
                consumer.accept(t);
            else
            {
                ClassLoader lastLoader = enterScope(request);
                try
                {
                    consumer.accept(t);
                }
                finally
                {
                    exitScope(request, lastContext, lastLoader);
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
                ClassLoader lastLoader = enterScope(request);
                try
                {
                    runnable.run();
                }
                finally
                {
                    exitScope(request, lastContext, lastLoader);
                }
            }
        }

        @Override
        public void execute(Runnable runnable)
        {
            execute(runnable, null);
        }

        public void execute(Runnable runnable, Request request)
        {
            getServer().getContext().execute(() -> run(runnable, request));
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

        @Override
        public String getPathInContext(String canonicallyEncodedPath)
        {
            return _rootContext ? canonicallyEncodedPath : Context.getPathInContext(_contextPath, canonicallyEncodedPath);
        }

        @Override
        public boolean isCrossContextDispatch(Request request)
        {
            return isCrossContextDispatchSupported() && request.getAttribute(CROSS_CONTEXT_ATTRIBUTE) != null;
        }

        @Override
        public String getCrossContextDispatchType(Request request)
        {
            return isCrossContextDispatchSupported() ? (String)request.getAttribute(CROSS_CONTEXT_ATTRIBUTE) : null;
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

        public VHost(String vhost)
        {
            boolean wild = false;
            String connector = null;
            int at = vhost.indexOf('@');
            if (at >= 0)
            {
                connector = vhost.substring(at + 1);
                vhost = vhost.substring(0, at);
            }

            if (StringUtil.isBlank(vhost))
            {
                vhost = null;
            }
            else if (vhost.startsWith("*."))
            {
                vhost = vhost.substring(1);
                wild = true;
            }

            _vHost = normalizeVirtualHostname(vhost);
            _wild = wild;
            _vConnector = connector;
        }

        public boolean matches(String connectorName, String host)
        {
            // Do we have a connector name to match
            if (_vConnector != null)
            {
                // then it must match
                if (!_vConnector.equalsIgnoreCase(connectorName))
                    return false;

                // if we don't also have a vhost then we are match, otherwise check the vhost as well
                if (_vHost == null)
                    return true;
            }

            // if we have a vhost
            if (_vHost != null && host != null)
            {
                // vHost pattern must be last or next to last if the host ends with '.' (indicates absolute DNS name)
                int offset = host.length() - _vHost.length() - (host.charAt(host.length() - 1) == '.' ? 1 : 0);
                if (host.regionMatches(true, offset, _vHost, 0, _vHost.length()))
                {
                    // if wild then we only match one level, so check for no more dots
                    if (_wild)
                        return host.lastIndexOf('.', offset - 1) < 0;
                    // otherwise the offset must be 0 for a complete match
                    return offset == 0;
                }
            }
            return false;
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
        public int hashCode()
        {
            return Objects.hash(_vHost, _wild, _vConnector);
        }

        @Override
        public boolean equals(Object o)
        {
            return o instanceof VHost vhost &&
                Objects.equals(_vHost, vhost._vHost) &&
                Objects.equals(_wild, vhost._wild) &&
                Objects.equals(_vConnector, vhost._vConnector);
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
