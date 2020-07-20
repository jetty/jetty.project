//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterRegistration;
import javax.servlet.FilterRegistration.Dynamic;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletRequestAttributeListener;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;
import javax.servlet.descriptor.JspConfigDescriptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.ClassLoaderDump;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

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
 * By default, the context is created with alias checkers for {@link AllowSymLinkAliasChecker} (unix only) and {@link ApproveNonExistentDirectoryAliases}. If
 * these alias checkers are not required, then {@link #clearAliasChecks()} or {@link #setAliasChecks(List)} should be called.
 * </p>
 */
@ManagedObject("URI Context")
public class ContextHandler extends ScopedHandler implements Attributes, Graceful
{
    public static final int SERVLET_MAJOR_VERSION = 3;
    public static final int SERVLET_MINOR_VERSION = 1;
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

    private static final Logger LOG = Log.getLogger(ContextHandler.class);

    private static final ThreadLocal<Context> __context = new ThreadLocal<>();

    private static String __serverInfo = "jetty/" + Server.getVersion();

    /**
     * If a context attribute with this name is set, it is interpreted as a comma separated list of attribute name. Any other context attributes that are set
     * with a name from this list will result in a call to {@link #setManagedAttribute(String, Object)}, which typically initiates the creation of a JMX MBean
     * for the attribute value.
     */
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
    public static Context getCurrentContext()
    {
        return __context.get();
    }

    public static ContextHandler getContextHandler(ServletContext context)
    {
        if (context instanceof ContextHandler.Context)
            return ((ContextHandler.Context)context).getContextHandler();
        Context c = getCurrentContext();
        if (c != null)
            return c.getContextHandler();
        return null;
    }

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
    
    protected ContextStatus _contextStatus = ContextStatus.NOTSET;
    protected Context _scontext;
    private final AttributesMap _attributes;
    private final Map<String, String> _initParams;
    private ClassLoader _classLoader;
    private String _contextPath = "/";
    private String _contextPathEncoded = "/";

    private String _displayName;

    private Resource _baseResource;
    private MimeTypes _mimeTypes;
    private Map<String, String> _localeEncodingMap;
    private String[] _welcomeFiles;
    private ErrorHandler _errorHandler;

    private String[] _vhosts; // Host name portion, matching _vconnectors array
    private boolean[] _vhostswildcard;
    private String[] _vconnectors; // connector portion, matching _vhosts array

    private Logger _logger;
    private boolean _allowNullPathInfo;
    private int _maxFormKeys = Integer.getInteger(MAX_FORM_KEYS_KEY, DEFAULT_MAX_FORM_KEYS);
    private int _maxFormContentSize = Integer.getInteger(MAX_FORM_CONTENT_SIZE_KEY, DEFAULT_MAX_FORM_CONTENT_SIZE);
    private boolean _compactPath = false;
    private boolean _usingSecurityManager = System.getSecurityManager() != null;

    private final List<EventListener> _eventListeners = new CopyOnWriteArrayList<>();
    private final List<EventListener> _programmaticListeners = new CopyOnWriteArrayList<>();
    private final List<ServletContextListener> _servletContextListeners = new CopyOnWriteArrayList<>();
    private final List<ServletContextListener> _destroyServletContextListeners = new ArrayList<>();
    private final List<ServletContextAttributeListener> _servletContextAttributeListeners = new CopyOnWriteArrayList<>();
    private final List<ServletRequestListener> _servletRequestListeners = new CopyOnWriteArrayList<>();
    private final List<ServletRequestAttributeListener> _servletRequestAttributeListeners = new CopyOnWriteArrayList<>();
    private final List<ContextScopeListener> _contextListeners = new CopyOnWriteArrayList<>();
    private final List<EventListener> _durableListeners = new CopyOnWriteArrayList<>();
    private String[] _protectedTargets;
    private final CopyOnWriteArrayList<AliasCheck> _aliasChecks = new CopyOnWriteArrayList<>();

    public enum Availability
    {
        UNAVAILABLE, STARTING, AVAILABLE, SHUTDOWN,
    }

    private volatile Availability _availability = Availability.UNAVAILABLE;

    public ContextHandler()
    {
        this(null, null, null);
    }

    protected ContextHandler(Context context)
    {
        this(context, null, null);
    }

    public ContextHandler(String contextPath)
    {
        this(null, null, contextPath);
    }

    public ContextHandler(HandlerContainer parent, String contextPath)
    {
        this(null, parent, contextPath);
    }

    private ContextHandler(Context context, HandlerContainer parent, String contextPath)
    {
        _scontext = context == null ? new Context() : context;
        _attributes = new AttributesMap();
        _initParams = new HashMap<>();
        addAliasCheck(new ApproveNonExistentDirectoryAliases());
        if (File.separatorChar == '/')
            addAliasCheck(new AllowSymLinkAliasChecker());

        if (contextPath != null)
            setContextPath(contextPath);
        if (parent instanceof HandlerWrapper)
            ((HandlerWrapper)parent).setHandler(this);
        else if (parent instanceof HandlerCollection)
            ((HandlerCollection)parent).addHandler(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent,
            new ClassLoaderDump(getClassLoader()),
            new DumpableCollection("eventListeners " + this, _eventListeners),
            new DumpableCollection("handler attributes " + this, ((AttributesMap)getAttributes()).getAttributeEntrySet()),
            new DumpableCollection("context attributes " + this, getServletContext().getAttributeEntrySet()),
            new DumpableCollection("initparams " + this, getInitParams().entrySet()));
    }

    public Context getServletContext()
    {
        return _scontext;
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
     * Set the virtual hosts for the context. Only requests that have a matching host header or fully qualified URL will be passed to that context with a
     * virtual host name. A context with no virtual host names or a null virtual host name is available to all requests that are not served by a context with a
     * matching virtual host name.
     *
     * @param vhosts Array of virtual hosts that this context responds to. A null/empty array means any hostname is acceptable. Host names may be String
     * representation of IP addresses. Host names may start with '*.' to wildcard one level of names. Hosts and wildcard hosts may be followed with
     * '@connectorname', in which case they will match only if the the {@link Connector#getName()} for the request also matches. If an entry is just
     * '@connectorname' it will match any host if that connector was used.  Note - In previous versions if one or more connectorname only entries existed
     * and non of the connectors matched the handler would not match regardless of any hostname entries.  If there is one or more connectorname only
     * entries and one or more host only entries but no hostname and connector entries we assume the old behavior and will log a warning.  The warning
     * can be removed by removing the host entries that were previously being ignored, or modifying to include a hostname and connectorname entry.
     */
    public void setVirtualHosts(String[] vhosts)
    {

        if (vhosts == null)
        {
            _vhosts = vhosts;
        }
        else
        {

            boolean hostMatch = false;
            boolean connectorHostMatch = false;
            _vhosts = new String[vhosts.length];
            _vconnectors = new String[vhosts.length];
            _vhostswildcard = new boolean[vhosts.length];
            ArrayList<Integer> connectorOnlyIndexes = null;
            for (int i = 0; i < vhosts.length; i++)
            {
                boolean connectorMatch = false;
                _vhosts[i] = vhosts[i];
                if (vhosts[i] == null)
                    continue;
                int connectorIndex = _vhosts[i].indexOf('@');
                if (connectorIndex >= 0)
                {
                    connectorMatch = true;
                    _vconnectors[i] = _vhosts[i].substring(connectorIndex + 1);
                    _vhosts[i] = _vhosts[i].substring(0, connectorIndex);
                    if (connectorIndex == 0)
                    {
                        if (connectorOnlyIndexes == null)
                            connectorOnlyIndexes = new ArrayList<>();
                        connectorOnlyIndexes.add(i);
                    }
                }

                if (_vhosts[i].startsWith("*."))
                {
                    _vhosts[i] = _vhosts[i].substring(1);
                    _vhostswildcard[i] = true;
                }
                if (_vhosts[i].isEmpty())
                    _vhosts[i] = null;
                else
                {
                    hostMatch = true;
                    connectorHostMatch = connectorHostMatch || connectorMatch;
                }
                _vhosts[i] = normalizeHostname(_vhosts[i]);
            }

            if (connectorOnlyIndexes != null && hostMatch && !connectorHostMatch)
            {
                LOG.warn("ContextHandler {} has a connector only entry e.g. \"@connector\" and one or more host only entries. \n" +
                        "The host entries will be ignored to match legacy behavior.  " +
                        "To clear this warning remove the host entries or update to use " +
                        "at least one host@connector syntax entry that will match a host for an specific connector",
                    Arrays.asList(vhosts));
                String[] filteredHosts = new String[connectorOnlyIndexes.size()];
                for (int i = 0; i < connectorOnlyIndexes.size(); i++)
                {
                    filteredHosts[i] = vhosts[connectorOnlyIndexes.get(i)];
                }
                setVirtualHosts(filteredHosts);
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

        if (_vhosts == null)
        {
            setVirtualHosts(virtualHosts);
        }
        else
        {
            Set<String> currentVirtualHosts = new HashSet<>(Arrays.asList(getVirtualHosts()));
            for (String vh : virtualHosts)
            {
                currentVirtualHosts.add(normalizeHostname(vh));
            }
            setVirtualHosts(currentVirtualHosts.toArray(new String[0]));
        }
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
        if (virtualHosts == null || virtualHosts.length == 0 || _vhosts == null || _vhosts.length == 0)
            return; // do nothing

        Set<String> existingVirtualHosts = new HashSet<>(Arrays.asList(getVirtualHosts()));
        for (String vh : virtualHosts)
        {
            existingVirtualHosts.remove(normalizeHostname(vh));
        }
        if (existingVirtualHosts.isEmpty())
            setVirtualHosts(null); // if we ended up removing them all, just null out _vhosts
        else
            setVirtualHosts(existingVirtualHosts.toArray(new String[0]));
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
    public String[] getVirtualHosts()
    {
        if (_vhosts == null)
            return null;

        String[] vhosts = new String[_vhosts.length];
        for (int i = 0; i < _vhosts.length; i++)
        {
            StringBuilder sb = new StringBuilder();
            if (_vhostswildcard[i])
                sb.append("*");
            if (_vhosts[i] != null)
                sb.append(_vhosts[i]);
            if (_vconnectors[i] != null)
                sb.append("@").append(_vconnectors[i]);
            vhosts[i] = sb.toString();
        }
        return vhosts;
    }

    /*
     * @see javax.servlet.ServletContext#getAttribute(java.lang.String)
     */
    @Override
    public Object getAttribute(String name)
    {
        return _attributes.getAttribute(name);
    }

    /*
     * @see javax.servlet.ServletContext#getAttributeNames()
     */
    @Override
    public Enumeration<String> getAttributeNames()
    {
        return AttributesMap.getAttributeNamesCopy(_attributes);
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        return _attributes.getAttributeNameSet();
    }

    /**
     * @return Returns the attributes.
     */
    public Attributes getAttributes()
    {
        return _attributes;
    }

    /**
     * @return Returns the classLoader.
     */
    public ClassLoader getClassLoader()
    {
        return _classLoader;
    }

    /**
     * Make best effort to extract a file classpath from the context classloader
     *
     * @return Returns the classLoader.
     */
    @ManagedAttribute("The file classpath")
    public String getClassPath()
    {
        if (_classLoader == null || !(_classLoader instanceof URLClassLoader))
            return null;
        URLClassLoader loader = (URLClassLoader)_classLoader;
        URL[] urls = loader.getURLs();
        StringBuilder classpath = new StringBuilder();
        for (int i = 0; i < urls.length; i++)
        {
            try
            {
                Resource resource = newResource(urls[i]);
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
                LOG.debug(e);
            }
        }
        if (classpath.length() == 0)
            return null;
        return classpath.toString();
    }

    /**
     * @return Returns the contextPath.
     */
    @ManagedAttribute("True if URLs are compacted to replace the multiple '/'s with a single '/'")
    public String getContextPath()
    {
        return _contextPath;
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
     * @return Returns the encoded contextPath, or empty string for root context
     */
    public String getRequestContextPath()
    {
        String contextPathEncoded = getContextPathEncoded();
        return "/".equals(contextPathEncoded) ? "" : contextPathEncoded;
    }

    /*
     * @see javax.servlet.ServletContext#getInitParameter(java.lang.String)
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
     * @see javax.servlet.ServletContext#getInitParameterNames()
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
     * @see javax.servlet.ServletContext#getServletContextName()
     */
    @ManagedAttribute(value = "Display name of the Context", readonly = true)
    public String getDisplayName()
    {
        return _displayName;
    }

    public EventListener[] getEventListeners()
    {
        return _eventListeners.toArray(new EventListener[0]);
    }

    /**
     * Set the context event listeners.
     *
     * @param eventListeners the event listeners
     * @see ServletContextListener
     * @see ServletContextAttributeListener
     * @see ServletRequestListener
     * @see ServletRequestAttributeListener
     */
    public void setEventListeners(EventListener[] eventListeners)
    {
        _contextListeners.clear();
        _servletContextListeners.clear();
        _servletContextAttributeListeners.clear();
        _servletRequestListeners.clear();
        _servletRequestAttributeListeners.clear();
        _eventListeners.clear();

        if (eventListeners != null)
            for (EventListener listener : eventListeners)
            {
                addEventListener(listener);
            }
    }

    /**
     * Add a context event listeners.
     *
     * @param listener the event listener to add
     * @see ServletContextListener
     * @see ServletContextAttributeListener
     * @see ServletRequestListener
     * @see ServletRequestAttributeListener
     */
    public void addEventListener(EventListener listener)
    {
        _eventListeners.add(listener);

        if (!(isStarted() || isStarting()))
        {
            _durableListeners.add(listener);
        }

        if (listener instanceof ContextScopeListener)
        {
            _contextListeners.add((ContextScopeListener)listener);
            if (__context.get() != null)
                ((ContextScopeListener)listener).enterScope(__context.get(), null, "Listener registered");
        }

        if (listener instanceof ServletContextListener)
            _servletContextListeners.add((ServletContextListener)listener);

        if (listener instanceof ServletContextAttributeListener)
            _servletContextAttributeListeners.add((ServletContextAttributeListener)listener);

        if (listener instanceof ServletRequestListener)
            _servletRequestListeners.add((ServletRequestListener)listener);

        if (listener instanceof ServletRequestAttributeListener)
            _servletRequestAttributeListeners.add((ServletRequestAttributeListener)listener);
    }

    /**
     * Remove a context event listeners.
     *
     * @param listener the event listener to remove
     * @see ServletContextListener
     * @see ServletContextAttributeListener
     * @see ServletRequestListener
     * @see ServletRequestAttributeListener
     */
    public void removeEventListener(EventListener listener)
    {
        _eventListeners.remove(listener);

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
        return _durableListeners.contains(listener);
    }

    /**
     * @return true if this context is shutting down
     */
    @ManagedAttribute("true for graceful shutdown, which allows existing requests to complete")
    public boolean isShutdown()
    {
        return _availability == Availability.SHUTDOWN;
    }

    /**
     * Set shutdown status. This field allows for graceful shutdown of a context. A started context may be put into non accepting state so that existing
     * requests can complete, but no new requests are accepted.
     */
    @Override
    public Future<Void> shutdown()
    {
        _availability = isRunning() ? Availability.SHUTDOWN : Availability.UNAVAILABLE;
        return new FutureCallback(true);
    }

    /**
     * @return false if this context is unavailable (sends 503)
     */
    public boolean isAvailable()
    {
        return _availability == Availability.AVAILABLE;
    }

    /**
     * Set Available status.
     *
     * @param available true to set as enabled
     */
    public void setAvailable(boolean available)
    {
        synchronized (this)
        {
            if (available && isRunning())
                _availability = Availability.AVAILABLE;
            else if (!available || !isRunning())
                _availability = Availability.UNAVAILABLE;
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

    /*
     * @see org.eclipse.thread.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        _availability = Availability.STARTING;

        if (_contextPath == null)
            throw new IllegalStateException("Null contextPath");

        if (_logger == null)
            _logger = Log.getLogger(ContextHandler.class.getName() + getLogNameSuffix());

        ClassLoader oldClassloader = null;
        Thread currentThread = null;
        Context oldContext = null;

        _attributes.setAttribute("org.eclipse.jetty.server.Executor", getServer().getThreadPool());

        if (_mimeTypes == null)
            _mimeTypes = new MimeTypes();

        try
        {
            // Set the classloader, context and enter scope
            if (_classLoader != null)
            {
                currentThread = Thread.currentThread();
                oldClassloader = currentThread.getContextClassLoader();
                currentThread.setContextClassLoader(_classLoader);
            }
            oldContext = __context.get();
            __context.set(_scontext);
            enterScope(null, getState());

            // defers the calling of super.doStart()
            startContext();
            
            contextInitialized();

            _availability = Availability.AVAILABLE;
            LOG.info("Started {}", this);
        }
        finally
        {
            if (_availability == Availability.STARTING)
                _availability = Availability.UNAVAILABLE;
            exitScope(null);
            __context.set(oldContext);
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
     * @see org.eclipse.jetty.server.handler.ContextHandler.Context
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
     * 
     * @throws Exception
     */
    public void contextInitialized() throws Exception
    {
        // Call context listeners
        switch (_contextStatus)
        {
            case NOTSET:
            {
                try
                {
                    _destroyServletContextListeners.clear();
                    if (!_servletContextListeners.isEmpty())
                    {
                        ServletContextEvent event = new ServletContextEvent(_scontext);
                        for (ServletContextListener listener : _servletContextListeners)
                        {
                            callContextInitialized(listener, event);
                            _destroyServletContextListeners.add(listener);
                        }
                    }
                }
                finally
                {
                    _contextStatus = ContextStatus.INITIALIZED;
                }
                break;
            }
            default:
                break;
        }
    }

    /**
     * Call the ServletContextListeners with contextDestroyed.
     * This method can be called from a ServletHandler in the
     * proper sequence of destroying filters, servlets and listeners.
     * If there is no ServletHandler, the ContextHandler must ensure
     * these listeners are called instead.
     * 
     * @throws Exception
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
                    ServletContextEvent event = new ServletContextEvent(_scontext);
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
        if (LOG.isDebugEnabled())
            LOG.debug("contextInitialized: {}->{}", e, l);
        l.contextInitialized(e);
    }

    protected void callContextDestroyed(ServletContextListener l, ServletContextEvent e)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("contextDestroyed: {}->{}", e, l);
        l.contextDestroyed(e);
    }

    @Override
    protected void doStop() throws Exception
    {
        // Should we attempt a graceful shutdown?
        MultiException mex = null;

        if (getStopTimeout() > 0)
        {
            try
            {
                doShutdown(null);
            }
            catch (MultiException e)
            {
                mex = e;
            }
        }

        _availability = Availability.UNAVAILABLE;

        ClassLoader oldClassloader = null;
        ClassLoader oldWebapploader = null;
        Thread currentThread = null;
        Context oldContext = __context.get();
        enterScope(null, "doStop");
        __context.set(_scontext);
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
            setEventListeners(_durableListeners.toArray(new EventListener[0]));
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
                        ((ContextScopeListener)l).exitScope(_scontext, null);
                    }
                    catch (Throwable e)
                    {
                        LOG.warn(e);
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
            __context.set(oldContext);
            exitScope(null);
            LOG.info("Stopped {}", this);
            // reset the classloader
            if ((oldClassloader == null || (oldClassloader != oldWebapploader)) && currentThread != null)
                currentThread.setContextClassLoader(oldClassloader);

            _scontext.clearAttributes();
        }

        if (mex != null)
            mex.ifExceptionThrow();
    }

    public boolean checkVirtualHost(final Request baseRequest)
    {
        if (_vhosts == null || _vhosts.length == 0)
            return true;

        String vhost = normalizeHostname(baseRequest.getServerName());
        String connectorName = baseRequest.getHttpChannel().getConnector().getName();

        for (int i = 0; i < _vhosts.length; i++)
        {
            String contextVhost = _vhosts[i];
            String contextVConnector = _vconnectors[i];

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
                if (_vhostswildcard[i])
                {
                    // wildcard only at the beginning, and only for one additional subdomain level
                    int index = vhost.indexOf(".");
                    if (index >= 0 && vhost.substring(index).equalsIgnoreCase(contextVhost))
                    {
                        return true;
                    }
                }
                else if (vhost.equalsIgnoreCase(contextVhost))
                {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean checkContextPath(String uri)
    {
        // Are we not the root context?
        if (_contextPath.length() > 1)
        {
            // reject requests that are not for us
            if (!uri.startsWith(_contextPath))
                return false;
            return uri.length() <= _contextPath.length() || uri.charAt(_contextPath.length()) == '/';
        }
        return true;
    }

    /*
     * @see org.eclipse.jetty.server.Handler#handle(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    public boolean checkContext(final String target, final Request baseRequest, final HttpServletResponse response) throws IOException
    {
        DispatcherType dispatch = baseRequest.getDispatcherType();

        // Check the vhosts
        if (!checkVirtualHost(baseRequest))
            return false;

        if (!checkContextPath(target))
            return false;

        // Are we not the root context?
        // redirect null path infos
        if (!_allowNullPathInfo && _contextPath.length() == target.length() && _contextPath.length() > 1)
        {
            // context request must end with /
            baseRequest.setHandled(true);
            if (baseRequest.getQueryString() != null)
                response.sendRedirect(baseRequest.getRequestURI() + "/?" + baseRequest.getQueryString());
            else
                response.sendRedirect(baseRequest.getRequestURI() + "/");
            return false;
        }

        switch (_availability)
        {
            case SHUTDOWN:
            case UNAVAILABLE:
                baseRequest.setHandled(true);
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return false;
            default:
                if ((DispatcherType.REQUEST.equals(dispatch) && baseRequest.isHandled()))
                    return false;
        }

        return true;
    }

    /**
     * @see org.eclipse.jetty.server.handler.ScopedHandler#doScope(java.lang.String, org.eclipse.jetty.server.Request, javax.servlet.http.HttpServletRequest,
     * javax.servlet.http.HttpServletResponse)
     */
    @Override
    public void doScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("scope {}|{}|{} @ {}", baseRequest.getContextPath(), baseRequest.getServletPath(), baseRequest.getPathInfo(), this);

        Context oldContext;
        String oldContextPath = null;
        String oldServletPath = null;
        String oldPathInfo = null;
        ClassLoader oldClassloader = null;
        Thread currentThread = null;
        String pathInfo = target;

        DispatcherType dispatch = baseRequest.getDispatcherType();

        oldContext = baseRequest.getContext();

        // Are we already in this context?
        if (oldContext != _scontext)
        {
            // check the target.
            if (DispatcherType.REQUEST.equals(dispatch) || DispatcherType.ASYNC.equals(dispatch))
            {
                if (isCompactPath())
                    target = URIUtil.compactPath(target);
                if (!checkContext(target, baseRequest, response))
                    return;

                if (target.length() > _contextPath.length())
                {
                    if (_contextPath.length() > 1)
                        target = target.substring(_contextPath.length());
                    pathInfo = target;
                }
                else if (_contextPath.length() == 1)
                {
                    target = URIUtil.SLASH;
                    pathInfo = URIUtil.SLASH;
                }
                else
                {
                    target = URIUtil.SLASH;
                    pathInfo = null;
                }
            }

            // Set the classloader
            if (_classLoader != null)
            {
                currentThread = Thread.currentThread();
                oldClassloader = currentThread.getContextClassLoader();
                currentThread.setContextClassLoader(_classLoader);
            }
        }

        try
        {
            oldContextPath = baseRequest.getContextPath();
            oldServletPath = baseRequest.getServletPath();
            oldPathInfo = baseRequest.getPathInfo();

            // Update the paths
            baseRequest.setContext(_scontext);
            __context.set(_scontext);
            if (!DispatcherType.INCLUDE.equals(dispatch) && target.startsWith("/"))
            {
                baseRequest.setContextPath(getRequestContextPath());
                baseRequest.setServletPath(null);
                baseRequest.setPathInfo(pathInfo);
            }

            if (oldContext != _scontext)
                enterScope(baseRequest, dispatch);

            if (LOG.isDebugEnabled())
                LOG.debug("context={}|{}|{} @ {}", baseRequest.getContextPath(), baseRequest.getServletPath(), baseRequest.getPathInfo(), this);

            nextScope(target, baseRequest, request, response);
        }
        finally
        {
            if (oldContext != _scontext)
            {
                exitScope(baseRequest);

                // reset the classloader
                if (_classLoader != null && currentThread != null)
                {
                    currentThread.setContextClassLoader(oldClassloader);
                }

                // reset the context and servlet path.
                baseRequest.setContext(oldContext);
                __context.set(oldContext);
                baseRequest.setContextPath(oldContextPath);
                baseRequest.setServletPath(oldServletPath);
                baseRequest.setPathInfo(oldPathInfo);
            }
        }
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
            final ServletRequestEvent sre = new ServletRequestEvent(_scontext, request);
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
            final ServletRequestEvent sre = new ServletRequestEvent(_scontext, request);
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

    /**
     * @see org.eclipse.jetty.server.handler.ScopedHandler#doHandle(java.lang.String, org.eclipse.jetty.server.Request, javax.servlet.http.HttpServletRequest,
     * javax.servlet.http.HttpServletResponse)
     */
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
                    listener.enterScope(_scontext, request, reason);
                }
                catch (Throwable e)
                {
                    LOG.warn(e);
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
                    _contextListeners.get(i).exitScope(_scontext, request);
                }
                catch (Throwable e)
                {
                    LOG.warn(e);
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
        Context oldContext = __context.get();

        // Are we already in the scope?
        if (oldContext == _scontext)
        {
            runnable.run();
            return;
        }

        // Nope, so enter the scope and then exit
        try
        {
            __context.set(_scontext);

            // Set the classloader
            if (_classLoader != null)
            {
                currentThread = Thread.currentThread();
                oldClassloader = currentThread.getContextClassLoader();
                currentThread.setContextClassLoader(_classLoader);
            }

            enterScope(request, runnable);
            runnable.run();
        }
        finally
        {
            exitScope(request);

            __context.set(oldContext);
            if (oldClassloader != null)
            {
                currentThread.setContextClassLoader(oldClassloader);
            }
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
        if (target == null || _protectedTargets == null)
            return false;

        while (target.startsWith("//"))
        {
            target = URIUtil.compactPath(target);
        }

        for (int i = 0; i < _protectedTargets.length; i++)
        {
            String t = _protectedTargets[i];
            if (StringUtil.startsWithIgnoreCase(target, t))
            {
                if (target.length() == t.length())
                    return true;

                // Check that the target prefix really is a path segment, thus
                // it can end with /, a query, a target or a parameter
                char c = target.charAt(t.length());
                if (c == '/' || c == '?' || c == '#' || c == ';')
                    return true;
            }
        }
        return false;
    }

    /**
     * @param targets Array of URL prefix. Each prefix is in the form /path and will match either /path exactly or /path/anything
     */
    public void setProtectedTargets(String[] targets)
    {
        if (targets == null)
        {
            _protectedTargets = null;
            return;
        }

        _protectedTargets = Arrays.copyOf(targets, targets.length);
    }

    public String[] getProtectedTargets()
    {
        if (_protectedTargets == null)
            return null;

        return Arrays.copyOf(_protectedTargets, _protectedTargets.length);
    }

    /*
     * @see javax.servlet.ServletContext#removeAttribute(java.lang.String)
     */
    @Override
    public void removeAttribute(String name)
    {
        _attributes.removeAttribute(name);
    }

    /*
     * Set a context attribute. Attributes set via this API cannot be overridden by the ServletContext.setAttribute API. Their lifecycle spans the stop/start of
     * a context. No attribute listener events are triggered by this API.
     *
     * @see javax.servlet.ServletContext#setAttribute(java.lang.String, java.lang.Object)
     */
    @Override
    public void setAttribute(String name, Object value)
    {
        _attributes.setAttribute(name, value);
    }

    /**
     * @param attributes The attributes to set.
     */
    public void setAttributes(Attributes attributes)
    {
        _attributes.clearAttributes();
        _attributes.addAll(attributes);
    }

    @Override
    public void clearAttributes()
    {
        _attributes.clearAttributes();
    }

    @Deprecated
    public void setManagedAttribute(String name, Object value)
    {
    }

    /**
     * @param classLoader The classLoader to set.
     */
    public void setClassLoader(ClassLoader classLoader)
    {
        _classLoader = classLoader;
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
            LOG.warn(this + " contextPath ends with /*");
            contextPath = contextPath.substring(0, contextPath.length() - 2);
        }
        else if (contextPath.length() > 1 && contextPath.endsWith("/"))
        {
            LOG.warn(this + " contextPath ends with /");
            contextPath = contextPath.substring(0, contextPath.length() - 1);
        }

        if (contextPath.length() == 0)
        {
            LOG.warn("Empty contextPath");
            contextPath = "/";
        }

        _contextPath = contextPath;
        _contextPathEncoded = URIUtil.encodePath(contextPath);

        if (getServer() != null && (getServer().isStarting() || getServer().isStarted()))
        {
            Class<ContextHandlerCollection> handlerClass = ContextHandlerCollection.class;
            Handler[] contextCollections = getServer().getChildHandlersByClass(handlerClass);
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
        _baseResource = base;
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
            LOG.warn(e.toString());
            LOG.debug(e);
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
     */
    public boolean isCompactPath()
    {
        return _compactPath;
    }

    /**
     * @param compactPath True if URLs are compacted to replace multiple '/'s with a single '/'
     */
    public void setCompactPath(boolean compactPath)
    {
        _compactPath = compactPath;
    }

    @Override
    public String toString()
    {
        String[] vhosts = getVirtualHosts();

        StringBuilder b = new StringBuilder();

        Package pkg = getClass().getPackage();
        if (pkg != null)
        {
            String p = pkg.getName();
            if (p != null && p.length() > 0)
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
        b.append(getContextPath()).append(',').append(getBaseResource()).append(',').append(_availability);

        if (vhosts != null && vhosts.length > 0)
            b.append(',').append(vhosts[0]);
        b.append('}');

        return b.toString();
    }

    public synchronized Class<?> loadClass(String className) throws ClassNotFoundException
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

    public Resource getResource(String path) throws MalformedURLException
    {
        if (path == null || !path.startsWith(URIUtil.SLASH))
            throw new MalformedURLException(path);

        if (_baseResource == null)
            return null;

        try
        {
            path = URIUtil.canonicalPath(path);
            Resource resource = _baseResource.addPath(path);

            if (checkAlias(path, resource))
                return resource;
            return null;
        }
        catch (Exception e)
        {
            LOG.ignore(e);
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
                LOG.debug("Aliased resource: " + resource + "~=" + resource.getAlias());

            // alias checks
            for (Iterator<AliasCheck> i = getAliasChecks().iterator(); i.hasNext(); )
            {
                AliasCheck check = i.next();
                if (check.check(path, resource))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Aliased resource: " + resource + " approved by " + check);
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
            path = URIUtil.canonicalPath(path);
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
            LOG.ignore(e);
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
        getAliasChecks().add(check);
    }

    /**
     * @return Mutable list of Alias checks
     */
    public List<AliasCheck> getAliasChecks()
    {
        return _aliasChecks;
    }

    /**
     * @param checks list of AliasCheck instances
     */
    public void setAliasChecks(List<AliasCheck> checks)
    {
        getAliasChecks().clear();
        getAliasChecks().addAll(checks);
    }

    /**
     * clear the list of AliasChecks
     */
    public void clearAliasChecks()
    {
        getAliasChecks().clear();
    }

    /**
     * Context.
     * <p>
     * A partial implementation of {@link javax.servlet.ServletContext}. A complete implementation is provided by the
     * derived {@link ContextHandler} implementations.
     * </p>
     */
    public class Context extends StaticContext
    {
        protected boolean _enabled = true; // whether or not the dynamic API is enabled for callers
        protected boolean _extendedListenerTypes = false;

        protected Context()
        {
        }

        public ContextHandler getContextHandler()
        {
            return ContextHandler.this;
        }

        /*
         * @see javax.servlet.ServletContext#getContext(java.lang.String)
         */
        @Override
        public ServletContext getContext(String uripath)
        {
            List<ContextHandler> contexts = new ArrayList<>();
            Handler[] handlers = getServer().getChildHandlersByClass(ContextHandler.class);
            String matchedPath = null;

            for (Handler handler : handlers)
            {
                if (handler == null)
                    continue;
                ContextHandler ch = (ContextHandler)handler;
                String contextPath = ch.getContextPath();

                if (uripath.equals(contextPath) ||
                    (uripath.startsWith(contextPath) && uripath.charAt(contextPath.length()) == '/') ||
                    "/".equals(contextPath))
                {
                    // look first for vhost matching context only
                    if (getVirtualHosts() != null && getVirtualHosts().length > 0)
                    {
                        if (ch.getVirtualHosts() != null && ch.getVirtualHosts().length > 0)
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
                return contexts.get(0)._scontext;

            // try again ignoring virtual hosts
            matchedPath = null;
            for (Handler handler : handlers)
            {
                if (handler == null)
                    continue;
                ContextHandler ch = (ContextHandler)handler;
                String contextPath = ch.getContextPath();

                if (uripath.equals(contextPath) ||
                    (uripath.startsWith(contextPath) && uripath.charAt(contextPath.length()) == '/') ||
                    "/".equals(contextPath))
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
                return contexts.get(0)._scontext;
            return null;
        }

        /*
         * @see javax.servlet.ServletContext#getMimeType(java.lang.String)
         */
        @Override
        public String getMimeType(String file)
        {
            if (_mimeTypes == null)
                return null;
            return _mimeTypes.getMimeByExtension(file);
        }

        /*
         * @see javax.servlet.ServletContext#getRequestDispatcher(java.lang.String)
         */
        @Override
        public RequestDispatcher getRequestDispatcher(String uriInContext)
        {
            // uriInContext is encoded, potentially with query

            if (uriInContext == null)
                return null;

            if (!uriInContext.startsWith("/"))
                return null;

            try
            {
                HttpURI uri = new HttpURI(null, null, 0, uriInContext);

                String pathInfo = URIUtil.canonicalPath(uri.getDecodedPath());
                if (pathInfo == null)
                    return null;

                String contextPath = getContextPath();
                if (contextPath != null && contextPath.length() > 0)
                    uri.setPath(URIUtil.addPaths(contextPath, uri.getPath()));

                return new Dispatcher(ContextHandler.this, uri, pathInfo);
            }
            catch (Exception e)
            {
                LOG.ignore(e);
            }
            return null;
        }

        /*
         * @see javax.servlet.ServletContext#getRealPath(java.lang.String)
         */
        @Override
        public String getRealPath(String path)
        {
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
                LOG.ignore(e);
            }

            return null;
        }

        @Override
        public URL getResource(String path) throws MalformedURLException
        {
            Resource resource = ContextHandler.this.getResource(path);
            if (resource != null && resource.exists())
                return resource.getURI().toURL();
            return null;
        }

        /*
         * @see javax.servlet.ServletContext#getResourceAsStream(java.lang.String)
         */
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
                LOG.ignore(e);
                return null;
            }
        }

        /*
         * @see javax.servlet.ServletContext#getResourcePaths(java.lang.String)
         */
        @Override
        public Set<String> getResourcePaths(String path)
        {
            return ContextHandler.this.getResourcePaths(path);
        }

        /*
         * @see javax.servlet.ServletContext#log(java.lang.Exception, java.lang.String)
         */
        @Override
        public void log(Exception exception, String msg)
        {
            _logger.warn(msg, exception);
        }

        /*
         * @see javax.servlet.ServletContext#log(java.lang.String)
         */
        @Override
        public void log(String msg)
        {
            _logger.info(msg);
        }

        /*
         * @see javax.servlet.ServletContext#log(java.lang.String, java.lang.Throwable)
         */
        @Override
        public void log(String message, Throwable throwable)
        {
            _logger.warn(message, throwable);
        }

        /*
         * @see javax.servlet.ServletContext#getInitParameter(java.lang.String)
         */
        @Override
        public String getInitParameter(String name)
        {
            return ContextHandler.this.getInitParameter(name);
        }

        /*
         * @see javax.servlet.ServletContext#getInitParameterNames()
         */
        @Override
        public Enumeration<String> getInitParameterNames()
        {
            return ContextHandler.this.getInitParameterNames();
        }

        /*
         * @see javax.servlet.ServletContext#getAttribute(java.lang.String)
         */
        @Override
        public synchronized Object getAttribute(String name)
        {
            Object o = ContextHandler.this.getAttribute(name);
            if (o == null)
                o = super.getAttribute(name);
            return o;
        }

        /*
         * @see javax.servlet.ServletContext#getAttributeNames()
         */
        @Override
        public synchronized Enumeration<String> getAttributeNames()
        {
            HashSet<String> set = new HashSet<>();
            Enumeration<String> e = super.getAttributeNames();
            while (e.hasMoreElements())
            {
                set.add(e.nextElement());
            }
            e = _attributes.getAttributeNames();
            while (e.hasMoreElements())
            {
                set.add(e.nextElement());
            }

            return Collections.enumeration(set);
        }

        /*
         * @see javax.servlet.ServletContext#setAttribute(java.lang.String, java.lang.Object)
         */
        @Override
        public synchronized void setAttribute(String name, Object value)
        {
            Object oldValue = super.getAttribute(name);

            if (value == null)
                super.removeAttribute(name);
            else
                super.setAttribute(name, value);

            if (!_servletContextAttributeListeners.isEmpty())
            {
                ServletContextAttributeEvent event = new ServletContextAttributeEvent(_scontext, name, oldValue == null ? value : oldValue);

                for (ServletContextAttributeListener l : _servletContextAttributeListeners)
                {
                    if (oldValue == null)
                        l.attributeAdded(event);
                    else if (value == null)
                        l.attributeRemoved(event);
                    else
                        l.attributeReplaced(event);
                }
            }
        }

        /*
         * @see javax.servlet.ServletContext#removeAttribute(java.lang.String)
         */
        @Override
        public synchronized void removeAttribute(String name)
        {
            Object oldValue = super.getAttribute(name);
            super.removeAttribute(name);
            if (oldValue != null && !_servletContextAttributeListeners.isEmpty())
            {
                ServletContextAttributeEvent event = new ServletContextAttributeEvent(_scontext, name, oldValue);

                for (ServletContextAttributeListener l : _servletContextAttributeListeners)
                {
                    l.attributeRemoved(event);
                }
            }
        }

        /*
         * @see javax.servlet.ServletContext#getServletContextName()
         */
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
                @SuppressWarnings(
                    {"unchecked", "rawtypes"})
                Class<? extends EventListener> clazz = _classLoader == null ? Loader.loadClass(className) : (Class<? extends EventListener>)_classLoader.loadClass(className);
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

        @Override
        public ClassLoader getClassLoader()
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            // no security manager just return the classloader
            if (!isUsingSecurityManager())
            {
                return _classLoader;
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
                    if (callerLoader == _classLoader)
                        return _classLoader;
                    else
                        callerLoader = callerLoader.getParent();
                }
                System.getSecurityManager().checkPermission(new RuntimePermission("getClassLoader"));
                return _classLoader;
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
     * A simple implementation of ServletContext that is used when there is no
     * ContextHandler.  This is also used as the base for all other ServletContext
     * implementations.
     */
    public static class StaticContext extends AttributesMap implements ServletContext
    {
        private int _effectiveMajorVersion = SERVLET_MAJOR_VERSION;
        private int _effectiveMinorVersion = SERVLET_MINOR_VERSION;

        @Override
        public ServletContext getContext(String uripath)
        {
            return null;
        }

        @Override
        public int getMajorVersion()
        {
            return SERVLET_MAJOR_VERSION;
        }

        @Override
        public String getMimeType(String file)
        {
            return null;
        }

        @Override
        public int getMinorVersion()
        {
            return SERVLET_MINOR_VERSION;
        }

        @Override
        public RequestDispatcher getNamedDispatcher(String name)
        {
            return null;
        }

        @Override
        public RequestDispatcher getRequestDispatcher(String uriInContext)
        {
            return null;
        }

        @Override
        public String getRealPath(String path)
        {
            return null;
        }

        @Override
        public URL getResource(String path) throws MalformedURLException
        {
            return null;
        }

        @Override
        public InputStream getResourceAsStream(String path)
        {
            return null;
        }

        @Override
        public Set<String> getResourcePaths(String path)
        {
            return null;
        }

        @Override
        public String getServerInfo()
        {
            return ContextHandler.getServerInfo();
        }

        @Override
        @Deprecated
        public Servlet getServlet(String name) throws ServletException
        {
            return null;
        }

        @SuppressWarnings("unchecked")
        @Override
        @Deprecated
        public Enumeration<String> getServletNames()
        {
            return Collections.enumeration(Collections.EMPTY_LIST);
        }

        @SuppressWarnings("unchecked")
        @Override
        @Deprecated
        public Enumeration<Servlet> getServlets()
        {
            return Collections.enumeration(Collections.EMPTY_LIST);
        }

        @Override
        public void log(Exception exception, String msg)
        {
            LOG.warn(msg, exception);
        }

        @Override
        public void log(String msg)
        {
            LOG.info(msg);
        }

        @Override
        public void log(String message, Throwable throwable)
        {
            LOG.warn(message, throwable);
        }

        @Override
        public String getInitParameter(String name)
        {
            return null;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Enumeration<String> getInitParameterNames()
        {
            return Collections.enumeration(Collections.EMPTY_LIST);
        }

        @Override
        public String getServletContextName()
        {
            return "No Context";
        }

        @Override
        public String getContextPath()
        {
            return null;
        }

        @Override
        public boolean setInitParameter(String name, String value)
        {
            return false;
        }

        @Override
        public Dynamic addFilter(String filterName, Class<? extends Filter> filterClass)
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "addFilter(String, Class)");
            return null;
        }

        @Override
        public Dynamic addFilter(String filterName, Filter filter)
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "addFilter(String, Filter)");
            return null;
        }

        @Override
        public Dynamic addFilter(String filterName, String className)
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "addFilter(String, String)");
            return null;
        }

        @Override
        public javax.servlet.ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass)
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "addServlet(String, Class)");
            return null;
        }

        @Override
        public javax.servlet.ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet)
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "addServlet(String, Servlet)");
            return null;
        }

        @Override
        public javax.servlet.ServletRegistration.Dynamic addServlet(String servletName, String className)
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "addServlet(String, String)");
            return null;
        }

        @Override
        public Set<SessionTrackingMode> getDefaultSessionTrackingModes()
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getDefaultSessionTrackingModes()");
            return null;
        }

        @Override
        public Set<SessionTrackingMode> getEffectiveSessionTrackingModes()
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getEffectiveSessionTrackingModes()");
            return null;
        }

        @Override
        public FilterRegistration getFilterRegistration(String filterName)
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getFilterRegistration(String)");
            return null;
        }

        @Override
        public Map<String, ? extends FilterRegistration> getFilterRegistrations()
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getFilterRegistrations()");
            return null;
        }

        @Override
        public ServletRegistration getServletRegistration(String servletName)
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getServletRegistration(String)");
            return null;
        }

        @Override
        public Map<String, ? extends ServletRegistration> getServletRegistrations()
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getServletRegistrations()");
            return null;
        }

        @Override
        public SessionCookieConfig getSessionCookieConfig()
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getSessionCookieConfig()");
            return null;
        }

        @Override
        public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes)
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "setSessionTrackingModes(Set<SessionTrackingMode>)");
        }

        @Override
        public void addListener(String className)
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "addListener(String)");
        }

        @Override
        public <T extends EventListener> void addListener(T t)
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "addListener(T)");
        }

        @Override
        public void addListener(Class<? extends EventListener> listenerClass)
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "addListener(Class)");
        }

        protected <T> T createInstance(Class<T> clazz) throws ServletException
        {
            try
            {
                return clazz.getDeclaredConstructor().newInstance();
            }
            catch (Exception e)
            {
                throw new ServletException(e);
            }
        }

        @Override
        public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException
        {
            return createInstance(clazz);
        }

        @Override
        public <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException
        {
            return createInstance(clazz);
        }

        @Override
        public <T extends Filter> T createFilter(Class<T> clazz) throws ServletException
        {
            return createInstance(clazz);
        }

        @Override
        public ClassLoader getClassLoader()
        {
            return ContextHandler.class.getClassLoader();
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

        @Override
        public JspConfigDescriptor getJspConfigDescriptor()
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getJspConfigDescriptor()");
            return null;
        }

        @Override
        public void declareRoles(String... roleNames)
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "declareRoles(String...)");
        }

        @Override
        public String getVirtualServerName()
        {
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
         * @param path The path the aliased resource was created for
         * @param resource The aliased resourced
         * @return True if the resource is OK to be served.
         */
        boolean check(String path, Resource resource);
    }

    /**
     * Approve all aliases.
     */
    public static class ApproveAliases implements AliasCheck
    {
        @Override
        public boolean check(String path, Resource resource)
        {
            return true;
        }
    }

    /**
     * Approve Aliases of a non existent directory. If a directory "/foobar/" does not exist, then the resource is aliased to "/foobar". Accept such aliases.
     */
    public static class ApproveNonExistentDirectoryAliases implements AliasCheck
    {
        @Override
        public boolean check(String path, Resource resource)
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
        void enterScope(Context context, Request request, Object reason);

        /**
         * @param context The context being exited
         * @param request A request that is applicable to the scope, or null
         */
        void exitScope(Context context, Request request);
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
