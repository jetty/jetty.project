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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextAttributeListener;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.ClassLoaderDump;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class contains the Servlet behaviours from the Jetty 11 ContextHandler, it is only supposed to
 * be extended by the ServletContextHandler and they should probably be combined into the same class.
 */
abstract class TempContextHandler extends ContextHandler implements Graceful
{
    public static final Class<?>[] SERVLET_LISTENER_TYPES =
        {
            ServletContextListener.class,
            ServletContextAttributeListener.class,
            ServletRequestListener.class,
            ServletRequestAttributeListener.class,
            HttpSessionIdListener.class,
            HttpSessionListener.class,
            HttpSessionAttributeListener.class
        };

    public static final int DEFAULT_LISTENER_TYPE_INDEX = 1;

    public static final int EXTENDED_LISTENER_TYPE_INDEX = 0;

    public static final String UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER = "Unimplemented {} - use org.eclipse.jetty.servlet.ServletContextHandler";

    private static final Logger LOG = LoggerFactory.getLogger(ContextHandler.class);

    private static String __serverInfo = "jetty/" + Server.getVersion();

    public static final String MANAGED_ATTRIBUTES = "org.eclipse.jetty.server.context.ManagedAttributes";

    public static final String MAX_FORM_KEYS_KEY = "org.eclipse.jetty.server.Request.maxFormKeys";
    public static final String MAX_FORM_CONTENT_SIZE_KEY = "org.eclipse.jetty.server.Request.maxFormContentSize";
    public static final int DEFAULT_MAX_FORM_KEYS = 1000;
    public static final int DEFAULT_MAX_FORM_CONTENT_SIZE = 200000;

    public static String getServerInfo()
    {
        return __serverInfo;
    }

    public static void setServerInfo(String serverInfo)
    {
        __serverInfo = serverInfo;
    }

    public enum ContextStatus
    {
        NOTSET,
        INITIALIZED,
        DESTROYED
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

    protected ServletContextHandler.ServletContextContext _servletContextContext;

    protected ContextStatus _contextStatus = ContextStatus.NOTSET;
    private final AttributesMap _attributes;
    private final Map<String, String> _initParams;
    private ClassLoader _classLoader;
    private boolean _contextPathDefault = true;
    private String _defaultRequestCharacterEncoding;
    private String _defaultResponseCharacterEncoding;
    private String _contextPathEncoded = "/";
    private String _displayName;
    private long _stopTimeout;
    protected MimeTypes _mimeTypes;
    private Map<String, String> _localeEncodingMap;
    private String[] _welcomeFiles;
    private Logger _logger;
    protected boolean _allowNullPathInfo;
    private int _maxFormKeys = Integer.getInteger(MAX_FORM_KEYS_KEY, DEFAULT_MAX_FORM_KEYS);
    private int _maxFormContentSize = Integer.getInteger(MAX_FORM_CONTENT_SIZE_KEY, DEFAULT_MAX_FORM_CONTENT_SIZE);
    private boolean _usingSecurityManager = System.getSecurityManager() != null;

    private final List<EventListener> _programmaticListeners = new CopyOnWriteArrayList<>();
    private final List<ServletContextListener> _servletContextListeners = new CopyOnWriteArrayList<>();
    private final List<ServletContextListener> _destroyServletContextListeners = new ArrayList<>();
    protected final List<ServletContextAttributeListener> _servletContextAttributeListeners = new CopyOnWriteArrayList<>();
    private final List<ServletRequestListener> _servletRequestListeners = new CopyOnWriteArrayList<>();
    private final List<ServletRequestAttributeListener> _servletRequestAttributeListeners = new CopyOnWriteArrayList<>();
    private final List<ContextScopeListener> _contextListeners = new CopyOnWriteArrayList<>();
    private final Set<EventListener> _durableListeners = new HashSet<>();
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

    protected final AtomicReference<Availability> _availability = new AtomicReference<>(Availability.STOPPED);

    protected TempContextHandler(Container parent, String contextPath)
    {
        _attributes = new AttributesMap();
        _initParams = new HashMap<>();

//        if (File.separatorChar == '/')
//            addAliasCheck(new SymlinkAllowedResourceAliasChecker(this));

        if (contextPath != null)
            setContextPath(contextPath);
        if (parent instanceof Handler.Wrapper)
            ((Handler.Wrapper)parent).setHandler(this);
        else if (parent instanceof Collection)
            ((Collection)parent).addHandler(this);
    }

    @Override
    public ServletContextHandler.Context getContext()
    {
        return (ServletContextHandler.Context)super.getContext();
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent,
            new ClassLoaderDump(getClassLoader()),
            new DumpableCollection("handler attributes " + this, _servletContextContext.getAttributeEntrySet()),
            new DumpableCollection("context attributes " + this, getContext().getAttributeEntrySet()),
            new DumpableCollection("initparams " + this, getInitParams().entrySet()));
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

    public boolean isUsingSecurityManager()
    {
        return _usingSecurityManager;
    }

    public void setUsingSecurityManager(boolean usingSecurityManager)
    {
        if (usingSecurityManager && System.getSecurityManager() == null)
            throw new IllegalStateException("No security manager");
        _usingSecurityManager = usingSecurityManager;
    }

    /**
     * @return Returns the encoded contextPath.
     */
    public String getContextPathEncoded()
    {
        return _contextPathEncoded;
    }

    /**
     * Get the context path in a form suitable to be returned from {@link HttpServletRequest#getContextPath()}
     * or {@link ServletContext#getContextPath()}.
     *
     * @return Returns the encoded contextPath, or empty string for root context
     */
    public String getRequestContextPath()
    {
        String contextPathEncoded = getContextPathEncoded();
        return "/".equals(contextPathEncoded) ? "" : contextPathEncoded;
    }

    /*
     * @see jakarta.servlet.ServletContext#getInitParameter(java.lang.String)
     */
    public String getInitParameter(String name)
    {
        return _initParams.get(name);
    }

    public String setInitParameter(String name, String value)
    {
        return _initParams.put(name, value);
    }

    /*
     * @see jakarta.servlet.ServletContext#getInitParameterNames()
     */
    public Enumeration<String> getInitParameterNames()
    {
        return Collections.enumeration(_initParams.keySet());
    }

    /**
     * @return Returns the initParams.
     */
    @ManagedAttribute("Initial Parameter map for the context")
    public Map<String, String> getInitParams()
    {
        return _initParams;
    }

    /*
     * @see jakarta.servlet.ServletContext#getServletContextName()
     */
    @ManagedAttribute(value = "Display name of the Context", readonly = true)
    public String getDisplayName()
    {
        return _displayName;
    }

    /**
     * Add a context event listeners.
     *
     * @param listener the event listener to add
     * @return true if the listener was added
     * @see ContextScopeListener
     * @see ServletContextListener
     * @see ServletContextAttributeListener
     * @see ServletRequestListener
     * @see ServletRequestAttributeListener
     */
    @Override
    public boolean addEventListener(EventListener listener)
    {
        if (super.addEventListener(listener))
        {
            if (listener instanceof ContextScopeListener)
            {
                ScopedContext currentContext = ContextHandler.getCurrentContext();
                _contextListeners.add((ContextScopeListener)listener);
                if (currentContext != null)
                    ((ContextScopeListener)listener).enterScope(currentContext, null, "Listener registered");
            }

            if (listener instanceof ServletContextListener)
            {
                if (_contextStatus == ContextStatus.INITIALIZED)
                {
                    ServletContextListener scl = (ServletContextListener)listener;
                    _destroyServletContextListeners.add(scl);
                    if (isStarting())
                    {
                        LOG.warn("ContextListener {} added whilst starting {}", scl, this);
                        callContextInitialized(scl, new ServletContextEvent(getServletContext()));
                    }
                    else
                    {
                        LOG.warn("ContextListener {} added after starting {}", scl, this);
                    }
                }

                _servletContextListeners.add((ServletContextListener)listener);
            }

            if (listener instanceof ServletContextAttributeListener)
                _servletContextAttributeListeners.add((ServletContextAttributeListener)listener);

            if (listener instanceof ServletRequestListener)
                _servletRequestListeners.add((ServletRequestListener)listener);

            if (listener instanceof ServletRequestAttributeListener)
                _servletRequestAttributeListeners.add((ServletRequestAttributeListener)listener);

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

            if (listener instanceof ServletContextListener)
            {
                _servletContextListeners.remove(listener);
                _destroyServletContextListeners.remove(listener);
            }

            if (listener instanceof ServletContextAttributeListener)
                _servletContextAttributeListeners.remove(listener);

            if (listener instanceof ServletRequestListener)
                _servletRequestListeners.remove(listener);

            if (listener instanceof ServletRequestAttributeListener)
                _servletRequestAttributeListeners.remove(listener);
            return true;
        }
        return false;
    }

    /**
     * Apply any necessary restrictions on a programmatic added listener.
     *
     * @param listener the programmatic listener to add
     */
    protected void addProgrammaticListener(EventListener listener)
    {
        _programmaticListeners.add(listener);
    }

    public boolean isProgrammaticListener(EventListener listener)
    {
        return _programmaticListeners.contains(listener);
    }

    public boolean isDurableListener(EventListener listener)
    {
        // The durable listeners are those set when the context is started
        if (isStarted())
            return _durableListeners.contains(listener);
        // If we are not yet started then all set listeners are durable
        return getEventListeners().contains(listener);
    }

    /**
     * @return true if this context is shutting down
     */
    @ManagedAttribute("true for graceful shutdown, which allows existing requests to complete")
    public boolean isShutdown()
    {
        return _availability.get() == Availability.SHUTDOWN;
    }

    /**
     * Set shutdown status. This field allows for graceful shutdown of a context. A started context may be put into non accepting state so that existing
     * requests can complete, but no new requests are accepted.
     */
    @Override
    public CompletableFuture<Void> shutdown()
    {
        while (true)
        {
            Availability availability = _availability.get();
            switch (availability)
            {
                case STOPPED:
                    return CompletableFuture.failedFuture(new IllegalStateException(getState()));
                case STARTING:
                case AVAILABLE:
                case UNAVAILABLE:
                    if (!_availability.compareAndSet(availability, Availability.SHUTDOWN))
                        continue;
                    break;
                default:
                    break;
            }
            break;
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * @return false if this context is unavailable (sends 503)
     */
    public boolean isAvailable()
    {
        return _availability.get() == Availability.AVAILABLE;
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

    public Logger getLogger()
    {
        return _logger;
    }

    public void setLogger(Logger logger)
    {
        _logger = logger;
    }

    public Resource getBaseResource()
    {
        Path resourceBase = getResourceBase();
        if (resourceBase == null)
            return null;
        return new PathResource(resourceBase);
    }

    @Override
    protected void doStart() throws Exception
    {
        if (getContextPath() == null)
            throw new IllegalStateException("Null contextPath");

        Resource baseResource = getBaseResource();
        if (baseResource != null && baseResource.isAlias())
            LOG.warn("BaseResource {} is aliased to {} in {}. May not be supported in future releases.",
                baseResource, baseResource.getAlias(), this);

        _availability.set(Availability.STARTING);

        if (_logger == null)
            _logger = LoggerFactory.getLogger(ContextHandler.class.getName() + getLogNameSuffix());

        ClassLoader oldClassloader = null;
        Thread currentThread = null;
        ContextHandler.ScopedContext oldContext = null;

        _attributes.setAttribute("org.eclipse.jetty.server.Executor", getServer().getThreadPool());

        if (_mimeTypes == null)
            _mimeTypes = new MimeTypes();

        _durableListeners.addAll(getEventListeners());

        try
        {
            // Set the classloader, context and enter scope
            if (_classLoader != null)
            {
                currentThread = Thread.currentThread();
                oldClassloader = currentThread.getContextClassLoader();
                currentThread.setContextClassLoader(_classLoader);
            }

            // defers the calling of super.doStart()
            startContext();

            contextInitialized();

            _availability.compareAndSet(Availability.STARTING, Availability.AVAILABLE);
            LOG.info("Started {}", this);
        }
        finally
        {
            _availability.compareAndSet(Availability.STARTING, Availability.UNAVAILABLE);
            exitScope(null);
            // reset the classloader
            if (_classLoader != null && currentThread != null)
                currentThread.setContextClassLoader(oldClassloader);
        }
    }

    private String getLogNameSuffix()
    {
        // Use display name first
        String logName = getDisplayName();
        if (StringUtil.isBlank(logName))
        {
            // try context path
            logName = getContextPath();
            if (logName != null)
            {
                // Strip prefix slash
                if (logName.startsWith("/"))
                {
                    logName = logName.substring(1);
                }
            }

            if (StringUtil.isBlank(logName))
            {
                // an empty context path is the ROOT context
                logName = "ROOT";
            }
        }

        // Replace bad characters.
        return '.' + logName.replaceAll("\\W", "_");
    }

    /**
     * Extensible startContext. this method is called from {@link ContextHandler#doStart()} instead of a call to super.doStart(). This allows derived classes to
     * insert additional handling (Eg configuration) before the call to super.doStart by this method will start contained handlers.
     *
     * @throws Exception if unable to start the context
     * @see ContextHandler.ScopedContext
     */
    protected void startContext() throws Exception
    {
        String managedAttributes = _initParams.get(MANAGED_ATTRIBUTES);
        if (managedAttributes != null)
            addEventListener(new ManagedAttributeListener((ServletContextHandler)this, StringUtil.csvSplit(managedAttributes)));

        super.doStart();
    }

    /**
     * Call the ServletContextListeners contextInitialized methods.
     * This can be called from a ServletHandler during the proper sequence
     * of initializing filters, servlets and listeners. However, if there is
     * no ServletHandler, the ContextHandler will call this method during
     * doStart().
     */
    public void contextInitialized() throws Exception
    {
        // Call context listeners
        if (_contextStatus == ContextStatus.NOTSET)
        {
            _contextStatus = ContextStatus.INITIALIZED;
            _destroyServletContextListeners.clear();
            if (!_servletContextListeners.isEmpty())
            {
                ServletContextEvent event = new ServletContextEvent(getServletContext());
                for (ServletContextListener listener : _servletContextListeners)
                {
                    callContextInitialized(listener, event);
                    _destroyServletContextListeners.add(listener);
                }
            }
        }
    }

    /**
     * Call the ServletContextListeners with contextDestroyed.
     * This method can be called from a ServletHandler in the
     * proper sequence of destroying filters, servlets and listeners.
     * If there is no ServletHandler, the ContextHandler must ensure
     * these listeners are called instead.
     */
    public void contextDestroyed() throws Exception
    {
        switch (_contextStatus)
        {
            case INITIALIZED:
            {
                try
                {
                    //Call context listeners
                    MultiException ex = new MultiException();
                    ServletContextEvent event = new ServletContextEvent(getServletContext());
                    Collections.reverse(_destroyServletContextListeners);
                    for (ServletContextListener listener : _destroyServletContextListeners)
                    {
                        try
                        {
                            callContextDestroyed(listener, event);
                        }
                        catch (Exception x)
                        {
                            ex.add(x);
                        }
                    }
                    ex.ifExceptionThrow();
                }
                finally
                {
                    _contextStatus = ContextStatus.DESTROYED;
                }
                break;
            }
            default:
                break;
        }
    }

    protected void stopContext() throws Exception
    {
        // stop all the handler hierarchy
        super.doStop();
    }

    protected void callContextInitialized(ServletContextListener l, ServletContextEvent e)
    {
        if (getServer().isDryRun())
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("contextInitialized: {}->{}", e, l);
        l.contextInitialized(e);
    }

    protected void callContextDestroyed(ServletContextListener l, ServletContextEvent e)
    {
        if (getServer().isDryRun())
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("contextDestroyed: {}->{}", e, l);
        l.contextDestroyed(e);
    }

    @Override
    protected void doStop() throws Exception
    {
        // Should we attempt a graceful shutdown?
        MultiException mex = null;

        _availability.set(Availability.STOPPED);

        ClassLoader oldClassloader = null;
        ClassLoader oldWebapploader = null;
        Thread currentThread = null;
        enterScope(null, "doStop");

        ServletContextHandler.Context context = getContext();
        try
        {
            // Set the classloader
            if (_classLoader != null)
            {
                oldWebapploader = _classLoader;
                currentThread = Thread.currentThread();
                oldClassloader = currentThread.getContextClassLoader();
                currentThread.setContextClassLoader(_classLoader);
            }

            stopContext();

            contextDestroyed();

            // retain only durable listeners
            setEventListeners(_durableListeners);
            _durableListeners.clear();

            /*
            TODO:
            if (_errorHandler != null)
                _errorHandler.stop();
            */

            for (EventListener l : _programmaticListeners)
            {
                removeEventListener(l);
                if (l instanceof ContextScopeListener)
                {
                    try
                    {
                        ((ContextScopeListener)l).exitScope(context, null);
                    }
                    catch (Throwable e)
                    {
                        LOG.warn("Unable to exit scope", e);
                    }
                }
            }
            _programmaticListeners.clear();
        }
        catch (Throwable x)
        {
            if (mex == null)
                mex = new MultiException();
            mex.add(x);
        }
        finally
        {
            _contextStatus = ContextStatus.NOTSET;
            exitScope(null);
            LOG.info("Stopped {}", this);
            // reset the classloader
            if ((oldClassloader == null || (oldClassloader != oldWebapploader)) && currentThread != null)
                currentThread.setContextClassLoader(oldClassloader);

            context.clearAttributes();
        }

        if (mex != null)
            mex.ifExceptionThrow();
    }

    protected void requestInitialized(Request baseRequest, HttpServletRequest request)
    {
        ServletScopedRequest scopedRequest = Request.as(baseRequest, ServletScopedRequest.class);

        // Handle the REALLY SILLY request events!
        if (!_servletRequestAttributeListeners.isEmpty())
        {
            for (ServletRequestAttributeListener l : _servletRequestAttributeListeners)
            {
                scopedRequest.addEventListener(l);
            }
        }

        if (!_servletRequestListeners.isEmpty())
        {
            final ServletRequestEvent sre = new ServletRequestEvent(getServletContext(), request);
            for (ServletRequestListener l : _servletRequestListeners)
            {
                l.requestInitialized(sre);
            }
        }
    }

    protected void requestDestroyed(Request baseRequest, HttpServletRequest request)
    {
        ServletScopedRequest scopedRequest = Request.as(baseRequest, ServletScopedRequest.class);

        // Handle more REALLY SILLY request events!
        if (!_servletRequestListeners.isEmpty())
        {
            final ServletRequestEvent sre = new ServletRequestEvent(getServletContext(), request);
            for (int i = _servletRequestListeners.size(); i-- > 0; )
            {
                _servletRequestListeners.get(i).requestDestroyed(sre);
            }
        }

        if (!_servletRequestAttributeListeners.isEmpty())
        {
            for (int i = _servletRequestAttributeListeners.size(); i-- > 0; )
            {
                scopedRequest.removeEventListener(_servletRequestAttributeListeners.get(i));
            }
        }
    }

    /**
     * @param request A request that is applicable to the scope, or null
     * @param reason An object that indicates the reason the scope is being entered.
     */
    protected void enterScope(Request request, Object reason)
    {
        if (!_contextListeners.isEmpty())
        {
            for (ContextScopeListener listener : _contextListeners)
            {
                try
                {
                    listener.enterScope(getContext(), request, reason);
                }
                catch (Throwable e)
                {
                    LOG.warn("Unable to enter scope", e);
                }
            }
        }
    }

    /**
     * @param request A request that is applicable to the scope, or null
     */
    @Override
    protected void exitScope(Request request)
    {
        if (!_contextListeners.isEmpty())
        {
            for (int i = _contextListeners.size(); i-- > 0; )
            {
                try
                {
                    _contextListeners.get(i).exitScope(getContext(), request);
                }
                catch (Throwable e)
                {
                    LOG.warn("Unable to exit scope", e);
                }
            }
        }
        super.exitScope(request);
    }

    /**
     * Check the target. Called by {@link #handle(Request)} when a target within a context is determined. If
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
     * @param classLoader The classLoader to set.
     */
    public void setClassLoader(ClassLoader classLoader)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _classLoader = classLoader;
    }

    /**
     * Set the default context path.
     * A default context path may be overriden by a default-context-path element
     * in a web.xml
     *
     * @param contextPath The _contextPath to set.
     */
    public void setDefaultContextPath(String contextPath)
    {
        setContextPath(contextPath);
        _contextPathDefault = true;
    }

    public void setDefaultRequestCharacterEncoding(String encoding)
    {
        _defaultRequestCharacterEncoding = encoding;
    }

    public String getDefaultRequestCharacterEncoding()
    {
        return _defaultRequestCharacterEncoding;
    }

    public void setDefaultResponseCharacterEncoding(String encoding)
    {
        _defaultResponseCharacterEncoding = encoding;
    }

    public String getDefaultResponseCharacterEncoding()
    {
        return _defaultResponseCharacterEncoding;
    }

    /**
     * @return True if the current contextPath is from default settings
     */
    public boolean isContextPathDefault()
    {
        return _contextPathDefault;
    }

    /**
     * @param contextPath The _contextPath to set.
     */
    @Override
    public void setContextPath(String contextPath)
    {
        if (contextPath == null)
            throw new IllegalArgumentException("null contextPath");

        if (contextPath.endsWith("/*"))
        {
            LOG.warn("{} contextPath ends with /*", this);
            contextPath = contextPath.substring(0, contextPath.length() - 2);
        }
        else if (contextPath.length() > 1 && contextPath.endsWith("/"))
        {
            LOG.warn("{} contextPath ends with /", this);
            contextPath = contextPath.substring(0, contextPath.length() - 1);
        }

        if (contextPath.length() == 0)
        {
            LOG.warn("Empty contextPath");
            contextPath = "/";
        }

        super.setContextPath(contextPath);
        _contextPathEncoded = URIUtil.encodePath(contextPath);
        _contextPathDefault = false;

        if (getServer() != null && (getServer().isStarting() || getServer().isStarted()))
        {
            Class<ContextHandlerCollection> handlerClass = ContextHandlerCollection.class;
            List<ContextHandlerCollection> contextCollections = getServer().getDescendants(handlerClass);
            if (contextCollections != null)
            {
                for (Handler contextCollection : contextCollections)
                {
                    handlerClass.cast(contextCollection).mapContexts();
                }
            }
        }
    }

    /**
     * @param servletContextName The servletContextName to set.
     */
    public void setDisplayName(String servletContextName)
    {
        _displayName = servletContextName;
    }

    /**
     * @return Returns the mimeTypes.
     */
    public MimeTypes getMimeTypes()
    {
        if (_mimeTypes == null)
            _mimeTypes = new MimeTypes();
        return _mimeTypes;
    }

    /**
     * @param mimeTypes The mimeTypes to set.
     */
    public void setMimeTypes(MimeTypes mimeTypes)
    {
        _mimeTypes = mimeTypes;
    }

    public void setWelcomeFiles(String[] files)
    {
        _welcomeFiles = files;
    }

    /**
     * @return The names of the files which the server should consider to be welcome files in this context.
     * @see <a href="http://jcp.org/aboutJava/communityprocess/final/jsr154/index.html">The Servlet Specification</a>
     * @see #setWelcomeFiles
     */
    @ManagedAttribute(value = "Partial URIs of directory welcome files", readonly = true)
    public String[] getWelcomeFiles()
    {
        return _welcomeFiles;
    }

    @ManagedAttribute("The maximum content size")
    public int getMaxFormContentSize()
    {
        return _maxFormContentSize;
    }

    /**
     * Set the maximum size of a form post, to protect against DOS attacks from large forms.
     *
     * @param maxSize the maximum size of the form content (in bytes)
     */
    public void setMaxFormContentSize(int maxSize)
    {
        _maxFormContentSize = maxSize;
    }

    public int getMaxFormKeys()
    {
        return _maxFormKeys;
    }

    /**
     * Set the maximum number of form Keys to protect against DOS attack from crafted hash keys.
     *
     * @param max the maximum number of form keys
     */
    public void setMaxFormKeys(int max)
    {
        _maxFormKeys = max;
    }

    public Class<?> loadClass(String className) throws ClassNotFoundException
    {
        if (className == null)
            return null;

        if (_classLoader == null)
            return Loader.loadClass(className);

        return _classLoader.loadClass(className);
    }

    public void addLocaleEncoding(String locale, String encoding)
    {
        if (_localeEncodingMap == null)
            _localeEncodingMap = new HashMap<>();
        _localeEncodingMap.put(locale, encoding);
    }

    public String getLocaleEncoding(String locale)
    {
        if (_localeEncodingMap == null)
            return null;
        String encoding = _localeEncodingMap.get(locale);
        return encoding;
    }

    /**
     * Get the character encoding for a locale. The full locale name is first looked up in the map of encodings. If no encoding is found, then the locale
     * language is looked up.
     *
     * @param locale a <code>Locale</code> value
     * @return a <code>String</code> representing the character encoding for the locale or null if none found.
     */
    public String getLocaleEncoding(Locale locale)
    {
        if (_localeEncodingMap == null)
            return null;
        String encoding = _localeEncodingMap.get(locale.toString());
        if (encoding == null)
            encoding = _localeEncodingMap.get(locale.getLanguage());
        return encoding;
    }

    /**
     * Get all of the locale encodings
     *
     * @return a map of all the locale encodings: key is name of the locale and value is the char encoding
     */
    public Map<String, String> getLocaleEncodings()
    {
        if (_localeEncodingMap == null)
            return null;
        return Collections.unmodifiableMap(_localeEncodingMap);
    }

    /**
     * Attempt to get a Resource from the Context.
     *
     * @param pathInContext the path within the base resource to attempt to get
     * @return the resource, or null if not available.
     * @throws MalformedURLException if unable to form a Resource from the provided path
     */
    public Resource getResource(String pathInContext) throws MalformedURLException
    {
        if (pathInContext == null || !pathInContext.startsWith(URIUtil.SLASH))
            throw new MalformedURLException(pathInContext);

        Resource baseResource = getBaseResource();
        if (baseResource == null)
            return null;

        try
        {
            // addPath with accept non-canonical paths that don't go above the root,
            // but will treat them as aliases. So unless allowed by an AliasChecker
            // they will be rejected below.
            Resource resource = baseResource.addPath(pathInContext);

            if (checkAlias(pathInContext, resource))
                return resource;
            return null;
        }
        catch (Exception e)
        {
            LOG.trace("IGNORED", e);
        }

        return null;
    }

    /**
     * @param path the path to check the alias for
     * @param resource the resource
     * @return True if the alias is OK
     */
    public boolean checkAlias(String path, Resource resource)
    {
        // Is the resource aliased?
        if (resource.isAlias())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Aliased resource: {}~={}", resource, resource.getAlias());

            // alias checks
            for (AliasCheck check : getAliasChecks())
            {
                if (check.check(path, resource))
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

    /**
     * Convert URL to Resource wrapper for {@link Resource#newResource(URL)} enables extensions to provide alternate resource implementations.
     *
     * @param url the url to convert to a Resource
     * @return the Resource for that url
     * @throws IOException if unable to create a Resource from the URL
     */
    public Resource newResource(URL url) throws IOException
    {
        return Resource.newResource(url);
    }

    /**
     * Convert URL to Resource wrapper for {@link Resource#newResource(URL)} enables extensions to provide alternate resource implementations.
     *
     * @param uri the URI to convert to a Resource
     * @return the Resource for that URI
     * @throws IOException if unable to create a Resource from the URL
     */
    public Resource newResource(URI uri) throws IOException
    {
        return Resource.newResource(uri);
    }

    /**
     * Convert a URL or path to a Resource. The default implementation is a wrapper for {@link Resource#newResource(String)}.
     *
     * @param urlOrPath The URL or path to convert
     * @return The Resource for the URL/path
     * @throws IOException The Resource could not be created.
     */
    public Resource newResource(String urlOrPath) throws IOException
    {
        return Resource.newResource(urlOrPath);
    }

    public Set<String> getResourcePaths(String path)
    {
        try
        {
            Resource resource = getResource(path);

            if (resource != null && resource.exists())
            {
                if (!path.endsWith(URIUtil.SLASH))
                    path = path + URIUtil.SLASH;

                String[] l = resource.list();
                if (l != null)
                {
                    HashSet<String> set = new HashSet<>();
                    for (int i = 0; i < l.length; i++)
                    {
                        set.add(path + l[i]);
                    }
                    return set;
                }
            }
        }
        catch (Exception e)
        {
            LOG.trace("IGNORED", e);
        }
        return Collections.emptySet();
    }

    private String normalizeHostname(String host)
    {
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

    /**
     * Interface to check aliases
     */
    public interface AliasCheck
    {

        /**
         * Check an alias
         *
         * @param pathInContext The path the aliased resource was created for
         * @param resource The aliased resourced
         * @return True if the resource is OK to be served.
         */
        boolean check(String pathInContext, Resource resource);
    }

    /**
     * Listener for all threads entering context scope, including async IO callbacks
     */
    public interface ContextScopeListener extends EventListener
    {
        /**
         * @param context The context being entered
         * @param request A request that is applicable to the scope, or null
         * @param reason An object that indicates the reason the scope is being entered.
         */
        void enterScope(ContextHandler.ScopedContext context, Request request, Object reason);

        /**
         * @param context The context being exited
         * @param request A request that is applicable to the scope, or null
         */
        void exitScope(ContextHandler.ScopedContext context, Request request);
    }

    public ServletContext getServletContext()
    {
        return getContext().getServletContext();
    }

    private static class Caller extends SecurityManager
    {
        public ClassLoader getCallerClassLoader(int depth)
        {
            if (depth < 0)
                return null;
            Class<?>[] classContext = getClassContext();
            if (classContext.length <= depth)
                return null;
            return classContext[depth].getClassLoader();
        }
    }
}
