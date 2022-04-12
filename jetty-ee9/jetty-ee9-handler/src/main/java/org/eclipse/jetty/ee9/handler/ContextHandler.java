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

package org.eclipse.jetty.ee9.handler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
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

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.FilterRegistration.Dynamic;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextAttributeEvent;
import jakarta.servlet.ServletContextAttributeListener;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.descriptor.JspConfigDescriptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ContextRequest;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ContextHandler.
 *
 * <p>
 * This handler wraps a call to handle by setting the context and servlet path, plus setting the context classloader.
 * </p>
 * <p>
 * If the context init parameter {@code org.eclipse.jetty.server.context.ManagedAttributes} is set to a comma separated list of names, then they are treated as
 * context attribute names, which if set as attributes are passed to the servers Container so that they may be managed with JMX.
 * </p>
 * <p>
 * The maximum size of a form that can be processed by this context is controlled by the system properties {@code org.eclipse.jetty.server.Request.maxFormKeys} and
 * {@code org.eclipse.jetty.server.Request.maxFormContentSize}. These can also be configured with {@link #setMaxFormContentSize(int)} and {@link #setMaxFormKeys(int)}
 * </p>
 * <p>
 * The executor is made available via a context attributed {@code org.eclipse.jetty.server.Executor}.
 * </p>
 * <p>
 * By default, the context is created with the {@link AllowedResourceAliasChecker} which is configured to allow symlinks. If
 * this alias checker is not required, then {@link #clearAliasChecks()} or {@link #setAliasChecks(List)} should be called.
 * </p>
 */
// TODO make this work
@ManagedObject("EE9 Context")
public class ContextHandler extends ScopedHandler implements Attributes, Graceful
{
    public static final int SERVLET_MAJOR_VERSION = 5;
    public static final int SERVLET_MINOR_VERSION = 0;
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

    private static final String UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER = "Unimplemented {} - use org.eclipse.jetty.servlet.ServletContextHandler";

    private static final Logger LOG = LoggerFactory.getLogger(ContextHandler.class);

    private static final ThreadLocal<APIContext> __context = new ThreadLocal<>();

    private static String __serverInfo = "jetty/" + Server.getVersion();

    public static final String MANAGED_ATTRIBUTES = "org.eclipse.jetty.server.context.ManagedAttributes";

    public static final String MAX_FORM_KEYS_KEY = "org.eclipse.jetty.server.Request.maxFormKeys";
    public static final String MAX_FORM_CONTENT_SIZE_KEY = "org.eclipse.jetty.server.Request.maxFormContentSize";
    public static final int DEFAULT_MAX_FORM_KEYS = 1000;
    public static final int DEFAULT_MAX_FORM_CONTENT_SIZE = 200000;

    /**
     * Get the current ServletContext implementation.
     *
     * @return ServletContext implementation
     */
    public static APIContext getCurrentContext()
    {
        return __context.get();
    }

    public static ContextHandler getContextHandler(ServletContext context)
    {
        if (context instanceof APIContext)
            return ((APIContext)context).getContextHandler();
        APIContext c = getCurrentContext();
        if (c != null)
            return c.getContextHandler();
        return null;
    }

    public static ServletContext getServletContext(Context context)
    {
        if (context instanceof CoreContextHandler.CoreContext coreContext)
            return coreContext.getAPIContext();
        return null;
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

    private final CoreContextHandler _coreContextHandler;
    protected ContextStatus _contextStatus = ContextStatus.NOTSET;
    protected APIContext _apiContext;
    private final Map<String, String> _initParams;
    private boolean _contextPathDefault = true;
    private String _defaultRequestCharacterEncoding;
    private String _defaultResponseCharacterEncoding;
    private String _contextPathEncoded = "/";
    private Resource _baseResource;
    private MimeTypes _mimeTypes;
    private Map<String, String> _localeEncodingMap;
    private String[] _welcomeFiles;
    private ErrorHandler _errorHandler;
    private Logger _logger;
    private boolean _allowNullPathInfo;
    private int _maxFormKeys = Integer.getInteger(MAX_FORM_KEYS_KEY, DEFAULT_MAX_FORM_KEYS);
    private int _maxFormContentSize = Integer.getInteger(MAX_FORM_CONTENT_SIZE_KEY, DEFAULT_MAX_FORM_CONTENT_SIZE);
    private boolean _compactPath = false;

    private final List<EventListener> _programmaticListeners = new CopyOnWriteArrayList<>();
    private final List<ServletContextListener> _servletContextListeners = new CopyOnWriteArrayList<>();
    private final List<ServletContextListener> _destroyServletContextListeners = new ArrayList<>();
    private final List<ServletContextAttributeListener> _servletContextAttributeListeners = new CopyOnWriteArrayList<>();
    private final List<ServletRequestListener> _servletRequestListeners = new CopyOnWriteArrayList<>();
    private final List<ServletRequestAttributeListener> _servletRequestAttributeListeners = new CopyOnWriteArrayList<>();
    private final List<ContextScopeListener> _contextListeners = new CopyOnWriteArrayList<>();
    private final Set<EventListener> _durableListeners = new HashSet<>();
    private Index<ProtectedTargetType> _protectedTargets = Index.empty(false);
    private final List<AliasCheck> _aliasChecks = new CopyOnWriteArrayList<>();

    public ContextHandler()
    {
        this(null, null, null);
    }

    public ContextHandler(String contextPath)
    {
        this(null, null, contextPath);
    }

    public ContextHandler(org.eclipse.jetty.server.Handler.Container parent)
    {
        this(null, parent, "/");
    }

    public ContextHandler(org.eclipse.jetty.server.Handler.Container parent, String contextPath)
    {
        this(null, parent, contextPath);
    }

    protected ContextHandler(APIContext context,
                             org.eclipse.jetty.server.Handler.Container parent,
                             String contextPath)
    {
        _coreContextHandler = new CoreContextHandler();
        _apiContext = context == null ? new APIContext() : context;
        _initParams = new HashMap<>();
        if (contextPath != null)
            setContextPath(contextPath);
        if (parent != null)
            parent.addHandler(_coreContextHandler);
    }

    public org.eclipse.jetty.server.handler.ContextHandler getCoreContextHandler()
    {
        return _coreContextHandler;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent, new DumpableCollection("initparams " + this, getInitParams().entrySet()));
    }

    public APIContext getServletContext()
    {
        return _apiContext;
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

    @Override
    public void setServer(Server server)
    {
        super.setServer(server);
        if (_errorHandler != null)
            _errorHandler.setServer(server);
    }

    @Deprecated
    public boolean isUsingSecurityManager()
    {
        return false;
    }

    @Deprecated
    public void setUsingSecurityManager(boolean usingSecurityManager)
    {
        if (usingSecurityManager)
            throw new UnsupportedOperationException();
    }

    public void setVirtualHosts(String[] vhosts)
    {
        _coreContextHandler.setVirtualHosts(vhosts == null ? Collections.emptyList() : Arrays.asList(vhosts));
    }

    public void addVirtualHosts(String[] virtualHosts)
    {
        _coreContextHandler.addVirtualHosts(virtualHosts);
    }

    public void removeVirtualHosts(String[] virtualHosts)
    {
        _coreContextHandler.removeVirtualHosts(virtualHosts);
    }

    @ManagedAttribute(value = "Virtual hosts accepted by the context", readonly = true)
    public String[] getVirtualHosts()
    {
        return _coreContextHandler.getVirtualHosts().toArray(new String[0]);
    }

    @Override
    public Object getAttribute(String name)
    {
        return _coreContextHandler.getAttribute(name);
    }

    public Enumeration<String> getAttributeNames()
    {
        return Collections.enumeration(getAttributeNameSet());
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        return _coreContextHandler.getAttributeNameSet();
    }

    /**
     * @return Returns the attributes.
     */
    public Attributes getAttributes()
    {
        return _coreContextHandler;
    }

    /**
     * @return Returns the classLoader.
     */
    public ClassLoader getClassLoader()
    {
        return _coreContextHandler.getClassLoader();
    }

    /**
     * Make best effort to extract a file classpath from the context classloader
     *
     * @return Returns the classLoader.
     */
    @ManagedAttribute("The file classpath")
    public String getClassPath()
    {
        return _coreContextHandler.getClassPath();
    }

    /**
     * @return Returns the contextPath.
     */
    @ManagedAttribute("True if URLs are compacted to replace the multiple '/'s with a single '/'")
    public String getContextPath()
    {
        return _coreContextHandler.getContextPath();
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
        return _coreContextHandler.getDisplayName();
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
                _contextListeners.add((ContextScopeListener)listener);
                if (__context.get() != null)
                    ((ContextScopeListener)listener).enterScope(__context.get(), null, "Listener registered");
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
                        callContextInitialized(scl, new ServletContextEvent(_apiContext));
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
        return _coreContextHandler.isShutdown();
    }

    /**
     * Set shutdown status. This field allows for graceful shutdown of a context. A started context may be put into non accepting state so that existing
     * requests can complete, but no new requests are accepted.
     */
    @Override
    public CompletableFuture<Void> shutdown()
    {
        return _coreContextHandler.shutdown();
    }

    /**
     * @return false if this context is unavailable (sends 503)
     */
    public boolean isAvailable()
    {
        return _coreContextHandler.isAvailable();
    }

    /**
     * Set Available status.
     *
     * @param available true to set as enabled
     */
    public void setAvailable(boolean available)
    {
        _coreContextHandler.setAvailable(available);
    }

    public Logger getLogger()
    {
        return _logger;
    }

    public void setLogger(Logger logger)
    {
        _logger = logger;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (_logger == null)
            _logger = LoggerFactory.getLogger(ContextHandler.class.getName() + getLogNameSuffix());

        setAttribute("org.eclipse.jetty.server.Executor", getServer().getThreadPool());

        if (_mimeTypes == null)
            _mimeTypes = new MimeTypes();

        _durableListeners.addAll(getEventListeners());

        // allow the call to super.doStart() to be deferred by extended implementations.
        startContext();
        contextInitialized();
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
     * @see APIContext
     */
    protected void startContext() throws Exception
    {
        String managedAttributes = _initParams.get(MANAGED_ATTRIBUTES);
        if (managedAttributes != null)
            addEventListener(new ManagedAttributeListener(this, StringUtil.csvSplit(managedAttributes)));

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
                ServletContextEvent event = new ServletContextEvent(_apiContext);
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
                    ServletContextEvent event = new ServletContextEvent(_apiContext);
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
        try
        {
            stopContext();
            contextDestroyed();

            // retain only durable listeners
            setEventListeners(_durableListeners);
            _durableListeners.clear();

            if (_errorHandler != null)
                _errorHandler.stop();

            for (EventListener l : _programmaticListeners)
            {
                removeEventListener(l);
                if (l instanceof ContextScopeListener)
                {
                    try
                    {
                        ((ContextScopeListener)l).exitScope(_apiContext, null);
                    }
                    catch (Throwable e)
                    {
                        LOG.warn("Unable to exit scope", e);
                    }
                }
            }
            _programmaticListeners.clear();
        }
        finally
        {
            _contextStatus = ContextStatus.NOTSET;
        }
    }

    @Override
    public void doScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("scope {}|{}|{} @ {}", baseRequest.getContextPath(), baseRequest.getServletPath(), baseRequest.getPathInfo(), this);

        nextScope(target, baseRequest, request, response);
    }

    protected void requestInitialized(Request baseRequest, HttpServletRequest request)
    {
        // Handle the REALLY SILLY request events!
        if (!_servletRequestAttributeListeners.isEmpty())
            for (ServletRequestAttributeListener l : _servletRequestAttributeListeners)
            {
                baseRequest.addEventListener(l);
            }

        if (!_servletRequestListeners.isEmpty())
        {
            final ServletRequestEvent sre = new ServletRequestEvent(_apiContext, request);
            for (ServletRequestListener l : _servletRequestListeners)
            {
                l.requestInitialized(sre);
            }
        }
    }

    protected void requestDestroyed(Request baseRequest, HttpServletRequest request)
    {
        // Handle more REALLY SILLY request events!
        if (!_servletRequestListeners.isEmpty())
        {
            final ServletRequestEvent sre = new ServletRequestEvent(_apiContext, request);
            for (int i = _servletRequestListeners.size(); i-- > 0; )
            {
                _servletRequestListeners.get(i).requestDestroyed(sre);
            }
        }

        if (!_servletRequestAttributeListeners.isEmpty())
        {
            for (int i = _servletRequestAttributeListeners.size(); i-- > 0; )
            {
                baseRequest.removeEventListener(_servletRequestAttributeListeners.get(i));
            }
        }
    }

    @Override
    public void doHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        final DispatcherType dispatch = baseRequest.getDispatcherType();
        final boolean new_context = baseRequest.takeNewContext();
        try
        {
            if (new_context)
                requestInitialized(baseRequest, request);

            if (dispatch == DispatcherType.REQUEST && isProtectedTarget(target))
            {
                baseRequest.setHandled(true);
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            nextHandle(target, baseRequest, request, response);
        }
        finally
        {
            if (new_context)
                requestDestroyed(baseRequest, request);
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
                    listener.enterScope(_apiContext, request, reason);
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
    protected void exitScope(Request request)
    {
        if (!_contextListeners.isEmpty())
        {
            for (int i = _contextListeners.size(); i-- > 0; )
            {
                try
                {
                    _contextListeners.get(i).exitScope(_apiContext, request);
                }
                catch (Throwable e)
                {
                    LOG.warn("Unable to exit scope", e);
                }
            }
        }
    }

    /**
     * Handle a runnable in the scope of this context and a particular request
     *
     * @param request The request to scope the thread to (may be null if no particular request is in scope)
     * @param runnable The runnable to run.
     */
    public void handle(Request request, Runnable runnable)
    {
        ClassLoader oldClassloader = null;
        Thread currentThread = null;
        APIContext oldContext = __context.get();

        // Are we already in the scope?
        if (oldContext == _apiContext)
        {
            runnable.run();
            return;
        }

        // Nope, so enter the scope and then exit
        try
        {
            __context.set(_apiContext);
            _apiContext._coreContext.run(runnable, request.getHttpChannel().getCoreRequest());
        }
        finally
        {
            __context.set(null);
        }
    }

    /*
     * Handle a runnable in the scope of this context
     */
    public void handle(Runnable runnable)
    {
        handle(null, runnable);
    }

    /**
     * Check the target. Called by {@link #handle(String, Request, HttpServletRequest, HttpServletResponse)} when a target within a context is determined. If
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

    @Override
    public Object removeAttribute(String name)
    {
        return _coreContextHandler.removeAttribute(name);
    }

    /*
     * Set a context attribute. Attributes set via this API cannot be overridden by the ServletContext.setAttribute API. Their lifecycle spans the stop/start of
     * a context. No attribute listener events are triggered by this API.
     *
     * @see jakarta.servlet.ServletContext#setAttribute(java.lang.String, java.lang.Object)
     */
    @Override
    public Object setAttribute(String name, Object value)
    {
        return _coreContextHandler.setAttribute(name, value);
    }

    /**
     * @param attributes The attributes to set.
     */
    public void setAttributes(Attributes attributes)
    {
        _coreContextHandler.clearAttributes();
        for (String n : attributes.getAttributeNameSet())
            _coreContextHandler.setAttribute(n, attributes.getAttribute(n));
    }

    @Override
    public void clearAttributes()
    {
        _coreContextHandler.clearAttributes();
    }

    /**
     * @param classLoader The classLoader to set.
     */
    public void setClassLoader(ClassLoader classLoader)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _coreContextHandler.setClassLoader(classLoader);
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

        _coreContextHandler.setContextPath(contextPath);
        _contextPathEncoded = URIUtil.encodePath(contextPath);
        _contextPathDefault = false;

        // update context mappings
        if (getServer() != null && getServer().isRunning())
            getServer().getDescendants(ContextHandlerCollection.class).forEach(ContextHandlerCollection::mapContexts);
    }

    /**
     * @param displayName The servletContextName to set.
     */
    public void setDisplayName(String displayName)
    {
        _coreContextHandler.setDisplayName(displayName);
    }

    /**
     * @return Returns the resourceBase.
     */
    public Resource getBaseResource()
    {
        if (_baseResource == null)
            return null;
        return _baseResource;
    }

    /**
     * @return Returns the base resource as a string.
     */
    @ManagedAttribute("document root for context")
    public String getResourceBase()
    {
        if (_baseResource == null)
            return null;
        return _baseResource.toString();
    }

    /**
     * Set the base resource for this context.
     *
     * @param base The resource used as the base for all static content of this context.
     * @see #setResourceBase(String)
     */
    public void setBaseResource(Resource base)
    {
        try
        {
            _coreContextHandler.setResourceBase(base.getFile().toPath());
            _baseResource = base;
        }
        catch (IOException e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Set the base resource for this context.
     *
     * @param resourceBase A string representing the base resource for the context. Any string accepted by {@link Resource#newResource(String)} may be passed and the
     * call is equivalent to <code>setBaseResource(newResource(resourceBase));</code>
     */
    public void setResourceBase(String resourceBase)
    {
        try
        {
            setBaseResource(newResource(resourceBase));
        }
        catch (Exception e)
        {
            if (LOG.isDebugEnabled())
                LOG.warn("Unable to set baseResource: {}", resourceBase, e);
            else
                LOG.warn(e.toString());
            throw new IllegalArgumentException(resourceBase);
        }
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

    /**
     * @return Returns the errorHandler.
     */
    @ManagedAttribute("The error handler to use for the context")
    public ErrorHandler getErrorHandler()
    {
        return _errorHandler;
    }

    /**
     * @param errorHandler The errorHandler to set.
     */
    public void setErrorHandler(ErrorHandler errorHandler)
    {
        if (errorHandler != null)
            errorHandler.setServer(getServer());
        updateBean(_errorHandler, errorHandler, true);
        _errorHandler = errorHandler;
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

    /**
     * @return True if URLs are compacted to replace multiple '/'s with a single '/'
     * @deprecated use {@code CompactPathRule} with {@code RewriteHandler} instead.
     */
    @Deprecated
    public boolean isCompactPath()
    {
        return _compactPath;
    }

    /**
     * @param compactPath True if URLs are compacted to replace multiple '/'s with a single '/'
     */
    @Deprecated
    public void setCompactPath(boolean compactPath)
    {
        _compactPath = compactPath;
    }

    @Override
    public String toString()
    {
        final String[] vhosts = getVirtualHosts();

        StringBuilder b = new StringBuilder();

        Package pkg = getClass().getPackage();
        if (pkg != null)
        {
            String p = pkg.getName();
            if (p.length() > 0)
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
        b.append(getContextPath()).append(',').append(getBaseResource()).append(',').append(_coreContextHandler.isAvailable());

        if (vhosts != null && vhosts.length > 0)
            b.append(',').append(vhosts[0]);
        b.append('}');

        return b.toString();
    }

    public Class<?> loadClass(String className) throws ClassNotFoundException
    {
        if (className == null)
            return null;

        ClassLoader classLoader = _apiContext.getCoreContext().getClassLoader();
        if (classLoader == null)
            return Loader.loadClass(className);

        return classLoader.loadClass(className);
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

        if (_baseResource == null)
            return null;

        try
        {
            // addPath with accept non-canonical paths that don't go above the root,
            // but will treat them as aliases. So unless allowed by an AliasChecker
            // they will be rejected below.
            Resource resource = _baseResource.addPath(pathInContext);

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

    /* Handle a request from a connection.
     * Called to handle a request on the connection when either the header has been received,
     * or after the entire request has been received (for short requests of known length), or
     * on the dispatch of an async request.
     */
    public void handle(HttpChannel channel) throws IOException, ServletException
    {
        final String target = channel.getRequest().getPathInfo();
        final Request request = channel.getRequest();
        final org.eclipse.jetty.ee9.handler.Response response = channel.getResponse();

        if (LOG.isDebugEnabled())
            LOG.debug("{} {} {} ?{} on {}", request.getDispatcherType(), request.getMethod(), target, request.getQueryString(), channel);

        if (HttpMethod.OPTIONS.is(request.getMethod()) || "*".equals(target))
        {
            if (!HttpMethod.OPTIONS.is(request.getMethod()))
            {
                request.setHandled(true);
                response.sendError(HttpStatus.BAD_REQUEST_400);
            }
            else
            {
                handleOptions(request, response);
                if (!request.isHandled())
                    handle(target, request, request, response);
            }
        }
        else
            handle(target, request, request, response);

        if (LOG.isDebugEnabled())
            LOG.debug("handled={} async={} committed={} on {}", request.isHandled(), request.isAsyncStarted(), response.isCommitted(), channel);
    }

    /* Handle Options request to server
     */
    protected void handleOptions(Request request, org.eclipse.jetty.ee9.handler.Response response) throws IOException
    {
    }

    /* Handle a request from a connection.
     * Called to handle a request on the connection when either the header has been received,
     * or after the entire request has been received (for short requests of known length), or
     * on the dispatch of an async request.
     */
    public void handleAsync(HttpChannel channel) throws IOException, ServletException
    {
        final HttpChannelState state = channel.getRequest().getHttpChannelState();
        final AsyncContextEvent event = state.getAsyncContextEvent();
        final Request baseRequest = channel.getRequest();

        HttpURI baseUri = event.getBaseURI();
        String encodedPathQuery = event.getDispatchPath();

        if (encodedPathQuery == null && baseUri == null)
        {
            // Simple case, no request modification or merging needed
            handleAsync(channel, event, baseRequest);
            return;
        }

        // this is a dispatch with either a provided URI and/or a dispatched path
        // We will have to modify the request and then revert
        final HttpURI oldUri = baseRequest.getHttpURI();
        final MultiMap<String> oldQueryParams = baseRequest.getQueryParameters();
        try
        {
            if (encodedPathQuery == null)
            {
                baseRequest.setHttpURI(baseUri);
            }
            else
            {
                ServletContext servletContext = event.getServletContext();
                if (servletContext != null)
                {
                    String encodedContextPath = servletContext instanceof APIContext
                        ? ((APIContext)servletContext).getContextHandler().getContextPathEncoded()
                        : URIUtil.encodePath(servletContext.getContextPath());
                    if (!StringUtil.isEmpty(encodedContextPath))
                    {
                        encodedPathQuery = URIUtil.canonicalPath(URIUtil.addEncodedPaths(encodedContextPath, encodedPathQuery));
                        if (encodedPathQuery == null)
                            throw new BadMessageException(500, "Bad dispatch path");
                    }
                }

                if (baseUri == null)
                    baseUri = oldUri;
                HttpURI.Mutable builder = HttpURI.build(baseUri, encodedPathQuery);
                if (StringUtil.isEmpty(builder.getParam()))
                    builder.param(baseUri.getParam());
                if (StringUtil.isEmpty(builder.getQuery()))
                    builder.query(baseUri.getQuery());
                baseRequest.setHttpURI(builder);

                if (baseUri.getQuery() != null && baseRequest.getQueryString() != null)
                    // TODO why can't the old map be passed?
                    baseRequest.mergeQueryParameters(oldUri.getQuery(), baseRequest.getQueryString());
            }

            baseRequest.setContext(null, baseRequest.getHttpURI().getDecodedPath());
            handleAsync(channel, event, baseRequest);
        }
        finally
        {
            baseRequest.setHttpURI(oldUri);
            baseRequest.setQueryParameters(oldQueryParams);
            baseRequest.resetParameters();
        }
    }

    private void handleAsync(HttpChannel channel, AsyncContextEvent event, Request baseRequest) throws IOException, ServletException
    {
        final String target = baseRequest.getPathInfo();
        final HttpServletRequest request = Request.unwrap(event.getSuppliedRequest());
        final HttpServletResponse response = org.eclipse.jetty.ee9.handler.Response.unwrap(event.getSuppliedResponse());

        if (LOG.isDebugEnabled())
            LOG.debug("{} {} {} on {}", request.getDispatcherType(), request.getMethod(), target, channel);
        handle(target, baseRequest, request, response);
        if (LOG.isDebugEnabled())
            LOG.debug("handledAsync={} async={} committed={} on {}", channel.getRequest().isHandled(), request.isAsyncStarted(), response.isCommitted(), channel);
    }

    /**
     * Context.
     * <p>
     * A partial implementation of {@link jakarta.servlet.ServletContext}. A complete implementation is provided by the
     * derived {@link ContextHandler} implementations.
     * </p>
     */
    public class APIContext implements ServletContext
    {
        private final org.eclipse.jetty.server.handler.ContextHandler.Context _coreContext;
        protected boolean _enabled = true; // whether or not the dynamic API is enabled for callers
        protected boolean _extendedListenerTypes = false;
        private int _effectiveMajorVersion = SERVLET_MAJOR_VERSION;
        private int _effectiveMinorVersion = SERVLET_MINOR_VERSION;

        protected APIContext()
        {
            _coreContext = _coreContextHandler.getContext();
        }

        org.eclipse.jetty.server.Context getCoreContext()
        {
            return _coreContext;
        }

        @Override
        public int getMajorVersion()
        {
            return SERVLET_MAJOR_VERSION;
        }

        @Override
        public int getMinorVersion()
        {
            return SERVLET_MINOR_VERSION;
        }

        @Override
        public String getServerInfo()
        {
            return getServer().getServerInfo();
        }

        @Override
        public int getEffectiveMajorVersion()
        {
            return _effectiveMajorVersion;
        }

        @Override
        public int getEffectiveMinorVersion()
        {
            return _effectiveMinorVersion;
        }

        public void setEffectiveMajorVersion(int v)
        {
            _effectiveMajorVersion = v;
        }

        public void setEffectiveMinorVersion(int v)
        {
            _effectiveMinorVersion = v;
        }

        public ContextHandler getContextHandler()
        {
            return ContextHandler.this;
        }

        @Override
        public ServletContext getContext(String uripath)
        {
            if (getContextPath().equals(uripath))
                return _apiContext;
            // No cross context dispatch
            return null;
        }

        @Override
        public String getMimeType(String file)
        {
            if (_mimeTypes == null)
                return null;
            return _mimeTypes.getMimeByExtension(file);
        }

        @Override
        public RequestDispatcher getRequestDispatcher(String uriInContext)
        {
            // uriInContext is encoded, potentially with query.
            if (uriInContext == null)
                return null;

            if (!uriInContext.startsWith("/"))
                return null;

            try
            {
                String contextPath = getContextPath();
                // uriInContext is canonicalized by HttpURI.
                HttpURI.Mutable uri = HttpURI.build(uriInContext);
                String pathInfo = uri.getCanonicalPath();
                if (StringUtil.isEmpty(pathInfo))
                    return null;

                if (!StringUtil.isEmpty(contextPath))
                {
                    uri.path(URIUtil.addPaths(contextPath, uri.getPath()));
                    pathInfo = uri.getCanonicalPath().substring(contextPath.length());
                }
                return new Dispatcher(ContextHandler.this, uri, pathInfo);
            }
            catch (Exception e)
            {
                LOG.trace("IGNORED", e);
            }
            return null;
        }

        @Override
        public String getRealPath(String path)
        {
            // This is an API call from the application which may have arbitrary non canonical paths passed
            // Thus we canonicalize here, to avoid the enforcement of only canonical paths in
            // ContextHandler.this.getResource(path).
            path = URIUtil.canonicalPath(path);
            if (path == null)
                return null;
            if (path.length() == 0)
                path = URIUtil.SLASH;
            else if (path.charAt(0) != '/')
                path = URIUtil.SLASH + path;

            try
            {
                Resource resource = ContextHandler.this.getResource(path);
                if (resource != null)
                {
                    File file = resource.getFile();
                    if (file != null)
                        return file.getCanonicalPath();
                }
            }
            catch (Exception e)
            {
                LOG.trace("IGNORED", e);
            }

            return null;
        }

        @Override
        public URL getResource(String path) throws MalformedURLException
        {
            // This is an API call from the application which may have arbitrary non canonical paths passed
            // Thus we canonicalize here, to avoid the enforcement of only canonical paths in
            // ContextHandler.this.getResource(path).
            path = URIUtil.canonicalPath(path);
            if (path == null)
                return null;
            Resource resource = ContextHandler.this.getResource(path);
            if (resource != null && resource.exists())
                return resource.getURI().toURL();
            return null;
        }

        @Override
        public InputStream getResourceAsStream(String path)
        {
            try
            {
                URL url = getResource(path);
                if (url == null)
                    return null;
                Resource r = Resource.newResource(url);
                // Cannot serve directories as an InputStream
                if (r.isDirectory())
                    return null;
                return r.getInputStream();
            }
            catch (Exception e)
            {
                LOG.trace("IGNORED", e);
                return null;
            }
        }

        @Override
        public Set<String> getResourcePaths(String path)
        {
            // This is an API call from the application which may have arbitrary non canonical paths passed
            // Thus we canonicalize here, to avoid the enforcement of only canonical paths in
            // ContextHandler.this.getResource(path).
            path = URIUtil.canonicalPath(path);
            if (path == null)
                return null;
            return ContextHandler.this.getResourcePaths(path);
        }

        @Override
        public void log(Exception exception, String msg)
        {
            _logger.warn(msg, exception);
        }

        @Override
        public void log(String msg)
        {
            _logger.info(msg);
        }

        @Override
        public void log(String message, Throwable throwable)
        {
            if (throwable == null)
                _logger.warn(message);
            else
                _logger.warn(message, throwable);
        }

        @Override
        public String getInitParameter(String name)
        {
            return ContextHandler.this.getInitParameter(name);
        }

        @Override
        public Enumeration<String> getInitParameterNames()
        {
            return ContextHandler.this.getInitParameterNames();
        }

        @Override
        public Object getAttribute(String name)
        {
            return _coreContext.getAttribute(name);
        }

        @Override
        public Enumeration<String> getAttributeNames()
        {
            return Collections.enumeration(_coreContext.getAttributeNameSet());
        }

        @Override
        public void setAttribute(String name, Object value)
        {
            Object oldValue = _coreContext.setAttribute(name, value);

            if (!_servletContextAttributeListeners.isEmpty())
            {
                ServletContextAttributeEvent event = new ServletContextAttributeEvent(_apiContext, name, oldValue == null ? value : oldValue);

                for (ServletContextAttributeListener listener : _servletContextAttributeListeners)
                {
                    if (oldValue == null)
                        listener.attributeAdded(event);
                    else if (value == null)
                        listener.attributeRemoved(event);
                    else
                        listener.attributeReplaced(event);
                }
            }
        }

        @Override
        public void removeAttribute(String name)
        {
            Object oldValue = _coreContext.removeAttribute(name);
            if (oldValue != null && !_servletContextAttributeListeners.isEmpty())
            {
                ServletContextAttributeEvent event = new ServletContextAttributeEvent(_apiContext, name, oldValue);
                for (ServletContextAttributeListener listener : _servletContextAttributeListeners)
                {
                    listener.attributeRemoved(event);
                }
            }
        }

        @Override
        public String getServletContextName()
        {
            String name = ContextHandler.this.getDisplayName();
            if (name == null)
                name = ContextHandler.this.getContextPath();
            return name;
        }

        @Override
        public String getContextPath()
        {
            return getRequestContextPath();
        }

        @Override
        public String toString()
        {
            return "ServletContext@" + ContextHandler.this.toString();
        }

        @Override
        public boolean setInitParameter(String name, String value)
        {
            if (ContextHandler.this.getInitParameter(name) != null)
                return false;
            ContextHandler.this.getInitParams().put(name, value);
            return true;
        }

        @Override
        public void addListener(String className)
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            try
            {
                ClassLoader classLoader = _coreContext.getClassLoader();
                @SuppressWarnings({"unchecked", "rawtypes"})
                Class<? extends EventListener> clazz = classLoader == null ? Loader.loadClass(className) : (Class)classLoader.loadClass(className);
                addListener(clazz);
            }
            catch (ClassNotFoundException e)
            {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public <T extends EventListener> void addListener(T t)
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            checkListener(t.getClass());

            ContextHandler.this.addEventListener(t);
            ContextHandler.this.addProgrammaticListener(t);
        }

        @Override
        public void addListener(Class<? extends EventListener> listenerClass)
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            try
            {
                EventListener e = createListener(listenerClass);
                addListener(e);
            }
            catch (ServletException e)
            {
                throw new IllegalArgumentException(e);
            }
        }

        public void checkListener(Class<? extends EventListener> listener) throws IllegalStateException
        {
            boolean ok = false;
            int startIndex = (isExtendedListenerTypes() ? EXTENDED_LISTENER_TYPE_INDEX : DEFAULT_LISTENER_TYPE_INDEX);
            for (int i = startIndex; i < SERVLET_LISTENER_TYPES.length; i++)
            {
                if (SERVLET_LISTENER_TYPES[i].isAssignableFrom(listener))
                {
                    ok = true;
                    break;
                }
            }
            if (!ok)
                throw new IllegalArgumentException("Inappropriate listener class " + listener.getName());
        }

        public void setExtendedListenerTypes(boolean extended)
        {
            _extendedListenerTypes = extended;
        }

        public boolean isExtendedListenerTypes()
        {
            return _extendedListenerTypes;
        }

        // TODO  Empty implementations - should we merge in ServletContextHandler?
        @Override
        public RequestDispatcher getNamedDispatcher(String name)
        {
            return null;
        }

        @Override
        public Servlet getServlet(String name) throws ServletException
        {
            return null;
        }

        @Override
        public Enumeration<Servlet> getServlets()
        {
            return null;
        }

        @Override
        public Enumeration<String> getServletNames()
        {
            return null;
        }

        @Override
        public ServletRegistration.Dynamic addServlet(String servletName, String className)
        {
            return null;
        }

        @Override
        public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet)
        {
            return null;
        }

        @Override
        public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass)
        {
            return null;
        }

        @Override
        public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile)
        {
            return null;
        }

        @Override
        public <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException
        {
            return createInstance(clazz);
        }

        @Override
        public ServletRegistration getServletRegistration(String servletName)
        {
            return null;
        }

        @Override
        public Map<String, ? extends ServletRegistration> getServletRegistrations()
        {
            return null;
        }

        @Override
        public Dynamic addFilter(String filterName, String className)
        {
            return null;
        }

        @Override
        public Dynamic addFilter(String filterName, Filter filter)
        {
            return null;
        }

        @Override
        public Dynamic addFilter(String filterName, Class<? extends Filter> filterClass)
        {
            return null;
        }

        public <T> T createInstance(Class<T> clazz) throws ServletException
        {
            try
            {
                // TODO object factory!
                return clazz.getDeclaredConstructor().newInstance();
            }
            catch (Exception e)
            {
                throw new ServletException(e);
            }
        }

        @Override
        public <T extends Filter> T createFilter(Class<T> clazz) throws ServletException
        {
            return createInstance(clazz);
        }

        @Override
        public FilterRegistration getFilterRegistration(String filterName)
        {
            return null;
        }

        @Override
        public Map<String, ? extends FilterRegistration> getFilterRegistrations()
        {
            return null;
        }

        @Override
        public SessionCookieConfig getSessionCookieConfig()
        {
            return null;
        }

        @Override
        public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes)
        {

        }

        @Override
        public Set<SessionTrackingMode> getDefaultSessionTrackingModes()
        {
            return null;
        }

        @Override
        public Set<SessionTrackingMode> getEffectiveSessionTrackingModes()
        {
            return null;
        }

        @Override
        public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException
        {
            return createInstance(clazz);
        }

        @Override
        public int getSessionTimeout()
        {
            return 0;
        }

        @Override
        public void setSessionTimeout(int sessionTimeout)
        {

        }

        @Override
        public String getRequestCharacterEncoding()
        {
            return null;
        }

        @Override
        public void setRequestCharacterEncoding(String encoding)
        {

        }

        @Override
        public String getResponseCharacterEncoding()
        {
            return null;
        }

        @Override
        public void setResponseCharacterEncoding(String encoding)
        {

        }

        @Override
        public ClassLoader getClassLoader()
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            // no security manager just return the classloader
            if (!isUsingSecurityManager())
            {
                return _coreContext.getClassLoader();
            }
            else
            {
                // check to see if the classloader of the caller is the same as the context
                // classloader, or a parent of it, as required by the javadoc specification.

                ClassLoader classLoader = _coreContext.getClassLoader();

                // Wrap in a PrivilegedAction so that only Jetty code will require the
                // "createSecurityManager" permission, not also application code that calls this method.
                Caller caller = AccessController.doPrivileged((PrivilegedAction<Caller>)Caller::new);
                ClassLoader callerLoader = caller.getCallerClassLoader(2);
                while (callerLoader != null)
                {
                    if (callerLoader == classLoader)
                        return classLoader;
                    else
                        callerLoader = callerLoader.getParent();
                }
                System.getSecurityManager().checkPermission(new RuntimePermission("getClassLoader"));
                return classLoader;
            }

        }

        @Override
        public JspConfigDescriptor getJspConfigDescriptor()
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getJspConfigDescriptor()");
            return null;
        }

        public void setJspConfigDescriptor(JspConfigDescriptor d)
        {

        }

        @Override
        public void declareRoles(String... roleNames)
        {
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();
        }

        public void setEnabled(boolean enabled)
        {
            _enabled = enabled;
        }

        public boolean isEnabled()
        {
            return _enabled;
        }

        @Override
        public String getVirtualServerName()
        {
            String[] hosts = getVirtualHosts();
            if (hosts != null && hosts.length > 0)
                return hosts[0];
            return null;
        }
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
     * Approve all aliases.
     * @deprecated use {@link org.eclipse.jetty.server.AllowedResourceAliasChecker} instead.
     */
    @Deprecated
    public static class ApproveAliases implements AliasCheck
    {
        public ApproveAliases()
        {
            LOG.warn("ApproveAliases is deprecated");
        }

        @Override
        public boolean check(String pathInContext, Resource resource)
        {
            return true;
        }
    }

    /**
     * Approve Aliases of a non existent directory. If a directory "/foobar/" does not exist, then the resource is aliased to "/foobar". Accept such aliases.
     */
    @Deprecated
    public static class ApproveNonExistentDirectoryAliases implements AliasCheck
    {
        @Override
        public boolean check(String pathInContext, Resource resource)
        {
            if (resource.exists())
                return false;

            String a = resource.getAlias().toString();
            String r = resource.getURI().toString();

            if (a.length() > r.length())
                return a.startsWith(r) && a.length() == r.length() + 1 && a.endsWith("/");
            if (a.length() < r.length())
                return r.startsWith(a) && r.length() == a.length() + 1 && r.endsWith("/");

            return a.equals(r);
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
         * @param reason An object that indicates the reason the scope is being entered.
         */
        void enterScope(APIContext context, Request request, Object reason);

        /**
         * @param context The context being exited
         * @param request A request that is applicable to the scope, or null
         */
        void exitScope(APIContext context, Request request);
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

    public static class CoreContextRequest extends ContextRequest
    {
        private final HttpChannel _httpChannel;

        protected CoreContextRequest(org.eclipse.jetty.server.handler.ContextHandler contextHandler,
                                     org.eclipse.jetty.server.handler.ContextHandler.Context context,
                                     org.eclipse.jetty.server.Request wrapped,
                                     String pathInContext,
                                     HttpChannel httpChannel)
        {
            super(contextHandler, context, wrapped, pathInContext);
            _httpChannel = httpChannel;
        }

        public HttpChannel getHttpChannel()
        {
            return _httpChannel;
        }
    }

    class CoreContextHandler extends org.eclipse.jetty.server.handler.ContextHandler implements org.eclipse.jetty.server.Request.Processor
    {
        CoreContextHandler()
        {
            super.setHandler(new Handler.Abstract()
            {
                @Override
                public org.eclipse.jetty.server.Request.Processor handle(org.eclipse.jetty.server.Request request) throws Exception
                {
                    return CoreContextHandler.this;
                }
            });
            addBean(ContextHandler.this, true);
        }

        @Override
        public void setHandler(Handler handler)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setServer(Server server)
        {
            super.setServer(server);
            ContextHandler.this.setServer(server);
        }

        @Override
        protected CoreContext newContext()
        {
            return new CoreContext();
        }

        @Override
        protected ContextRequest wrap(org.eclipse.jetty.server.Request request, String pathInContext)
        {
            HttpChannel httpChannel = (HttpChannel)request.getComponents().getCache().get(HttpChannel.class.getName());
            if (httpChannel == null)
            {
                httpChannel = new HttpChannel(ContextHandler.this, request.getConnectionMetaData());
                request.getComponents().getCache().put(HttpChannel.class.getName(), httpChannel);
            }
            else
            {
                httpChannel.recycle();
            }

            return new CoreContextRequest(this, this.getContext(), request, pathInContext, httpChannel);
        }

        @Override
        protected void enterScope(org.eclipse.jetty.server.Request request)
        {
            __context.set(_apiContext);
            super.enterScope(request);

            // TODO get the servlet base request
            ContextHandler.this.enterScope(null, null);
        }

        @Override
        protected void exitScope(org.eclipse.jetty.server.Request request)
        {
            // TODO get the servlet base request
            try
            {
                ContextHandler.this.exitScope(null);
                super.exitScope(request);
            }
            finally
            {
                __context.set(null);
            }
        }

        @Override
        public void process(org.eclipse.jetty.server.Request request, Response response, Callback callback) throws Exception
        {
            HttpChannel httpChannel = org.eclipse.jetty.server.Request.get(request, CoreContextRequest.class, CoreContextRequest::getHttpChannel);
            httpChannel.onRequest(request, response, callback);
            httpChannel.getRequest().setContext(_apiContext, request.getPathInContext());

            httpChannel.handle();
        }

        class CoreContext extends org.eclipse.jetty.server.handler.ContextHandler.Context
        {
            public APIContext getAPIContext()
            {
                return _apiContext;
            }
        }
    }
}
