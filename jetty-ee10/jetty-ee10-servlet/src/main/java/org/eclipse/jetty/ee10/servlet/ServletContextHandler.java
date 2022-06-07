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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContainerInitializer;
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
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.descriptor.JspConfigDescriptor;
import jakarta.servlet.descriptor.JspPropertyGroupDescriptor;
import jakarta.servlet.descriptor.TaglibDescriptor;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSessionActivationListener;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.eclipse.jetty.ee10.servlet.security.ConstraintAware;
import org.eclipse.jetty.ee10.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee10.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee10.servlet.security.SecurityHandler;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SymlinkAllowedResourceAliasChecker;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ContextRequest;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.DeprecationWarning;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Servlet Context.
 * <p>
 * This extension to the ContextHandler allows for
 * simple construction of a context with ServletHandler and optionally
 * session and security handlers, et.
 * <pre>
 *   new ServletContext("/context",Context.SESSIONS|Context.NO_SECURITY);
 * </pre>
 * <p>
 * This class should have been called ServletContext, but this would have
 * cause confusion with {@link ServletContext}.
 */
@ManagedObject("Servlet Context Handler")
public class ServletContextHandler extends ContextHandler implements Graceful
{
    private static final Logger LOG = LoggerFactory.getLogger(ServletContextHandler.class);
    protected static final Environment __environment = Environment.ensure("ee10");
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
    public static final String MANAGED_ATTRIBUTES = "org.eclipse.jetty.server.context.ManagedAttributes";
    public static final String MAX_FORM_KEYS_KEY = "org.eclipse.jetty.server.Request.maxFormKeys";
    public static final String MAX_FORM_CONTENT_SIZE_KEY = "org.eclipse.jetty.server.Request.maxFormContentSize";
    public static final int DEFAULT_MAX_FORM_KEYS = 1000;
    public static final int DEFAULT_MAX_FORM_CONTENT_SIZE = 200000;

    public static final int SESSIONS = 1;
    public static final int SECURITY = 2;
    public static final int NO_SESSIONS = 0;
    public static final int NO_SECURITY = 0;

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
    protected enum ProtectedTargetType
    {
        EXACT,
        PREFIX
    }

    public static ServletContextHandler getServletContextHandler(ServletContext servletContext, String purpose)
    {
        if (servletContext instanceof ServletContextApi servletContextApi)
            return servletContextApi.getContext().getServletContextHandler();
        throw new IllegalStateException("No Jetty ServletContextHandler, " + purpose + " unavailable");
    }

    public static ServletContextHandler getServletContextHandler(ServletContext servletContext)
    {
        if (servletContext instanceof ServletContextApi)
            return ((ServletContextApi)servletContext).getContext().getServletContextHandler();
        return null;
    }

    public static ServletContext getCurrentServletContext()
    {
        return getServletContext(ContextHandler.getCurrentContext());
    }

    public static ServletContext getServletContext(ContextHandler.Context context)
    {
        if (context instanceof Context)
            return ((Context)context).getServletContext();
        return null;
    }

    public static ServletContextHandler getCurrentServletContextHandler()
    {
        ContextHandler.Context context = ContextHandler.getCurrentContext();
        if (context instanceof Context)
            return ((Context)context).getServletContextHandler();
        return null;
    }

    public interface ServletContainerInitializerCaller extends LifeCycle {}

    private Class<? extends SecurityHandler> _defaultSecurityHandlerClass = ConstraintSecurityHandler.class;
    private final ServletContextApi _servletContext;
    protected ContextStatus _contextStatus = ContextStatus.NOTSET;
    private final Map<String, String> _initParams = new HashMap<>();
    private boolean _contextPathDefault = true;
    private String _defaultRequestCharacterEncoding;
    private String _defaultResponseCharacterEncoding;
    private String _contextPathEncoded = "/";
    protected MimeTypes _mimeTypes; // TODO move to core context?
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
    private final List<ServletContextScopeListener> _contextListeners = new CopyOnWriteArrayList<>();
    private final Set<EventListener> _durableListeners = new HashSet<>();
    private Index<ProtectedTargetType> _protectedTargets = Index.empty(false);
    private final List<AliasCheck> _aliasChecks = new CopyOnWriteArrayList<>();

    protected final DecoratedObjectFactory _objFactory;
//    protected Class<? extends SecurityHandler> _defaultSecurityHandlerClass = org.eclipse.jetty.security.ConstraintSecurityHandler.class;
    protected SessionHandler _sessionHandler;
    protected SecurityHandler _securityHandler;
    protected ServletHandler _servletHandler;
    protected int _options;
    protected JspConfigDescriptor _jspConfig;

    private boolean _startListeners;

    public ServletContextHandler()
    {
        this(null, null, null, null, null);
    }

    public ServletContextHandler(String contextPath)
    {
        this(null, contextPath);
    }

    public ServletContextHandler(int options)
    {
        this(null, null, options);
    }

    public ServletContextHandler(Container parent, String contextPath)
    {
        this(parent, contextPath, null, null, null, null);
    }

    public ServletContextHandler(Container parent, String contextPath, int options)
    {
        this(parent, contextPath, null, null, null, null, options);
    }

    public ServletContextHandler(Container parent, String contextPath, boolean sessions, boolean security)
    {
        this(parent, contextPath, (sessions ? SESSIONS : 0) | (security ? SECURITY : 0));
    }

    public ServletContextHandler(Container parent, SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler)
    {
        this(parent, null, sessionHandler, securityHandler, servletHandler, errorHandler);
    }

    public ServletContextHandler(Container parent, String contextPath, SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler)
    {
        this(parent, contextPath, sessionHandler, securityHandler, servletHandler, errorHandler, 0);
    }

    public ServletContextHandler(Container parent, String contextPath, SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler, int options)
    {
        _servletContext = newServletContextApi();
        
        if (File.separatorChar == '/')
            addAliasCheck(new SymlinkAllowedResourceAliasChecker(this));

        if (contextPath != null)
            setContextPath(contextPath);
        if (parent instanceof Handler.Wrapper)
            ((Handler.Wrapper)parent).setHandler(this);
        else if (parent instanceof Collection)
            parent.addHandler(this);

        _options = options;
        _sessionHandler = sessionHandler;
        _securityHandler = securityHandler;
        _servletHandler = servletHandler;

        _objFactory = new DecoratedObjectFactory();
        addBean(_objFactory, true);

        // Link the handlers
        relinkHandlers();

        /*
        TODO: error handling.
        if (errorHandler != null)
            setErrorHandler(errorHandler);
        */
    }
    
    public ServletContextApi newServletContextApi()
    {
        return new ServletContextApi();
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        // TODO almost certainly this is wrong
        super.dump(out, indent);
        dumpObjects(out, indent,
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

    @Override
    public boolean removeEventListener(EventListener listener)
    {
        if (super.removeEventListener(listener))
        {
            if (listener instanceof ServletContextScopeListener)
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

    public Logger getLogger()
    {
        return _logger;
    }

    public void setLogger(Logger logger)
    {
        _logger = logger;
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

    protected void requestInitialized(Request baseRequest, HttpServletRequest request)
    {
        ServletContextRequest scopedRequest = Request.as(baseRequest, ServletContextRequest.class);

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
        ServletContextRequest scopedRequest = Request.as(baseRequest, ServletContextRequest.class);

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

        ClassLoader loader = getClassLoader();
        if (loader == null)
            return Loader.loadClass(className);

        return loader.loadClass(className);
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

        Resource baseResource = getResourceBase();
        if (baseResource == null)
            return null;

        try
        {
            // addPath with accept non-canonical paths that don't go above the root,
            // but will treat them as aliases. So unless allowed by an AliasChecker
            // they will be rejected below.
            Resource resource = baseResource.resolve(pathInContext);

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
     * Listener for all threads entering context scope, including async IO callbacks
     */
    public static interface ServletContextScopeListener extends EventListener
    {
        /**
         * @param context The context being entered
         * @param request A request that is applicable to the scope, or null
         */
        void enterScope(Context context, ServletContextRequest request);

        /**
         * @param context The context being exited
         * @param request A request that is applicable to the scope, or null
         */
        void exitScope(Context context, ServletContextRequest request);
    }

    public ServletContext getServletContext()
    {
        return getContext().getServletContext();
    }

    @Override
    protected ContextHandler.Context newContext()
    {
        return new Context();
    }

    @Override
    public Context getContext()
    {
        return (Context)super.getContext();
    }

    protected void setParent(Container parent)
    {
        if (parent instanceof Handler.Wrapper)
            ((Handler.Wrapper)parent).setHandler(this);
        else if (parent instanceof Collection)
            ((Collection)parent).addHandler(this);
    }

    /**
     * Add a context event listeners.
     *
     * @param listener the event listener to add
     * @return true if the listener was added
     * @see HttpSessionAttributeListener
     * @see HttpSessionActivationListener
     * @see HttpSessionBindingListener
     * @see HttpSessionListener
     * @see HttpSessionIdListener
     * @see ServletContextScopeListener
     * @see ServletContextListener
     * @see ServletContextAttributeListener
     * @see ServletRequestListener
     * @see ServletRequestAttributeListener
     * @see ContextHandler#addEventListener(EventListener)
     */
    @Override
    public boolean addEventListener(EventListener listener)
    {
        if (super.addEventListener(listener))
        {
            if ((listener instanceof HttpSessionActivationListener) ||
                (listener instanceof HttpSessionAttributeListener) ||
                (listener instanceof HttpSessionBindingListener) ||
                (listener instanceof HttpSessionListener) ||
                (listener instanceof HttpSessionIdListener))
            {
                if (_sessionHandler != null)
                    _sessionHandler.addEventListener(listener);
            }

            if (listener instanceof ServletContextScopeListener)
            {
                ContextHandler.Context currentContext = ContextHandler.getCurrentContext();
                _contextListeners.add((ServletContextScopeListener)listener);
                if (currentContext != null)
                    ((ServletContextScopeListener)listener).enterScope(getContext(), null);
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
    public void setHandler(Handler handler)
    {
        if (handler instanceof SessionHandler)
            setSessionHandler((SessionHandler)handler);
        else if (handler instanceof SecurityHandler)
            setSecurityHandler((SecurityHandler)handler);
        else if (handler instanceof ServletHandler)
            setServletHandler((ServletHandler)handler);
        else if (handler instanceof GzipHandler)
            setGzipHandler((GzipHandler)handler);
        else
        {
            if (handler != null)
                LOG.warn("ServletContextHandler.setHandler should not be called directly. Use insertHandler or setSessionHandler etc.");
            super.setHandler(handler);
        }
    }

    private void doSetHandler(Handler.Nested wrapper, Handler handler)
    {
        if (wrapper == this)
            super.setHandler(handler);
        else
            wrapper.setHandler(handler);
    }

    // TODO: review this.
    private void relinkHandlers()
    {
        Handler.Nested handler = this;

        // link session handler
        if (getSessionHandler() != null)
        {
            while (!(handler.getHandler() instanceof SessionHandler) &&
                !(handler.getHandler() instanceof SecurityHandler) &&
                !(handler.getHandler() instanceof ServletHandler) &&
                handler.getHandler() instanceof Handler.Nested)
            {
                handler = (Handler.Nested)handler.getHandler();
            }

            if (handler.getHandler() != _sessionHandler)
                doSetHandler(handler, _sessionHandler);
            handler = _sessionHandler;
        }

        // link security handler
        if (getSecurityHandler() != null)
        {
            while (!(handler.getHandler() instanceof SecurityHandler) &&
                !(handler.getHandler() instanceof ServletHandler) &&
                handler.getHandler() instanceof Handler.Nested)
            {
                handler = (Handler.Nested)handler.getHandler();
            }

            if (handler.getHandler() != _securityHandler)
                doSetHandler(handler, _securityHandler);
            handler = _securityHandler;
        }

        // link servlet handler
        if (getServletHandler() != null)
        {
            while (!(handler.getHandler() instanceof ServletHandler) &&
                handler.getHandler() instanceof Handler.Nested)
            {
                handler = (Handler.Nested)handler.getHandler();
            }

            if (handler.getHandler() != _servletHandler)
                doSetHandler(handler, _servletHandler);
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        getContext().call(() -> 
        {    
            _objFactory.addDecorator(new DeprecationWarning());
            getServletContext().setAttribute(DecoratedObjectFactory.ATTR, _objFactory);

            if (getContextPath() == null)
                throw new IllegalStateException("Null contextPath");

            Resource baseResource = getResourceBase();
            if (baseResource != null && baseResource.isAlias())
                LOG.warn("BaseResource {} is aliased to {} in {}. May not be supported in future releases.",
                    baseResource, baseResource.getAlias(), this);

            if (_logger == null)
                _logger = LoggerFactory.getLogger(ContextHandler.class.getName() + getLogNameSuffix());

            ClassLoader oldClassloader = null;
            Thread currentThread = null;
            ContextHandler.Context oldContext = null;

            // TODO who uses this???
            if (getServer() != null)
                _servletContext.setAttribute("org.eclipse.jetty.server.Executor", getServer().getThreadPool());

            if (_mimeTypes == null)
                _mimeTypes = new MimeTypes();

            _durableListeners.addAll(getEventListeners());

            ClassLoader loader = getClassLoader();
            try
            {
                // Set the classloader, context and enter scope
                if (loader != null)
                {
                    currentThread = Thread.currentThread();
                    oldClassloader = currentThread.getContextClassLoader();
                    currentThread.setContextClassLoader(loader);
                }

                // defers the calling of super.doStart()
                startContext();

                contextInitialized();

                LOG.info("Started {}", this);
            }
            finally
            {
                exitScope(null);
                // reset the classloader
                if (loader != null && currentThread != null)
                    currentThread.setContextClassLoader(oldClassloader);
            }
        }, null);
    }

    @Override
    protected void doStop() throws Exception
    {
        // Should we attempt a graceful shutdown?
        MultiException mex = null;

        ClassLoader oldClassloader = null;
        ClassLoader oldWebapploader = null;
        Thread currentThread = null;

        // TODO: Review.
        enterScope(null);

        Context context = getContext();
        try
        {
            // Set the classloader
            ClassLoader loader = getClassLoader();
            if (loader != null)
            {
                oldWebapploader = loader;
                currentThread = Thread.currentThread();
                oldClassloader = currentThread.getContextClassLoader();
                currentThread.setContextClassLoader(loader);
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

        _objFactory.clear();
        getServletContext().removeAttribute(DecoratedObjectFactory.ATTR);
    }

    @Override
    protected ServletContextRequest wrap(Request request, String pathInContext)
    {
        ServletHandler.MappedServlet mappedServlet = _servletHandler.getMappedServlet(pathInContext);
        if (mappedServlet == null)
            return null;

        // Get a servlet request, possibly from a cached version in the channel attributes.
        // TODO We should cache this heavy weight object!  Something like:
        // TODO: ServletChannel is not properly cleared out so I have disabled the caching of this for now.
        ServletChannel servletChannel = null; // (ServletChannel)request.getComponents().getCache().get("blah.blah.ServletChannel");
        if (servletChannel == null)
        {
            // TODO this may not be the right object to recycle, but ultimately we want to reuse: HttpInput, HttpOutput, ServletChannelState etc. etc.
            servletChannel = new ServletChannel();
            // request.getComponents().getCache().put("blah.blah.ServletChannel", servletChannel); TODO: Re-enable.
        }

        ServletContextRequest servletContextRequest = new ServletContextRequest(_servletContext, servletChannel, request, pathInContext, mappedServlet);
        servletChannel.init(servletContextRequest);
        return servletContextRequest;
    }

    @Override
    protected Request.Processor processByContextHandler(ContextRequest request)
    {
        ServletContextRequest scopedRequest = Request.as(request, ServletContextRequest.class);
        DispatcherType dispatch = scopedRequest.getHttpServletRequest().getDispatcherType();
        if (dispatch == DispatcherType.REQUEST && isProtectedTarget(request.getPathInContext()))
            return (req, resp, cb) -> Response.writeError(req, resp, cb, HttpServletResponse.SC_NOT_FOUND, null);

        return super.processByContextHandler(request);
    }

    @Override
    protected void enterScope(Request request)
    {
        super.enterScope(request);

        ServletContextRequest scopedRequest = Request.as(request, ServletContextRequest.class);
        if (!_contextListeners.isEmpty())
        {
            for (ServletContextScopeListener listener : _contextListeners)
            {
                try
                {
                    listener.enterScope(getContext(), scopedRequest);
                }
                catch (Throwable e)
                {
                    LOG.warn("Unable to enter scope", e);
                }
            }
        }
    }

    @Override
    protected void exitScope(Request request)
    {
        ServletContextRequest scopedRequest = Request.as(request, ServletContextRequest.class);
        if (!_contextListeners.isEmpty())
        {
            for (int i = _contextListeners.size(); i-- > 0; )
            {
                try
                {
                    _contextListeners.get(i).exitScope(getContext(), scopedRequest);
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
     * Get the defaultSecurityHandlerClass.
     *
     * @return the defaultSecurityHandlerClass
     */
    public Class<? extends SecurityHandler> getDefaultSecurityHandlerClass()
    {
        return _defaultSecurityHandlerClass;
    }

    /**
     * Set the defaultSecurityHandlerClass.
     *
     * @param defaultSecurityHandlerClass the defaultSecurityHandlerClass to set
     */
    public void setDefaultSecurityHandlerClass(Class<? extends SecurityHandler> defaultSecurityHandlerClass)
    {
        _defaultSecurityHandlerClass = defaultSecurityHandlerClass;
    }

    protected SessionHandler newSessionHandler()
    {
        return new SessionHandler();
    }

    protected SecurityHandler newSecurityHandler()
    {
        try
        {
            return getDefaultSecurityHandlerClass().getDeclaredConstructor().newInstance();
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    protected ServletHandler newServletHandler()
    {
        return new ServletHandler();
    }

    /**
     * Finish constructing handlers and link them together.
     */
    protected void startContext() throws Exception
    {
        for (ServletContainerInitializerCaller  sci : getBeans(ServletContainerInitializerCaller.class))
        {
            if (sci.isStopped())
            {
                sci.start();
                if (isAuto(sci))
                    manage(sci);
            }
        }

        if (_servletHandler != null)
        {
            //Ensure listener instances are created, added to ContextHandler
            if (_servletHandler.getListeners() != null)
            {
                for (ListenerHolder holder : _servletHandler.getListeners())
                {
                    holder.start();
                }
            }
        }

        _startListeners = true;
        String managedAttributes = _initParams.get(MANAGED_ATTRIBUTES);
        if (managedAttributes != null)
            addEventListener(new ManagedAttributeListener((ServletContextHandler)this, StringUtil.csvSplit(managedAttributes)));

        super.doStart();

        // OK to Initialize servlet handler now that all relevant object trees have been started
        if (_servletHandler != null)
            _servletHandler.initialize();
    }

    protected void stopContext() throws Exception
    {
        _startListeners = false;

        // stop all the handler hierarchy
        super.doStop();
    }

    /**
     * @return Returns the securityHandler.
     */
    @ManagedAttribute(value = "context security handler", readonly = true)
    public SecurityHandler getSecurityHandler()
    {
        if (_securityHandler == null && (_options & SECURITY) != 0 && !isStarted())
            _securityHandler = newSecurityHandler();

        return _securityHandler;
    }

    /**
     * @return Returns the servletHandler.
     */
    @ManagedAttribute(value = "context servlet handler", readonly = true)
    public ServletHandler getServletHandler()
    {
        if (_servletHandler == null && !isStarted())
            _servletHandler = newServletHandler();
        return _servletHandler;
    }

    /**
     * @return Returns the sessionHandler.
     */
    @ManagedAttribute(value = "context session handler", readonly = true)
    public SessionHandler getSessionHandler()
    {
        if (_sessionHandler == null && (_options & SESSIONS) != 0 && !isStarted())
            _sessionHandler = newSessionHandler();
        return _sessionHandler;
    }

    /**
     * Convenience method to add a servlet.
     *
     * @param className the servlet class name
     * @param pathSpec the path spec to map servlet to
     * @return the ServletHolder for the added servlet
     */
    public ServletHolder addServlet(String className, String pathSpec)
    {
        return getServletHandler().addServletWithMapping(className, pathSpec);
    }

    /**
     * Convenience method to add a servlet.
     *
     * @param servlet the servlet class
     * @param pathSpec the path spec to map servlet to
     * @return the ServletHolder for the added servlet
     */
    public ServletHolder addServlet(Class<? extends Servlet> servlet, String pathSpec)
    {
        return getServletHandler().addServletWithMapping(servlet, pathSpec);
    }

    /**
     * Convenience method to add a servlet.
     *
     * @param servlet the servlet holder
     * @param pathSpec the path spec
     */
    public void addServlet(ServletHolder servlet, String pathSpec)
    {
        getServletHandler().addServletWithMapping(servlet, pathSpec);
    }

    /**
     * Convenience method to add a servlet.
     *
     * @param servlet the servlet instance
     * @param pathSpec the path spec
     * @return the ServletHolder for the added servlet
     */
    public ServletHolder addServlet(HttpServlet servlet, String pathSpec)
    {
        ServletHolder servletHolder = new ServletHolder(servlet);
        getServletHandler().addServletWithMapping(servletHolder, pathSpec);
        return servletHolder;
    }

    /**
     * Convenience method to add a filter
     *
     * @param holder the filter holder
     * @param pathSpec the path spec
     * @param dispatches the dispatcher types for this filter
     */
    public void addFilter(FilterHolder holder, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        getServletHandler().addFilterWithMapping(holder, pathSpec, dispatches);
    }

    /**
     * Convenience method to add a filter
     *
     * @param filterClass the filter class
     * @param pathSpec the path spec
     * @param dispatches the dispatcher types for this filter
     * @return the FilterHolder that was created
     */
    public FilterHolder addFilter(Class<? extends Filter> filterClass, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        return getServletHandler().addFilterWithMapping(filterClass, pathSpec, dispatches);
    }

    /**
     * Convenience method to add a filter
     *
     * @param filterClass the filter class name
     * @param pathSpec the path spec
     * @param dispatches the dispatcher types for this filter
     * @return the FilterHolder that was created
     */
    public FilterHolder addFilter(String filterClass, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        return getServletHandler().addFilterWithMapping(filterClass, pathSpec, dispatches);
    }

    /**
     * Convenience method to add a servlet.
     *
     * @param filter the filter instance
     * @param pathSpec the path spec
     * @param dispatches the dispatcher types for this filter
     * @return the FilterHolder that was created
     */
    public FilterHolder addFilter(HttpFilter filter, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        FilterHolder filterHolder = new FilterHolder(filter);
        getServletHandler().addFilterWithMapping(filterHolder, pathSpec, dispatches);
        return filterHolder;
    }

    /**
     * Convenience method to programmatically add a {@link ServletContainerInitializer}.
     * @param sci the ServletContainerInitializer to register.
     * @return the ServletContainerInitializerHolder that was created
     */
    public ServletContainerInitializerHolder addServletContainerInitializer(ServletContainerInitializer sci)
    {
        if (!isStopped())
            throw new IllegalStateException("ServletContainerInitializers should be added before starting");

        ServletContainerInitializerHolder holder = new ServletContainerInitializerHolder(sci);
        addServletContainerInitializer(holder);
        return holder;
    }

    /**
     * Convenience method to programmatically add a {@link ServletContainerInitializer}.
     * @param sci the ServletContainerInitializer to register.
     * @param classes the Set of application classes.
     * @return the ServletContainerInitializerHolder that was created
     */
    public ServletContainerInitializerHolder addServletContainerInitializer(ServletContainerInitializer sci, Class<?>... classes)
    {
        if (!isStopped())
            throw new IllegalStateException("ServletContainerInitializers should be added before starting");

        ServletContainerInitializerHolder holder = new ServletContainerInitializerHolder(sci, classes);
        addServletContainerInitializer(holder);
        return holder;
    }
    
    /**
     * Convenience method to programmatically add a list of {@link ServletContainerInitializer}.
     * The initializers are guaranteed to be called in the order they are passed into this method.
     * @param sciHolders the ServletContainerInitializerHolders
     */
    public void addServletContainerInitializer(ServletContainerInitializerHolder... sciHolders)
    {
        ServletContainerInitializerStarter starter = getBean(ServletContainerInitializerStarter.class);
        if (starter == null)
        {
            //add the starter as bean which will start when the context is started
            //NOTE: do not use addManaged(starter) because this will start the
            //starter immediately, which  may not be before we have parsed web.xml
            starter = new ServletContainerInitializerStarter();
            addBean(starter, true);
        }
        starter.addServletContainerInitializerHolders(sciHolders);
    }

    /**
     * notification that a ServletRegistration has been created so we can track the annotations
     *
     * @param holder new holder created through the api.
     * @return the ServletRegistration.Dynamic
     */
    protected ServletRegistration.Dynamic dynamicHolderAdded(ServletHolder holder)
    {
        return holder.getRegistration();
    }

    /**
     * delegate for ServletContext.declareRole method
     *
     * @param roleNames role names to add
     */
    protected void addRoles(String... roleNames)
    {
        /*
        TODO: implement security.
        //Get a reference to the SecurityHandler, which must be ConstraintAware
        if (_securityHandler != null && _securityHandler instanceof ConstraintAware)
        {
            HashSet<String> union = new HashSet<>();
            Set<String> existing = ((ConstraintAware)_securityHandler).getRoles();
            if (existing != null)
                union.addAll(existing);
            union.addAll(Arrays.asList(roleNames));
            ((ConstraintSecurityHandler)_securityHandler).setRoles(union);
        }
         */
    }

    /**
     * Delegate for ServletRegistration.Dynamic.setServletSecurity method
     *
     * @param registration ServletRegistration.Dynamic instance that setServletSecurity was called on
     * @param servletSecurityElement new security info
     * @return the set of exact URL mappings currently associated with the registration that are also present in the web.xml
     * security constraints and thus will be unaffected by this call.
     */
    public Set<String> setServletSecurity(ServletRegistration.Dynamic registration, ServletSecurityElement servletSecurityElement)
    {
        //Default implementation is to just accept them all. If using a webapp, then this behaviour is overridden in WebAppContext.setServletSecurity       
        java.util.Collection<String> pathSpecs = registration.getMappings();
        if (pathSpecs != null)
        {
            for (String pathSpec : pathSpecs)
            {
                List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath(registration.getName(), pathSpec, servletSecurityElement);
                for (ConstraintMapping m : mappings)
                {
                    ((ConstraintAware)getSecurityHandler()).addConstraintMapping(m);
                }
            }
        }
        return Collections.emptySet();
    }

    public void callContextInitialized(ServletContextListener l, ServletContextEvent e)
    {
        try
        {
            //toggle state of the dynamic API so that the listener cannot use it
            if (isProgrammaticListener(l))
                getContext().getServletContext().setEnabled(false);

            if (getServer().isDryRun())
                return;

            if (LOG.isDebugEnabled())
                LOG.debug("contextInitialized: {}->{}", e, l);
            l.contextInitialized(e);
        }
        finally
        {
            //untoggle the state of the dynamic API
            getContext().getServletContext().setEnabled(true);
        }
    }

    public void callContextDestroyed(ServletContextListener l, ServletContextEvent e)
    {
        if (getServer().isDryRun())
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("contextDestroyed: {}->{}", e, l);
        l.contextDestroyed(e);
    }

    private void replaceHandler(Handler.Nested handler, Handler.Nested replacement)
    {
        if (isStarted())
            throw new IllegalStateException("STARTED");

        Handler next = null;
        if (handler != null)
        {
            next = handler.getHandler();
            handler.setHandler((Handler)null);

            Handler.Wrapper wrapper = this;
            while (wrapper != null)
            {
                if (wrapper.getHandler() == handler)
                {
                    doSetHandler(wrapper, replacement);
                    break;
                }

                wrapper = (wrapper.getHandler() instanceof Handler.Wrapper) ? (Handler.Wrapper)wrapper.getHandler() : null;
            }
        }

        if (next != null && replacement.getHandler() == null)
            replacement.setHandler(next);
    }

    /**
     * @param sessionHandler The sessionHandler to set.
     */
    public void setSessionHandler(SessionHandler sessionHandler)
    {
        replaceHandler(_sessionHandler, sessionHandler);
        _sessionHandler = sessionHandler;
        relinkHandlers();
    }

    /**
     * @param securityHandler The {@link SecurityHandler} to set on this context.
     */
    public void setSecurityHandler(SecurityHandler securityHandler)
    {
        replaceHandler(_securityHandler, securityHandler);
        _securityHandler = securityHandler;
        relinkHandlers();
    }

    /**
     * @param servletHandler The servletHandler to set.
     */
    public void setServletHandler(ServletHandler servletHandler)
    {
        replaceHandler(_servletHandler, servletHandler);
        _servletHandler = servletHandler;
        relinkHandlers();
    }

    /**
     * @param gzipHandler the GzipHandler for this ServletContextHandler
     * @deprecated use {@link #insertHandler(Handler.Nested)} instead
     */
    @Deprecated
    public void setGzipHandler(GzipHandler gzipHandler)
    {
        // TODO remove
        insertHandler(gzipHandler);
        LOG.warn("ServletContextHandler.setGzipHandler(GzipHandler) is deprecated, use insertHandler(HandlerWrapper) instead.");
    }

    /**
     * Insert a HandlerWrapper before the first Session, Security or ServletHandler
     * but after any other HandlerWrappers.
     */
    @Override
    public void insertHandler(Handler.Nested handler)
    {
        if (handler instanceof SessionHandler)
            setSessionHandler((SessionHandler)handler);
        else if (handler instanceof SecurityHandler)
            setSecurityHandler((SecurityHandler)handler);
        else if (handler instanceof ServletHandler)
            setServletHandler((ServletHandler)handler);
        else
        {
            Handler.Nested tail = handler;
            while (tail.getHandler() instanceof Handler.Wrapper)
            {
                tail = (Handler.Wrapper)tail.getHandler();
            }
            if (tail.getHandler() != null)
                throw new IllegalArgumentException("bad tail of inserted wrapper chain");

            // Skip any injected handlers
            Handler.Nested h = this;
            while (h.getHandler() instanceof Handler.Nested wrapper)
            {
                if (wrapper instanceof SessionHandler ||
                    wrapper instanceof SecurityHandler ||
                    wrapper instanceof ServletHandler)
                    break;
                h = wrapper;
            }

            Handler next = h.getHandler();
            doSetHandler(h, handler);
            doSetHandler(tail, next);
        }
        relinkHandlers();
    }

    /**
     * The DecoratedObjectFactory for use by IoC containers (weld / spring / etc)
     *
     * @return The DecoratedObjectFactory
     */
    public DecoratedObjectFactory getObjectFactory()
    {
        return _objFactory;
    }

    void destroyServlet(Servlet servlet)
    {
        getContext().destroy(servlet);
    }

    void destroyFilter(Filter filter)
    {
        getContext().destroy(filter);
    }

    void destroyListener(EventListener listener)
    {
        getContext().destroy(listener);
    }

    public static class JspPropertyGroup implements JspPropertyGroupDescriptor
    {
        private final List<String> _urlPatterns = new ArrayList<>();
        private String _elIgnored;
        private String _pageEncoding;
        private String _scriptingInvalid;
        private String _isXml;
        private final List<String> _includePreludes = new ArrayList<>();
        private final List<String> _includeCodas = new ArrayList<>();
        private String _deferredSyntaxAllowedAsLiteral;
        private String _trimDirectiveWhitespaces;
        private String _defaultContentType;
        private String _buffer;
        private String _errorOnUndeclaredNamespace;

        @Override
        public java.util.Collection<String> getUrlPatterns()
        {
            return new ArrayList<>(_urlPatterns); // spec says must be a copy
        }

        public void addUrlPattern(String s)
        {
            if (!_urlPatterns.contains(s))
                _urlPatterns.add(s);
        }

        @Override
        public String getElIgnored()
        {
            return _elIgnored;
        }

        @Override
        public String getErrorOnELNotFound()
        {
            return "true";
        }

        public void setElIgnored(String s)
        {
            _elIgnored = s;
        }

        @Override
        public String getPageEncoding()
        {
            return _pageEncoding;
        }

        public void setPageEncoding(String pageEncoding)
        {
            _pageEncoding = pageEncoding;
        }

        public void setScriptingInvalid(String scriptingInvalid)
        {
            _scriptingInvalid = scriptingInvalid;
        }

        public void setIsXml(String isXml)
        {
            _isXml = isXml;
        }

        public void setDeferredSyntaxAllowedAsLiteral(String deferredSyntaxAllowedAsLiteral)
        {
            _deferredSyntaxAllowedAsLiteral = deferredSyntaxAllowedAsLiteral;
        }

        public void setTrimDirectiveWhitespaces(String trimDirectiveWhitespaces)
        {
            _trimDirectiveWhitespaces = trimDirectiveWhitespaces;
        }

        public void setDefaultContentType(String defaultContentType)
        {
            _defaultContentType = defaultContentType;
        }

        public void setBuffer(String buffer)
        {
            _buffer = buffer;
        }

        public void setErrorOnUndeclaredNamespace(String errorOnUndeclaredNamespace)
        {
            _errorOnUndeclaredNamespace = errorOnUndeclaredNamespace;
        }

        @Override
        public String getScriptingInvalid()
        {
            return _scriptingInvalid;
        }

        @Override
        public String getIsXml()
        {
            return _isXml;
        }

        @Override
        public java.util.Collection getIncludePreludes()
        {
            return new ArrayList<>(_includePreludes); //must be a copy
        }

        public void addIncludePrelude(String prelude)
        {
            if (!_includePreludes.contains(prelude))
                _includePreludes.add(prelude);
        }

        @Override
        public java.util.Collection getIncludeCodas()
        {
            return new ArrayList<>(_includeCodas); //must be a copy
        }

        public void addIncludeCoda(String coda)
        {
            if (!_includeCodas.contains(coda))
                _includeCodas.add(coda);
        }

        @Override
        public String getDeferredSyntaxAllowedAsLiteral()
        {
            return _deferredSyntaxAllowedAsLiteral;
        }

        @Override
        public String getTrimDirectiveWhitespaces()
        {
            return _trimDirectiveWhitespaces;
        }

        @Override
        public String getDefaultContentType()
        {
            return _defaultContentType;
        }

        @Override
        public String getBuffer()
        {
            return _buffer;
        }

        @Override
        public String getErrorOnUndeclaredNamespace()
        {
            return _errorOnUndeclaredNamespace;
        }

        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder();
            sb.append("JspPropertyGroupDescriptor:");
            sb.append(" el-ignored=").append(_elIgnored);
            sb.append(" is-xml=").append(_isXml);
            sb.append(" page-encoding=").append(_pageEncoding);
            sb.append(" scripting-invalid=").append(_scriptingInvalid);
            sb.append(" deferred-syntax-allowed-as-literal=").append(_deferredSyntaxAllowedAsLiteral);
            sb.append(" trim-directive-whitespaces").append(_trimDirectiveWhitespaces);
            sb.append(" default-content-type=").append(_defaultContentType);
            sb.append(" buffer=").append(_buffer);
            sb.append(" error-on-undeclared-namespace=").append(_errorOnUndeclaredNamespace);
            for (String prelude : _includePreludes)
            {
                sb.append(" include-prelude=").append(prelude);
            }
            for (String coda : _includeCodas)
            {
                sb.append(" include-coda=").append(coda);
            }
            return sb.toString();
        }
    }

    public static class TagLib implements TaglibDescriptor
    {
        private String _uri;
        private String _location;

        @Override
        public String getTaglibURI()
        {
            return _uri;
        }

        public void setTaglibURI(String uri)
        {
            _uri = uri;
        }

        @Override
        public String getTaglibLocation()
        {
            return _location;
        }

        public void setTaglibLocation(String location)
        {
            _location = location;
        }

        @Override
        public String toString()
        {
            return ("TagLibDescriptor: taglib-uri=" + _uri + " location=" + _location);
        }
    }

    public static class JspConfig implements JspConfigDescriptor
    {
        private final List<TaglibDescriptor> _taglibs = new ArrayList<>();
        private final List<JspPropertyGroupDescriptor> _jspPropertyGroups = new ArrayList<>();

        public JspConfig()
        {
        }

        @Override
        public java.util.Collection getTaglibs()
        {
            return new ArrayList<>(_taglibs);
        }

        public void addTaglibDescriptor(TaglibDescriptor d)
        {
            _taglibs.add(d);
        }

        @Override
        public java.util.Collection getJspPropertyGroups()
        {
            return new ArrayList<>(_jspPropertyGroups);
        }

        public void addJspPropertyGroup(JspPropertyGroupDescriptor g)
        {
            _jspPropertyGroups.add(g);
        }

        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder();
            sb.append("JspConfigDescriptor: \n");
            for (TaglibDescriptor taglib : _taglibs)
            {
                sb.append(taglib).append("\n");
            }
            for (JspPropertyGroupDescriptor jpg : _jspPropertyGroups)
            {
                sb.append(jpg).append("\n");
            }
            return sb.toString();
        }
    }

    public class Context extends ContextHandler.Context
    {
        public ServletContextApi getServletContext()
        {
            return _servletContext;
        }

        public ServletContextHandler getServletContextHandler()
        {
            return ServletContextHandler.this;
        }

        @Override
        protected DecoratedObjectFactory getDecoratedObjectFactory()
        {
            return _objFactory;
        }

        public <T> T createInstance(BaseHolder<T> holder) throws ServletException
        {
            try
            {
                //set a thread local
                DecoratedObjectFactory.associateInfo(holder);
                try
                {
                    T t = holder.getHeldClass().getDeclaredConstructor().newInstance();
                    return decorate(t);
                }
                catch (Exception e)
                {
                    throw new ServletException(e);
                }
            }
            finally
            {
                //unset the thread local
                DecoratedObjectFactory.disassociateInfo();
            }
        }

        public <T extends Filter> void destroyFilter(T f)
        {
            _objFactory.destroy(f);
        }

        public <T extends Servlet> void destroyServlet(T s)
        {
            _objFactory.destroy(s);
        }

        public void setExtendedListenerTypes(boolean b)
        {
            _servletContext.setExtendedListenerTypes(b);
        }
    }

    public class ServletContextApi implements ServletContext
    {
        public static final int SERVLET_MAJOR_VERSION = 6;
        public static final int SERVLET_MINOR_VERSION = 0;

        private int _effectiveMajorVersion = SERVLET_MAJOR_VERSION;
        private int _effectiveMinorVersion = SERVLET_MINOR_VERSION;
        protected boolean _enabled = true; // whether or not the dynamic API is enabled for callers
        protected boolean _extendedListenerTypes = false;

        public ServletContextApi()
        {
        }

        @Override
        public String getServerInfo()
        {
            return getServer().getServerInfo();
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

        public Context getContext()
        {
            return ServletContextHandler.this.getContext();
        }

        @Override
        public RequestDispatcher getNamedDispatcher(String name)
        {
            ServletContextHandler context = ServletContextHandler.this;
            if (_servletHandler == null)
                return null;
            ServletHolder holder = _servletHandler.getServlet(name);
            if (holder == null || !holder.isEnabled())
                return null;
            return new Dispatcher(context, name);
        }

        private void checkDynamic(String name)
        {
            if (isStarted())
                throw new IllegalStateException();
            
            if (ServletContextHandler.this.getServletHandler().isInitialized())
                throw new IllegalStateException();

            if (StringUtil.isBlank(name))
                throw new IllegalArgumentException("Missing name");

            if (!_enabled)
                throw new UnsupportedOperationException();
        }

        /**
         * @since servlet-api-3.0
         */
        @Override
        public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass)
        {
            checkDynamic(filterName);

            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            FilterHolder holder = handler.getFilter(filterName);
            if (holder == null)
            {
                //new filter
                holder = handler.newFilterHolder(Source.JAVAX_API);
                holder.setName(filterName);
                holder.setHeldClass(filterClass);
                handler.addFilter(holder);
                return holder.getRegistration();
            }
            if (holder.getClassName() == null && holder.getHeldClass() == null)
            {
                //preliminary filter registration completion
                holder.setHeldClass(filterClass);
                return holder.getRegistration();
            }
            else
                return null; //existing filter
        }

        /**
         * @since servlet-api-3.0
         */
        @Override
        public FilterRegistration.Dynamic addFilter(String filterName, String className)
        {
            checkDynamic(filterName);

            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            FilterHolder holder = handler.getFilter(filterName);
            if (holder == null)
            {
                //new filter
                holder = handler.newFilterHolder(Source.JAVAX_API);
                holder.setName(filterName);
                holder.setClassName(className);
                handler.addFilter(holder);
                return holder.getRegistration();
            }
            if (holder.getClassName() == null && holder.getHeldClass() == null)
            {
                //preliminary filter registration completion
                holder.setClassName(className);
                return holder.getRegistration();
            }
            else
                return null; //existing filter
        }

        /**
         * @since servlet-api-3.0
         */
        @Override
        public FilterRegistration.Dynamic addFilter(String filterName, Filter filter)
        {
            checkDynamic(filterName);

            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            FilterHolder holder = handler.getFilter(filterName);
            if (holder == null)
            {
                //new filter
                holder = handler.newFilterHolder(Source.JAVAX_API);
                holder.setName(filterName);
                holder.setFilter(filter);
                handler.addFilter(holder);
                return holder.getRegistration();
            }

            if (holder.getClassName() == null && holder.getHeldClass() == null)
            {
                //preliminary filter registration completion
                holder.setFilter(filter);
                return holder.getRegistration();
            }
            else
                return null; //existing filter
        }

        /**
         * @since servlet-api-3.0
         */
        @Override
        public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass)
        {
            checkDynamic(servletName);

            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            ServletHolder holder = handler.getServlet(servletName);
            if (holder == null)
            {
                //new servlet
                holder = handler.newServletHolder(Source.JAVAX_API);
                holder.setName(servletName);
                holder.setHeldClass(servletClass);
                handler.addServlet(holder);
                return dynamicHolderAdded(holder);
            }

            //complete a partial registration
            if (holder.getClassName() == null && holder.getHeldClass() == null)
            {
                holder.setHeldClass(servletClass);
                return holder.getRegistration();
            }
            else
                return null; //existing completed registration for servlet name
        }

        /**
         * @since servlet-api-3.0
         */
        @Override
        public ServletRegistration.Dynamic addServlet(String servletName, String className)
        {
            checkDynamic(servletName);

            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            ServletHolder holder = handler.getServlet(servletName);
            if (holder == null)
            {
                //new servlet
                holder = handler.newServletHolder(Source.JAVAX_API);
                holder.setName(servletName);
                holder.setClassName(className);
                handler.addServlet(holder);
                return dynamicHolderAdded(holder);
            }

            //complete a partial registration
            if (holder.getClassName() == null && holder.getHeldClass() == null)
            {
                holder.setClassName(className);
                return holder.getRegistration();
            }
            else
                return null; //existing completed registration for servlet name
        }

        /**
         * @since servlet-api-3.0
         */
        @Override
        public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet)
        {
            checkDynamic(servletName);

            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            ServletHolder holder = handler.getServlet(servletName);
            if (holder == null)
            {
                holder = handler.newServletHolder(Source.JAVAX_API);
                holder.setName(servletName);
                holder.setServlet(servlet);
                handler.addServlet(holder);
                return dynamicHolderAdded(holder);
            }

            //complete a partial registration
            if (holder.getClassName() == null && holder.getHeldClass() == null)
            {
                holder.setServlet(servlet);
                return holder.getRegistration();
            }
            else
                return null; //existing completed registration for servlet name
        }
        
        @Override
        public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile)
        {
            checkDynamic(servletName);
            
            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            ServletHolder holder = handler.getServlet(servletName);
            if (holder == null)
            {
                //new servlet
                holder = handler.newServletHolder(Source.JAVAX_API);
                holder.setName(servletName);
                holder.setForcedPath(jspFile);
                handler.addServlet(holder);
                return dynamicHolderAdded(holder);
            }
            
            //complete a partial registration
            if (holder.getClassName() == null && holder.getHeldClass() == null && holder.getForcedPath() == null)
            {
                holder.setForcedPath(jspFile);
                return holder.getRegistration();
            }
            else
                return null; //existing completed registration for servlet name
        }

        public String getInitParameter(String name)
        {
            //since servlet spec 4.0
            Objects.requireNonNull(name);
            return ServletContextHandler.this.getInitParameter(name);
        }

        public boolean setInitParameter(String name, String value)
        {
            //since servlet spec 4.0
            Objects.requireNonNull(name);

            if (!isStarting())
                throw new IllegalStateException();

            if (!_enabled)
                throw new UnsupportedOperationException();

            if (ServletContextHandler.this.getInitParameter(name) != null)
                return false;
            ServletContextHandler.this.getInitParams().put(name, value);
            return true;
        }

        public <T> T createInstance(Class<T> clazz) throws ServletException
        {
            try
            {
                T result = getContext().decorate(clazz.getDeclaredConstructor().newInstance());
                return result;
            }
            catch (Exception e)
            {
                throw new ServletException(e);
            }
        }

        @Override
        public Set<SessionTrackingMode> getDefaultSessionTrackingModes()
        {
            if (!_enabled)
                throw new UnsupportedOperationException();
            
            if (_sessionHandler != null)
                return _sessionHandler.getDefaultSessionTrackingModes();
            return null;
        }

        @Override
        public Set<SessionTrackingMode> getEffectiveSessionTrackingModes()
        {
            if (!_enabled)
                throw new UnsupportedOperationException();
            
            if (_sessionHandler != null)
                return _sessionHandler.getEffectiveSessionTrackingModes();
            return null;
        }

        @Override
        public FilterRegistration getFilterRegistration(String filterName)
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            final FilterHolder holder = ServletContextHandler.this.getServletHandler().getFilter(filterName);
            return (holder == null) ? null : holder.getRegistration();
        }

        @Override
        public Map<String, ? extends FilterRegistration> getFilterRegistrations()
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            HashMap<String, FilterRegistration> registrations = new HashMap<>();
            ServletHandler handler = ServletContextHandler.this.getServletHandler();
            FilterHolder[] holders = handler.getFilters();
            if (holders != null)
            {
                for (FilterHolder holder : holders)
                {
                    registrations.put(holder.getName(), holder.getRegistration());
                }
            }
            return registrations;
        }

        @Override
        public ServletRegistration getServletRegistration(String servletName)
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            final ServletHolder holder = ServletContextHandler.this.getServletHandler().getServlet(servletName);
            return (holder == null) ? null : holder.getRegistration();
        }

        @Override
        public Map<String, ? extends ServletRegistration> getServletRegistrations()
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            HashMap<String, ServletRegistration> registrations = new HashMap<>();
            ServletHandler handler = ServletContextHandler.this.getServletHandler();
            ServletHolder[] holders = handler.getServlets();
            if (holders != null)
            {
                for (ServletHolder holder : holders)
                {
                    registrations.put(holder.getName(), holder.getRegistration());
                }
            }
            return registrations;
        }

        @Override
        public SessionCookieConfig getSessionCookieConfig()
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            if (_sessionHandler != null)
                return _sessionHandler.getSessionCookieConfig();
            return null;
        }

        @Override
        public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes)
        {
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();

            if (_sessionHandler != null)
                _sessionHandler.setSessionTrackingModes(sessionTrackingModes);
        }

        @Override
        public int getSessionTimeout()
        {
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();

            int timeout = -1;
            if (_sessionHandler != null)
            {
                timeout = _sessionHandler.getMaxInactiveInterval();
            }

            return (int)TimeUnit.SECONDS.toMinutes(timeout);
        }

        @Override
        public void setSessionTimeout(int sessionTimeout)
        {
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();

            if (_sessionHandler != null)
            {
                long tmp = TimeUnit.MINUTES.toSeconds(sessionTimeout);
                if (tmp > Integer.MAX_VALUE)
                    tmp = Integer.MAX_VALUE;
                if (tmp < Integer.MIN_VALUE)
                    tmp = Integer.MIN_VALUE;
                _sessionHandler.setMaxInactiveInterval((int)tmp);
            }
        }
        
        public <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException
        {
            if (!_enabled)
                throw new UnsupportedOperationException();
            try
            {
                return createInstance(clazz);
            }
            catch (Exception e)
            {
                throw new ServletException(e);
            }
        }

        public <T extends Filter> T createFilter(Class<T> clazz) throws ServletException
        {
            if (!_enabled)
                throw new UnsupportedOperationException();
            try
            {
                return createInstance(clazz);
            }
            catch (Exception e)
            {
                throw new ServletException(e);
            }
        }
        
        public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException
        {
            if (!_enabled)
                throw new UnsupportedOperationException();
            try
            {
                checkListener(clazz);
            }
            catch (IllegalArgumentException e)
            {
                //Bizarrely, according to the spec, it is NOT an error to create an instance of
                //a ServletContextListener from inside a ServletContextListener, but it IS an error
                //to call addListener with one!
                if (!ServletContextListener.class.isAssignableFrom(clazz))
                    throw e;
            }
            try
            {
                return createInstance(clazz);
            }
            catch (Exception e)
            {
                throw new ServletException(e);
            }
        }

        public void addListener(String className)
        {
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();

            try
            {
                ClassLoader classLoader = ServletContextHandler.this.getClassLoader();
                @SuppressWarnings({"unchecked", "rawtypes"})
                Class<? extends EventListener> clazz = classLoader == null ? Loader.loadClass(className) : (Class)classLoader.loadClass(className);
                if (!_enabled)
                    throw new UnsupportedOperationException();

                try
                {
                    EventListener result;
                    try
                    {
                        result = clazz.getDeclaredConstructor().newInstance();
                    }
                    catch (Exception e)
                    {
                        throw new ServletException(e);
                    }
                    EventListener el = result;
                    addListener(el);
                }
                catch (ServletException e)
                {
                    throw new IllegalArgumentException(e);
                }
            }
            catch (ClassNotFoundException e)
            {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public <T extends EventListener> void addListener(T t)
        {
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();

            checkListener(t.getClass());

            ListenerHolder holder = getServletHandler().newListenerHolder(Source.JAVAX_API);
            holder.setListener(t);
            addProgrammaticListener(t);
            getServletHandler().addListener(holder);
            if (_startListeners)
            {
                try
                {
                    holder.start();   
                }
                catch (Exception e)
                {
                    throw new IllegalStateException(e);
                }
            }
        }

        public void addListener(Class<? extends EventListener> listenerClass)
        {
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();

            try
            {
                EventListener result;
                try
                {
                    result = listenerClass.getDeclaredConstructor().newInstance();
                }
                catch (Exception e)
                {
                    throw new ServletException(e);
                }
                EventListener el = result;
                addListener(el);
            }
            catch (ServletException e)
            {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public JspConfigDescriptor getJspConfigDescriptor()
        {
            return _jspConfig;
        }

        public void setJspConfigDescriptor(JspConfigDescriptor d)
        {
            _jspConfig = d;
        }

        @Override
        public void declareRoles(String... roleNames)
        {
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();
            addRoles(roleNames);
        }

        @Override
        public String getRequestCharacterEncoding()
        {
            return getDefaultRequestCharacterEncoding();
        }

        @Override
        public void setRequestCharacterEncoding(String encoding)
        {
            if (!isStarting())
                throw new IllegalStateException();
            
            setDefaultRequestCharacterEncoding(encoding);
        }

        @Override
        public String getResponseCharacterEncoding()
        {
            return getDefaultResponseCharacterEncoding();
        }

        @Override
        public void setResponseCharacterEncoding(String encoding)
        {
            if (!isStarting())
                throw new IllegalStateException();
            
            setDefaultResponseCharacterEncoding(encoding);
        }

        public ContextHandler getContextHandler()
        {
            return ServletContextHandler.this;
        }

        @Override
        public ServletContext getContext(String uripath)
        {
            List<ServletContextHandler> contexts = new ArrayList<>();
            List<ServletContextHandler> handlers = getServer().getDescendants(ServletContextHandler.class);
            String matchedPath = null;

            for (ServletContextHandler ch : handlers)
            {
                if (ch == null)
                    continue;
                String contextPath = ch.getContextPath();

                if (uripath.equals(contextPath) ||
                    (uripath.startsWith(contextPath) && uripath.charAt(contextPath.length()) == '/') ||
                    "/".equals(contextPath))
                {
                    // look first for vhost matching context only
                    if (getVirtualHosts() != null && getVirtualHosts().size() > 0)
                    {
                        if (ch.getVirtualHosts() != null && ch.getVirtualHosts().size() > 0)
                        {
                            for (String h1 : getVirtualHosts())
                            {
                                for (String h2 : ch.getVirtualHosts())
                                {
                                    if (h1.equals(h2))
                                    {
                                        if (matchedPath == null || contextPath.length() > matchedPath.length())
                                        {
                                            contexts.clear();
                                            matchedPath = contextPath;
                                        }

                                        if (matchedPath.equals(contextPath))
                                            contexts.add(ch);
                                    }
                                }
                            }
                        }
                    }
                    else
                    {
                        if (matchedPath == null || contextPath.length() > matchedPath.length())
                        {
                            contexts.clear();
                            matchedPath = contextPath;
                        }

                        if (matchedPath.equals(contextPath))
                            contexts.add(ch);
                    }
                }
            }

            if (contexts.size() > 0)
                return contexts.get(0).getServletContext();

            // try again ignoring virtual hosts
            matchedPath = null;
            for (ServletContextHandler ch : handlers)
            {
                if (ch == null)
                    continue;
                String contextPath = ch.getContextPath();

                if (uripath.equals(contextPath) || (uripath.startsWith(contextPath) && uripath.charAt(contextPath.length()) == '/') || "/".equals(contextPath))
                {
                    if (matchedPath == null || contextPath.length() > matchedPath.length())
                    {
                        contexts.clear();
                        matchedPath = contextPath;
                    }

                    if (matchedPath.equals(contextPath))
                        contexts.add(ch);
                }
            }

            if (contexts.size() > 0)
                return contexts.get(0).getServletContext();
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
                return new Dispatcher(ServletContextHandler.this, uri, pathInfo);
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
                Resource resource = ServletContextHandler.this.getResource(path);
                if (resource != null)
                {
                    Path file = resource.getPath();
                    if (file != null)
                        return file.normalize().toString();
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
            Resource resource = ServletContextHandler.this.getResource(path);
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
            return ServletContextHandler.this.getResourcePaths(path);
        }

        @Override
        public void log(String msg)
        {
            getLogger().info(msg);
        }

        @Override
        public void log(String message, Throwable throwable)
        {
            if (throwable == null)
                getLogger().warn(message);
            else
                getLogger().warn(message, throwable);
        }

        @Override
        public Enumeration<String> getInitParameterNames()
        {
            return ServletContextHandler.this.getInitParameterNames();
        }

        @Override
        public Object getAttribute(String name)
        {
            return getContext().getAttribute(name);
        }

        @Override
        public Enumeration<String> getAttributeNames()
        {
            return Collections.enumeration(getContext().getAttributeNameSet());
        }

        @Override
        public void setAttribute(String name, Object value)
        {
            Object oldValue = getContext().setAttribute(name, value);

            if (!_servletContextAttributeListeners.isEmpty())
            {
                ServletContextAttributeEvent event = new ServletContextAttributeEvent(_servletContext, name, oldValue == null ? value : oldValue);

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
            Object oldValue = getContext().removeAttribute(name);
            if (oldValue != null && !_servletContextAttributeListeners.isEmpty())
            {
                ServletContextAttributeEvent event = new ServletContextAttributeEvent(_servletContext, name, oldValue);
                for (ServletContextAttributeListener listener : _servletContextAttributeListeners)
                {
                    listener.attributeRemoved(event);
                }
            }
        }

        @Override
        public String getServletContextName()
        {
            String name = ServletContextHandler.this.getDisplayName();
            if (name == null)
                name = ServletContextHandler.this.getContextPath();
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
            return "ServletContext@" + ServletContextHandler.this.toString();
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

        @Override
        public ClassLoader getClassLoader()
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            // no security manager just return the classloader
            ClassLoader classLoader = ServletContextHandler.this.getClassLoader();
            if (!isUsingSecurityManager())
            {
                return classLoader;
            }
            else
            {
                // check to see if the classloader of the caller is the same as the context
                // classloader, or a parent of it, as required by the javadoc specification.

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
            List<String> hosts = getVirtualHosts();
            if (hosts != null && !hosts.isEmpty())
                return hosts.get(0);
            return null;
        }
    }

    /**
     * Bean that is added to the ServletContextHandler to start all of the
     * ServletContainerInitializers by starting their corresponding
     * ServletContainerInitializerHolders when this bean is itself started.
     * Note that the SCIs will be started in order of addition.
     */
    public static class ServletContainerInitializerStarter extends ContainerLifeCycle implements ServletContainerInitializerCaller
    {
        public void addServletContainerInitializerHolders(ServletContainerInitializerHolder... holders)
        {
            for (ServletContainerInitializerHolder holder:holders)
                addBean(holder, true);
        }
        
        public java.util.Collection getServletContainerInitializerHolders()
        {
            return getContainedBeans(ServletContainerInitializerHolder.class);
        }

        @Override
        protected void doStart() throws Exception
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Starting SCIs");
            super.doStart();
        }

        @Override
        protected void doStop() throws Exception
        {
            //remove all of the non-programmatic holders
            java.util.Collection<ServletContainerInitializerHolder> holders = getServletContainerInitializerHolders();
            for (ServletContainerInitializerHolder h : holders)
            {
                if (h.getSource().getOrigin() != Source.Origin.EMBEDDED)
                    removeBean(h);
            }
            super.doStop();
        }
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
