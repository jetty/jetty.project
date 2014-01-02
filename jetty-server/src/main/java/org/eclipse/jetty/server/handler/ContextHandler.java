//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessController;
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

import javax.servlet.DispatcherType;
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
import javax.servlet.Filter;
import javax.servlet.FilterRegistration;
import javax.servlet.FilterRegistration.Dynamic;
import javax.servlet.descriptor.JspConfigDescriptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

/* ------------------------------------------------------------ */
/**
 * ContextHandler.
 *
 * This handler wraps a call to handle by setting the context and servlet path, plus setting the context classloader.
 *
 * <p>
 * If the context init parameter "org.eclipse.jetty.server.context.ManagedAttributes" is set to a comma separated list of names, then they are treated as
 * context attribute names, which if set as attributes are passed to the servers Container so that they may be managed with JMX.
 * <p>
 * The maximum size of a form that can be processed by this context is controlled by the system properties org.eclipse.jetty.server.Request.maxFormKeys
 * and org.eclipse.jetty.server.Request.maxFormContentSize.  These can also be configured with {@link #setMaxFormContentSize(int)} and {@link #setMaxFormKeys(int)}
 * 
 * @org.apache.xbean.XBean description="Creates a basic HTTP context"
 */
public class ContextHandler extends ScopedHandler implements Attributes, Server.Graceful
{
    private static final Logger LOG = Log.getLogger(ContextHandler.class);

    private static final ThreadLocal<Context> __context = new ThreadLocal<Context>();

    /**
     * If a context attribute with this name is set, it is interpreted as a comma separated list of attribute name. Any other context attributes that are set
     * with a name from this list will result in a call to {@link #setManagedAttribute(String, Object)}, which typically initiates the creation of a JMX MBean
     * for the attribute value.
     */
    public static final String MANAGED_ATTRIBUTES = "org.eclipse.jetty.server.context.ManagedAttributes";

    /* ------------------------------------------------------------ */
    /**
     * Get the current ServletContext implementation.
     *
     * @return ServletContext implementation
     */
    public static Context getCurrentContext()
    {
        return __context.get();
    }

    protected Context _scontext;

    private final AttributesMap _attributes;
    private final AttributesMap _contextAttributes;
    private final Map<String, String> _initParams;
    private ClassLoader _classLoader;
    private String _contextPath = "/";
    private String _displayName;
    private Resource _baseResource;
    private MimeTypes _mimeTypes;
    private Map<String, String> _localeEncodingMap;
    private String[] _welcomeFiles;
    private ErrorHandler _errorHandler;
    private String[] _vhosts;
    private Set<String> _connectors;
    private EventListener[] _eventListeners;
    private Logger _logger;
    private boolean _allowNullPathInfo;
    private int _maxFormKeys = Integer.getInteger("org.eclipse.jetty.server.Request.maxFormKeys",-1).intValue();
    private int _maxFormContentSize = Integer.getInteger("org.eclipse.jetty.server.Request.maxFormContentSize",-1).intValue();
    private boolean _compactPath = false;
    private boolean _aliasesAllowed = false;

    private Object _contextListeners;
    private Object _contextAttributeListeners;
    private Object _requestListeners;
    private Object _requestAttributeListeners;
    private Object _durableListeners;
    private Map<String, Object> _managedAttributes;
    private String[] _protectedTargets;
    private final CopyOnWriteArrayList<AliasCheck> _aliasChecks = new CopyOnWriteArrayList<ContextHandler.AliasCheck>();

    private boolean _shutdown = false;
    private boolean _available = true;
    private volatile int _availability; // 0=STOPPED, 1=AVAILABLE, 2=SHUTDOWN, 3=UNAVAILABLE

    private final static int __STOPPED = 0, __AVAILABLE = 1, __SHUTDOWN = 2, __UNAVAILABLE = 3;

    /* ------------------------------------------------------------ */
    /**
     *
     */
    public ContextHandler()
    {
        super();
        _scontext = new Context();
        _attributes = new AttributesMap();
        _contextAttributes = new AttributesMap();
        _initParams = new HashMap<String, String>();
        addAliasCheck(new ApproveNonExistentDirectoryAliases());
    }

    /* ------------------------------------------------------------ */
    /**
     *
     */
    protected ContextHandler(Context context)
    {
        super();
        _scontext = context;
        _attributes = new AttributesMap();
        _contextAttributes = new AttributesMap();
        _initParams = new HashMap<String, String>();
        addAliasCheck(new ApproveNonExistentDirectoryAliases());
    }

    /* ------------------------------------------------------------ */
    /**
     *
     */
    public ContextHandler(String contextPath)
    {
        this();
        setContextPath(contextPath);
    }

    /* ------------------------------------------------------------ */
    /**
     *
     */
    public ContextHandler(HandlerContainer parent, String contextPath)
    {
        this();
        setContextPath(contextPath);
        if (parent instanceof HandlerWrapper)
            ((HandlerWrapper)parent).setHandler(this);
        else if (parent instanceof HandlerCollection)
            ((HandlerCollection)parent).addHandler(this);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpThis(out);
        dump(out,indent,Collections.singletonList(new CLDump(getClassLoader())),TypeUtil.asList(getHandlers()),getBeans(),_initParams.entrySet(),
                _attributes.getAttributeEntrySet(),_contextAttributes.getAttributeEntrySet());
    }

    /* ------------------------------------------------------------ */
    public Context getServletContext()
    {
        return _scontext;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the allowNullPathInfo true if /context is not redirected to /context/
     */
    public boolean getAllowNullPathInfo()
    {
        return _allowNullPathInfo;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param allowNullPathInfo
     *            true if /context is not redirected to /context/
     */
    public void setAllowNullPathInfo(boolean allowNullPathInfo)
    {
        _allowNullPathInfo = allowNullPathInfo;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void setServer(Server server)
    {
        if (_errorHandler != null)
        {
            Server old_server = getServer();
            if (old_server != null && old_server != server)
                old_server.getContainer().update(this,_errorHandler,null,"error",true);
            super.setServer(server);
            if (server != null && server != old_server)
                server.getContainer().update(this,null,_errorHandler,"error",true);
            _errorHandler.setServer(server);
        }
        else
            super.setServer(server);
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the virtual hosts for the context. Only requests that have a matching host header or fully qualified URL will be passed to that context with a
     * virtual host name. A context with no virtual host names or a null virtual host name is available to all requests that are not served by a context with a
     * matching virtual host name.
     *
     * @param vhosts
     *            Array of virtual hosts that this context responds to. A null host name or null/empty array means any hostname is acceptable. Host names may be
     *            String representation of IP addresses. Host names may start with '*.' to wildcard one level of names.
     */
    public void setVirtualHosts(String[] vhosts)
    {
        if (vhosts == null)
        {
            _vhosts = vhosts;
        }
        else
        {
            _vhosts = new String[vhosts.length];
            for (int i = 0; i < vhosts.length; i++)
                _vhosts[i] = normalizeHostname(vhosts[i]);
        }
    }

    /* ------------------------------------------------------------ */
    /** Either set virtual hosts or add to an existing set of virtual hosts.
     *
     * @param virtualHosts
     *            Array of virtual hosts that this context responds to. A null host name or null/empty array means any hostname is acceptable. Host names may be
     *            String representation of IP addresses. Host names may start with '*.' to wildcard one level of names.
     */
    public void addVirtualHosts(String[] virtualHosts)
    {
        if (virtualHosts == null)  // since this is add, we don't null the old ones
        {
            return;
        }
        else
        {
            List<String> currentVirtualHosts = null;
            if (_vhosts != null)
            {
                currentVirtualHosts = new ArrayList<String>(Arrays.asList(_vhosts));
            }
            else
            {
                currentVirtualHosts = new ArrayList<String>();
            }

            for (int i = 0; i < virtualHosts.length; i++)
            {
                String normVhost = normalizeHostname(virtualHosts[i]);
                if (!currentVirtualHosts.contains(normVhost))
                {
                    currentVirtualHosts.add(normVhost);
                }
            }
            _vhosts = currentVirtualHosts.toArray(new String[0]);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Removes an array of virtual host entries, if this removes all entries the _vhosts will be set to null
     *
     *  @param virtualHosts
     *            Array of virtual hosts that this context responds to. A null host name or null/empty array means any hostname is acceptable. Host names may be
     *            String representation of IP addresses. Host names may start with '*.' to wildcard one level of names.
     */
    public void removeVirtualHosts(String[] virtualHosts)
    {
        if (virtualHosts == null)
        {
            return; // do nothing
        }
        else if ( _vhosts == null || _vhosts.length == 0)
        {
            return; // do nothing
        }
        else
        {
            List<String> existingVirtualHosts = new ArrayList<String>(Arrays.asList(_vhosts));

            for (int i = 0; i < virtualHosts.length; i++)
            {
                String toRemoveVirtualHost = normalizeHostname(virtualHosts[i]);
                if (existingVirtualHosts.contains(toRemoveVirtualHost))
                {
                    existingVirtualHosts.remove(toRemoveVirtualHost);
                }
            }

            if (existingVirtualHosts.isEmpty())
            {
                _vhosts = null; // if we ended up removing them all, just null out _vhosts
            }
            else
            {
                _vhosts = existingVirtualHosts.toArray(new String[0]);
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the virtual hosts for the context. Only requests that have a matching host header or fully qualified URL will be passed to that context with a
     * virtual host name. A context with no virtual host names or a null virtual host name is available to all requests that are not served by a context with a
     * matching virtual host name.
     *
     * @return Array of virtual hosts that this context responds to. A null host name or empty array means any hostname is acceptable. Host names may be String
     *         representation of IP addresses. Host names may start with '*.' to wildcard one level of names.
     */
    public String[] getVirtualHosts()
    {
        return _vhosts;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return an array of connector names that this context will accept a request from.
     */
    public String[] getConnectorNames()
    {
        if (_connectors == null || _connectors.size() == 0)
            return null;

        return _connectors.toArray(new String[_connectors.size()]);
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the names of accepted connectors.
     *
     * Names are either "host:port" or a specific configured name for a connector.
     *
     * @param connectors
     *            If non null, an array of connector names that this context will accept a request from.
     */
    public void setConnectorNames(String[] connectors)
    {
        if (connectors == null || connectors.length == 0)
            _connectors = null;
        else
            _connectors = new HashSet<String>(Arrays.asList(connectors));
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletContext#getAttribute(java.lang.String)
     */
    public Object getAttribute(String name)
    {
        return _attributes.getAttribute(name);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletContext#getAttributeNames()
     */
    @SuppressWarnings("unchecked")
    public Enumeration getAttributeNames()
    {
        return AttributesMap.getAttributeNamesCopy(_attributes);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the attributes.
     */
    public Attributes getAttributes()
    {
        return _attributes;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the classLoader.
     */
    public ClassLoader getClassLoader()
    {
        return _classLoader;
    }

    /* ------------------------------------------------------------ */
    /**
     * Make best effort to extract a file classpath from the context classloader
     *
     * @return Returns the classLoader.
     */
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

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the _contextPath.
     */
    public String getContextPath()
    {
        return _contextPath;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletContext#getInitParameter(java.lang.String)
     */
    public String getInitParameter(String name)
    {
        return _initParams.get(name);
    }

    /* ------------------------------------------------------------ */
    /*
     */
    public String setInitParameter(String name, String value)
    {
        return _initParams.put(name,value);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletContext#getInitParameterNames()
     */
    @SuppressWarnings("rawtypes")
    public Enumeration getInitParameterNames()
    {
        return Collections.enumeration(_initParams.keySet());
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the initParams.
     */
    public Map<String, String> getInitParams()
    {
        return _initParams;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletContext#getServletContextName()
     */
    public String getDisplayName()
    {
        return _displayName;
    }

    /* ------------------------------------------------------------ */
    public EventListener[] getEventListeners()
    {
        return _eventListeners;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the context event listeners.
     *
     * @param eventListeners
     *            the event listeners
     * @see ServletContextListener
     * @see ServletContextAttributeListener
     * @see ServletRequestListener
     * @see ServletRequestAttributeListener
     */
    public void setEventListeners(EventListener[] eventListeners)
    {
        _contextListeners = null;
        _contextAttributeListeners = null;
        _requestListeners = null;
        _requestAttributeListeners = null;

        _eventListeners = eventListeners;

        for (int i = 0; eventListeners != null && i < eventListeners.length; i++)
        {
            EventListener listener = _eventListeners[i];

            if (listener instanceof ServletContextListener)
                _contextListeners = LazyList.add(_contextListeners,listener);

            if (listener instanceof ServletContextAttributeListener)
                _contextAttributeListeners = LazyList.add(_contextAttributeListeners,listener);

            if (listener instanceof ServletRequestListener)
                _requestListeners = LazyList.add(_requestListeners,listener);

            if (listener instanceof ServletRequestAttributeListener)
                _requestAttributeListeners = LazyList.add(_requestAttributeListeners,listener);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Add a context event listeners.
     *
     * @see ServletContextListener
     * @see ServletContextAttributeListener
     * @see ServletRequestListener
     * @see ServletRequestAttributeListener
     */
    public void addEventListener(EventListener listener)
    {
        //Only listeners added before the context starts last through a stop/start cycle
        if (!(isStarted() || isStarting()))
            _durableListeners = LazyList.add(_durableListeners, listener);
        
        setEventListeners((EventListener[])LazyList.addToArray(getEventListeners(),listener,EventListener.class));
    }
    
   
    /**
     * Apply any necessary restrictions on a programmatically added
     * listener.
     * 
     * Superclasses should implement.
     * 
     * @param listener
     */
    public void restrictEventListener (EventListener listener)
    {
    }

    

    /* ------------------------------------------------------------ */
    /**
     * @return true if this context is accepting new requests
     */
    public boolean isShutdown()
    {
        synchronized (this)
        {
            return !_shutdown;
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Set shutdown status. This field allows for graceful shutdown of a context. A started context may be put into non accepting state so that existing
     * requests can complete, but no new requests are accepted.
     *
     * @param shutdown
     *            true if this context is (not?) accepting new requests
     */
    public void setShutdown(boolean shutdown)
    {
        synchronized (this)
        {
            _shutdown = shutdown;
            _availability = isRunning()?(_shutdown?__SHUTDOWN:_available?__AVAILABLE:__UNAVAILABLE):__STOPPED;
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return false if this context is unavailable (sends 503)
     */
    public boolean isAvailable()
    {
        synchronized (this)
        {
            return _available;
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Set Available status.
     */
    public void setAvailable(boolean available)
    {
        synchronized (this)
        {
            _available = available;
            _availability = isRunning()?(_shutdown?__SHUTDOWN:_available?__AVAILABLE:__UNAVAILABLE):__STOPPED;
        }
    }

    /* ------------------------------------------------------------ */
    public Logger getLogger()
    {
        return _logger;
    }

    /* ------------------------------------------------------------ */
    public void setLogger(Logger logger)
    {
        _logger = logger;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.thread.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        _availability = __STOPPED;

        if (_contextPath == null)
            throw new IllegalStateException("Null contextPath");

        _logger = Log.getLogger(getDisplayName() == null?getContextPath():getDisplayName());
        ClassLoader old_classloader = null;
        Thread current_thread = null;
        Context old_context = null;

        try
        {
            // Set the classloader
            if (_classLoader != null)
            {
                current_thread = Thread.currentThread();
                old_classloader = current_thread.getContextClassLoader();
                current_thread.setContextClassLoader(_classLoader);
            }

            if (_mimeTypes == null)
                _mimeTypes = new MimeTypes();

            old_context = __context.get();
            __context.set(_scontext);

            // defers the calling of super.doStart()
            startContext();

            synchronized(this)
            {
                _availability = _shutdown?__SHUTDOWN:_available?__AVAILABLE:__UNAVAILABLE;
            }
        }
        finally
        {
            __context.set(old_context);

            // reset the classloader
            if (_classLoader != null)
            {
                current_thread.setContextClassLoader(old_classloader);
            }

        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Extensible startContext. this method is called from {@link ContextHandler#doStart()} instead of a call to super.doStart(). This allows derived classes to
     * insert additional handling (Eg configuration) before the call to super.doStart by this method will start contained handlers.
     *
     * @see org.eclipse.jetty.server.handler.ContextHandler.Context
     */
    protected void startContext() throws Exception
    {
        String managedAttributes = _initParams.get(MANAGED_ATTRIBUTES);
        if (managedAttributes != null)
        {
            _managedAttributes = new HashMap<String, Object>();
            String[] attributes = managedAttributes.split(",");
            for (String attribute : attributes)
                _managedAttributes.put(attribute,null);

            Enumeration e = _scontext.getAttributeNames();
            while (e.hasMoreElements())
            {
                String name = (String)e.nextElement();
                Object value = _scontext.getAttribute(name);
                checkManagedAttribute(name,value);
            }
        }

        super.doStart();

        if (_errorHandler != null)
            _errorHandler.start();

        // Context listeners
        if (_contextListeners != null)
        {
            ServletContextEvent event = new ServletContextEvent(_scontext);
            for (int i = 0; i < LazyList.size(_contextListeners); i++)
            {
                callContextInitialized(((ServletContextListener)LazyList.get(_contextListeners, i)), event);
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void callContextInitialized (ServletContextListener l, ServletContextEvent e)
    {
        l.contextInitialized(e);
    }

    /* ------------------------------------------------------------ */
    public void callContextDestroyed (ServletContextListener l, ServletContextEvent e)
    {
        l.contextDestroyed(e);
    }
    
    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.thread.AbstractLifeCycle#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        _availability = __STOPPED;

        ClassLoader old_classloader = null;
        Thread current_thread = null;

        Context old_context = __context.get();
        __context.set(_scontext);
        try
        {
            // Set the classloader
            if (_classLoader != null)
            {
                current_thread = Thread.currentThread();
                old_classloader = current_thread.getContextClassLoader();
                current_thread.setContextClassLoader(_classLoader);
            }

            super.doStop();

            // Context listeners
            if (_contextListeners != null)
            {
                ServletContextEvent event = new ServletContextEvent(_scontext);
                for (int i = LazyList.size(_contextListeners); i-- > 0;)
                {
                    ((ServletContextListener)LazyList.get(_contextListeners,i)).contextDestroyed(event);
                }
            }
            
            //remove all non-durable listeners
            setEventListeners((EventListener[])LazyList.toArray(_durableListeners, EventListener.class));
            _durableListeners = null;

            if (_errorHandler != null)
                _errorHandler.stop();

            Enumeration e = _scontext.getAttributeNames();
            while (e.hasMoreElements())
            {
                String name = (String)e.nextElement();
                checkManagedAttribute(name,null);
            }
        }
        finally
        {
            LOG.info("stopped {}",this);
            __context.set(old_context);
            // reset the classloader
            if (_classLoader != null)
                current_thread.setContextClassLoader(old_classloader);
        }

        _contextAttributes.clearAttributes();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.Handler#handle(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    public boolean checkContext(final String target, final Request baseRequest, final HttpServletResponse response) throws IOException, ServletException
    {
        DispatcherType dispatch = baseRequest.getDispatcherType();

        switch (_availability)
        {
            case __STOPPED:
            case __SHUTDOWN:
                return false;
            case __UNAVAILABLE:
                baseRequest.setHandled(true);
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return false;
            default:
                if ((DispatcherType.REQUEST.equals(dispatch) && baseRequest.isHandled()))
                    return false;
        }

        // Check the vhosts
        if (_vhosts != null && _vhosts.length > 0)
        {
            String vhost = normalizeHostname(baseRequest.getServerName());

            boolean match = false;

            // TODO non-linear lookup
            for (int i = 0; !match && i < _vhosts.length; i++)
            {
                String contextVhost = _vhosts[i];
                if (contextVhost == null)
                    continue;
                if (contextVhost.startsWith("*."))
                {
                    // wildcard only at the beginning, and only for one additional subdomain level
                    match = contextVhost.regionMatches(true,2,vhost,vhost.indexOf(".") + 1,contextVhost.length() - 2);
                }
                else
                    match = contextVhost.equalsIgnoreCase(vhost);
            }
            if (!match)
                return false;
        }

        // Check the connector
        if (_connectors != null && _connectors.size() > 0)
        {
            String connector = AbstractHttpConnection.getCurrentConnection().getConnector().getName();
            if (connector == null || !_connectors.contains(connector))
                return false;
        }

        // Are we not the root context?
        if (_contextPath.length() > 1)
        {
            // reject requests that are not for us
            if (!target.startsWith(_contextPath))
                return false;
            if (target.length() > _contextPath.length() && target.charAt(_contextPath.length()) != '/')
                return false;

            // redirect null path infos
            if (!_allowNullPathInfo && _contextPath.length() == target.length())
            {
                // context request must end with /
                baseRequest.setHandled(true);
                if (baseRequest.getQueryString() != null)
                    response.sendRedirect(URIUtil.addPaths(baseRequest.getRequestURI(),URIUtil.SLASH) + "?" + baseRequest.getQueryString());
                else
                    response.sendRedirect(URIUtil.addPaths(baseRequest.getRequestURI(),URIUtil.SLASH));
                return false;
            }
        }

        return true;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.handler.ScopedHandler#doScope(java.lang.String, org.eclipse.jetty.server.Request, javax.servlet.http.HttpServletRequest,
     *      javax.servlet.http.HttpServletResponse)
     */
    @Override
    public void doScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("scope {}|{}|{} @ {}",baseRequest.getContextPath(),baseRequest.getServletPath(),baseRequest.getPathInfo(),this);

        Context old_context = null;
        String old_context_path = null;
        String old_servlet_path = null;
        String old_path_info = null;
        ClassLoader old_classloader = null;
        Thread current_thread = null;
        String pathInfo = target;

        DispatcherType dispatch = baseRequest.getDispatcherType();

        old_context = baseRequest.getContext();

        // Are we already in this context?
        if (old_context != _scontext)
        {
            // check the target.
            if (DispatcherType.REQUEST.equals(dispatch) || DispatcherType.ASYNC.equals(dispatch) || (DispatcherType.ERROR.equals(dispatch) && baseRequest.getAsyncContinuation().isExpired()))
            {
                if (_compactPath)
                    target = URIUtil.compactPath(target);
                if (!checkContext(target,baseRequest,response))
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
                current_thread = Thread.currentThread();
                old_classloader = current_thread.getContextClassLoader();
                current_thread.setContextClassLoader(_classLoader);
            }
        }

        try
        {
            old_context_path = baseRequest.getContextPath();
            old_servlet_path = baseRequest.getServletPath();
            old_path_info = baseRequest.getPathInfo();

            // Update the paths
            baseRequest.setContext(_scontext);
            __context.set(_scontext);
            if (!DispatcherType.INCLUDE.equals(dispatch) && target.startsWith("/"))
            {
                if (_contextPath.length() == 1)
                    baseRequest.setContextPath("");
                else
                    baseRequest.setContextPath(_contextPath);
                baseRequest.setServletPath(null);
                baseRequest.setPathInfo(pathInfo);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("context={}|{}|{} @ {}",baseRequest.getContextPath(),baseRequest.getServletPath(), baseRequest.getPathInfo(),this);

            // start manual inline of nextScope(target,baseRequest,request,response);
            if (never())
                nextScope(target,baseRequest,request,response);
            else if (_nextScope != null)
                _nextScope.doScope(target,baseRequest,request,response);
            else if (_outerScope != null)
                _outerScope.doHandle(target,baseRequest,request,response);
            else
                doHandle(target,baseRequest,request,response);
            // end manual inline (pathentic attempt to reduce stack depth)
        }
        finally
        {
            if (old_context != _scontext)
            {
                // reset the classloader
                if (_classLoader != null)
                {
                    current_thread.setContextClassLoader(old_classloader);
                }

                // reset the context and servlet path.
                baseRequest.setContext(old_context);
                __context.set(old_context);
                baseRequest.setContextPath(old_context_path);
                baseRequest.setServletPath(old_servlet_path);
                baseRequest.setPathInfo(old_path_info);
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.handler.ScopedHandler#doHandle(java.lang.String, org.eclipse.jetty.server.Request, javax.servlet.http.HttpServletRequest,
     *      javax.servlet.http.HttpServletResponse)
     */
    @Override
    public void doHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        final DispatcherType dispatch = baseRequest.getDispatcherType();
        final boolean new_context = baseRequest.takeNewContext();
        try
        {
            if (new_context)
            {
                // Handle the REALLY SILLY request events!
                if (_requestAttributeListeners != null)
                {
                    final int s = LazyList.size(_requestAttributeListeners);
                    for (int i = 0; i < s; i++)
                        baseRequest.addEventListener(((EventListener)LazyList.get(_requestAttributeListeners,i)));
                }

                if (_requestListeners != null)
                {
                    final int s = LazyList.size(_requestListeners);
                    final ServletRequestEvent sre = new ServletRequestEvent(_scontext,request);
                    for (int i = 0; i < s; i++)
                        ((ServletRequestListener)LazyList.get(_requestListeners,i)).requestInitialized(sre);
                }
            }

            if (DispatcherType.REQUEST.equals(dispatch) && isProtectedTarget(target))
                throw new HttpException(HttpServletResponse.SC_NOT_FOUND);

            // start manual inline of nextHandle(target,baseRequest,request,response);
            // noinspection ConstantIfStatement
            if (never())
                nextHandle(target,baseRequest,request,response);
            else if (_nextScope != null && _nextScope == _handler)
                _nextScope.doHandle(target,baseRequest,request,response);
            else if (_handler != null)
                _handler.handle(target,baseRequest,request,response);
            // end manual inline
        }
        catch (HttpException e)
        {
            LOG.debug(e);
            baseRequest.setHandled(true);
            response.sendError(e.getStatus(),e.getReason());
        }
        finally
        {
            // Handle more REALLY SILLY request events!
            if (new_context)
            {
                if (_requestListeners != null)
                {
                    final ServletRequestEvent sre = new ServletRequestEvent(_scontext,request);
                    for (int i = LazyList.size(_requestListeners); i-- > 0;)
                        ((ServletRequestListener)LazyList.get(_requestListeners,i)).requestDestroyed(sre);
                }

                if (_requestAttributeListeners != null)
                {
                    for (int i = LazyList.size(_requestAttributeListeners); i-- > 0;)
                        baseRequest.removeEventListener(((EventListener)LazyList.get(_requestAttributeListeners,i)));
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * Handle a runnable in this context
     */
    public void handle(Runnable runnable)
    {
        ClassLoader old_classloader = null;
        Thread current_thread = null;
        Context old_context = null;
        try
        {
            old_context = __context.get();
            __context.set(_scontext);

            // Set the classloader
            if (_classLoader != null)
            {
                current_thread = Thread.currentThread();
                old_classloader = current_thread.getContextClassLoader();
                current_thread.setContextClassLoader(_classLoader);
            }

            runnable.run();
        }
        finally
        {
            __context.set(old_context);
            if (old_classloader != null)
            {
                current_thread.setContextClassLoader(old_classloader);
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Check the target. Called by {@link #handle(String, Request, HttpServletRequest, HttpServletResponse)} when a target within a context is determined. If
     * the target is protected, 404 is returned. 
     */
    /* ------------------------------------------------------------ */
    public boolean isProtectedTarget(String target)
    {
        if (target == null || _protectedTargets == null)
            return false;
        
        while (target.startsWith("//"))
            target=URIUtil.compactPath(target);
        
        boolean isProtected = false;
        int i=0;
        while (!isProtected && i<_protectedTargets.length)
        {
            isProtected = StringUtil.startsWithIgnoreCase(target, _protectedTargets[i++]);
        }
        return isProtected;
    }
    
    
    public void setProtectedTargets (String[] targets)
    {
        if (targets == null)
        {
            _protectedTargets = null;
            return;
        }
        
        _protectedTargets = new String[targets.length];
        System.arraycopy(targets, 0, _protectedTargets, 0, targets.length);
    }
    
    public String[] getProtectedTargets ()
    {
        if (_protectedTargets == null)
            return null;
        
        String[] tmp = new String[_protectedTargets.length];
        System.arraycopy(_protectedTargets, 0, tmp, 0, _protectedTargets.length);
        return tmp;
    }
    

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletContext#removeAttribute(java.lang.String)
     */
    public void removeAttribute(String name)
    {
        checkManagedAttribute(name,null);
        _attributes.removeAttribute(name);
    }

    /* ------------------------------------------------------------ */
    /*
     * Set a context attribute. Attributes set via this API cannot be overriden by the ServletContext.setAttribute API. Their lifecycle spans the stop/start of
     * a context. No attribute listener events are triggered by this API.
     *
     * @see javax.servlet.ServletContext#setAttribute(java.lang.String, java.lang.Object)
     */
    public void setAttribute(String name, Object value)
    {
        checkManagedAttribute(name,value);
        _attributes.setAttribute(name,value);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param attributes
     *            The attributes to set.
     */
    public void setAttributes(Attributes attributes)
    {
        _attributes.clearAttributes();
        _attributes.addAll(attributes);
        Enumeration e = _attributes.getAttributeNames();
        while (e.hasMoreElements())
        {
            String name = (String)e.nextElement();
            checkManagedAttribute(name,attributes.getAttribute(name));
        }
    }

    /* ------------------------------------------------------------ */
    public void clearAttributes()
    {
        Enumeration e = _attributes.getAttributeNames();
        while (e.hasMoreElements())
        {
            String name = (String)e.nextElement();
            checkManagedAttribute(name,null);
        }
        _attributes.clearAttributes();
    }

    /* ------------------------------------------------------------ */
    public void checkManagedAttribute(String name, Object value)
    {
        if (_managedAttributes != null && _managedAttributes.containsKey(name))
        {
            setManagedAttribute(name,value);
        }
    }

    /* ------------------------------------------------------------ */
    public void setManagedAttribute(String name, Object value)
    {
        Object old = _managedAttributes.put(name,value);
        getServer().getContainer().update(this,old,value,name,true);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param classLoader
     *            The classLoader to set.
     */
    public void setClassLoader(ClassLoader classLoader)
    {
        _classLoader = classLoader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param contextPath
     *            The _contextPath to set.
     */
    public void setContextPath(String contextPath)
    {
        if (contextPath != null && contextPath.length() > 1 && contextPath.endsWith("/"))
            throw new IllegalArgumentException("ends with /");
        _contextPath = contextPath;

        if (getServer() != null && (getServer().isStarting() || getServer().isStarted()))
        {
            Handler[] contextCollections = getServer().getChildHandlersByClass(ContextHandlerCollection.class);
            for (int h = 0; contextCollections != null && h < contextCollections.length; h++)
                ((ContextHandlerCollection)contextCollections[h]).mapContexts();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @param servletContextName
     *            The servletContextName to set.
     */
    public void setDisplayName(String servletContextName)
    {
        _displayName = servletContextName;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the resourceBase.
     */
    public Resource getBaseResource()
    {
        if (_baseResource == null)
            return null;
        return _baseResource;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the base resource as a string.
     */
    public String getResourceBase()
    {
        if (_baseResource == null)
            return null;
        return _baseResource.toString();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param base
     *            The resourceBase to set.
     */
    public void setBaseResource(Resource base)
    {
        _baseResource = base;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param resourceBase
     *            The base resource as a string.
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

    /* ------------------------------------------------------------ */
    /**
     * @return True if aliases are allowed
     */
    public boolean isAliases()
    {
        return _aliasesAllowed;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param aliases
     *            aliases are allowed
     */
    public void setAliases(boolean aliases)
    {
        _aliasesAllowed = aliases;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the mimeTypes.
     */
    public MimeTypes getMimeTypes()
    {
        if (_mimeTypes == null)
            _mimeTypes = new MimeTypes();
        return _mimeTypes;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param mimeTypes
     *            The mimeTypes to set.
     */
    public void setMimeTypes(MimeTypes mimeTypes)
    {
        _mimeTypes = mimeTypes;
    }

    /* ------------------------------------------------------------ */
    /**
     */
    public void setWelcomeFiles(String[] files)
    {
        _welcomeFiles = files;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The names of the files which the server should consider to be welcome files in this context.
     * @see <a href="http://jcp.org/aboutJava/communityprocess/final/jsr154/index.html">The Servlet Specification</a>
     * @see #setWelcomeFiles
     */
    public String[] getWelcomeFiles()
    {
        return _welcomeFiles;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the errorHandler.
     */
    public ErrorHandler getErrorHandler()
    {
        return _errorHandler;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param errorHandler
     *            The errorHandler to set.
     */
    public void setErrorHandler(ErrorHandler errorHandler)
    {
        if (errorHandler != null)
            errorHandler.setServer(getServer());
        if (getServer() != null)
            getServer().getContainer().update(this,_errorHandler,errorHandler,"errorHandler",true);
        _errorHandler = errorHandler;
    }

    /* ------------------------------------------------------------ */
    public int getMaxFormContentSize()
    {
        return _maxFormContentSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the maximum size of a form post, to protect against DOS attacks from large forms.
     * @param maxSize
     */
    public void setMaxFormContentSize(int maxSize)
    {
        _maxFormContentSize = maxSize;
    }

    /* ------------------------------------------------------------ */
    public int getMaxFormKeys()
    {
        return _maxFormKeys;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the maximum number of form Keys to protect against DOS attack from crafted hash keys.
     * @param max
     */
    public void setMaxFormKeys(int max)
    {
        _maxFormKeys = max;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if URLs are compacted to replace multiple '/'s with a single '/'
     */
    public boolean isCompactPath()
    {
        return _compactPath;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param compactPath
     *            True if URLs are compacted to replace multiple '/'s with a single '/'
     */
    public void setCompactPath(boolean compactPath)
    {
        _compactPath = compactPath;
    }

    /* ------------------------------------------------------------ */
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
                    b.append(s.charAt(0)).append('.');
            }
        }
        b.append(getClass().getSimpleName());
        b.append('{').append(getContextPath()).append(',').append(getBaseResource());

        if (vhosts != null && vhosts.length > 0)
            b.append(',').append(vhosts[0]);
        b.append('}');

        return b.toString();
    }

    /* ------------------------------------------------------------ */
    public synchronized Class<?> loadClass(String className) throws ClassNotFoundException
    {
        if (className == null)
            return null;

        if (_classLoader == null)
            return Loader.loadClass(this.getClass(),className);

        return _classLoader.loadClass(className);
    }

    /* ------------------------------------------------------------ */
    public void addLocaleEncoding(String locale, String encoding)
    {
        if (_localeEncodingMap == null)
            _localeEncodingMap = new HashMap<String, String>();
        _localeEncodingMap.put(locale,encoding);
    }

    /* ------------------------------------------------------------ */
    public String getLocaleEncoding(String locale)
    {
        if (_localeEncodingMap == null)
            return null;
        String encoding = _localeEncodingMap.get(locale);
        return encoding;
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the character encoding for a locale. The full locale name is first looked up in the map of encodings. If no encoding is found, then the locale
     * language is looked up.
     *
     * @param locale
     *            a <code>Locale</code> value
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

    /* ------------------------------------------------------------ */
    /*
     */
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
            
            if (checkAlias(path,resource))
                return resource;
            return null;
        }
        catch (Exception e)
        {
            LOG.ignore(e);
        }

        return null;
    }

    /* ------------------------------------------------------------ */
    public boolean checkAlias(String path, Resource resource)
    {
        // Is the resource aliased?
        if (!_aliasesAllowed && resource.getAlias() != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Aliased resource: " + resource + "~=" + resource.getAlias());

            // alias checks
            for (Iterator<AliasCheck> i=_aliasChecks.iterator();i.hasNext();)
            {
                AliasCheck check = i.next();
                if (check.check(path,resource))
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
    
    /* ------------------------------------------------------------ */
    /**
     * Convert URL to Resource wrapper for {@link Resource#newResource(URL)} enables extensions to provide alternate resource implementations.
     */
    public Resource newResource(URL url) throws IOException
    {
        return Resource.newResource(url);
    }

    /* ------------------------------------------------------------ */
    /**
     * Convert a URL or path to a Resource. The default implementation is a wrapper for {@link Resource#newResource(String)}.
     *
     * @param urlOrPath
     *            The URL or path to convert
     * @return The Resource for the URL/path
     * @throws IOException
     *             The Resource could not be created.
     */
    public Resource newResource(String urlOrPath) throws IOException
    {
        return Resource.newResource(urlOrPath);
    }

    /* ------------------------------------------------------------ */
    /*
     */
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
                    HashSet<String> set = new HashSet<String>();
                    for (int i = 0; i < l.length; i++)
                        set.add(path + l[i]);
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

    /* ------------------------------------------------------------ */
    private String normalizeHostname(String host)
    {
        if (host == null)
            return null;

        if (host.endsWith("."))
            return host.substring(0,host.length() - 1);

        return host;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Add an AliasCheck instance to possibly permit aliased resources
     * @param check The alias checker
     */
    public void addAliasCheck(AliasCheck check)
    {
        _aliasChecks.add(check);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return Mutable list of Alias checks
     */
    public List<AliasCheck> getAliasChecks()
    {
        return _aliasChecks;
    }

    /* ------------------------------------------------------------ */
    /**
     * Context.
     * <p>
     * A partial implementation of {@link javax.servlet.ServletContext}. A complete implementation is provided by the derived {@link ContextHandler}.
     * </p>
     *
     *
     */
    public class Context implements ServletContext
    {
        protected int _majorVersion = 3;
        protected int _minorVersion = 0;
        protected boolean _enabled = true; //whether or not the dynamic API is enabled for callers

        /* ------------------------------------------------------------ */
        protected Context()
        {
        }

        /* ------------------------------------------------------------ */
        public ContextHandler getContextHandler()
        {
            // TODO reduce visibility of this method
            return ContextHandler.this;
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#getContext(java.lang.String)
         */
        @Override
        public ServletContext getContext(String uripath)
        {
            List<ContextHandler> contexts = new ArrayList<ContextHandler>();
            Handler[] handlers = getServer().getChildHandlersByClass(ContextHandler.class);
            String matched_path = null;

            for (Handler handler : handlers)
            {
                if (handler == null)
                    continue;
                ContextHandler ch = (ContextHandler)handler;
                String context_path = ch.getContextPath();

                if (uripath.equals(context_path) || (uripath.startsWith(context_path) && uripath.charAt(context_path.length()) == '/')
                        || "/".equals(context_path))
                {
                    // look first for vhost matching context only
                    if (getVirtualHosts() != null && getVirtualHosts().length > 0)
                    {
                        if (ch.getVirtualHosts() != null && ch.getVirtualHosts().length > 0)
                        {
                            for (String h1 : getVirtualHosts())
                                for (String h2 : ch.getVirtualHosts())
                                    if (h1.equals(h2))
                                    {
                                        if (matched_path == null || context_path.length() > matched_path.length())
                                        {
                                            contexts.clear();
                                            matched_path = context_path;
                                        }

                                        if (matched_path.equals(context_path))
                                            contexts.add(ch);
                                    }
                        }
                    }
                    else
                    {
                        if (matched_path == null || context_path.length() > matched_path.length())
                        {
                            contexts.clear();
                            matched_path = context_path;
                        }

                        if (matched_path.equals(context_path))
                            contexts.add(ch);
                    }
                }
            }

            if (contexts.size() > 0)
                return contexts.get(0)._scontext;

            // try again ignoring virtual hosts
            matched_path = null;
            for (Handler handler : handlers)
            {
                if (handler == null)
                    continue;
                ContextHandler ch = (ContextHandler)handler;
                String context_path = ch.getContextPath();

                if (uripath.equals(context_path) || (uripath.startsWith(context_path) && uripath.charAt(context_path.length()) == '/')
                        || "/".equals(context_path))
                {
                    if (matched_path == null || context_path.length() > matched_path.length())
                    {
                        contexts.clear();
                        matched_path = context_path;
                    }

                    if (matched_path.equals(context_path))
                        contexts.add(ch);
                }
            }

            if (contexts.size() > 0)
                return contexts.get(0)._scontext;
            return null;
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#getMajorVersion()
         */
        @Override
        public int getMajorVersion()
        {
            return 3;
        }
      

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#getMimeType(java.lang.String)
         */
        @Override
        public String getMimeType(String file)
        {
            if (_mimeTypes == null)
                return null;
            Buffer mime = _mimeTypes.getMimeByExtension(file);
            if (mime != null)
                return mime.toString();
            return null;
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#getMinorVersion()
         */
        @Override
        public int getMinorVersion()
        {
            return 0;
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#getNamedDispatcher(java.lang.String)
         */
        @Override
        public RequestDispatcher getNamedDispatcher(String name)
        {
            return null;
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#getRequestDispatcher(java.lang.String)
         */
        @Override
        public RequestDispatcher getRequestDispatcher(String uriInContext)
        {
            if (uriInContext == null)
                return null;

            if (!uriInContext.startsWith("/"))
                return null;

            try
            {
                String query = null;
                int q = 0;
                if ((q = uriInContext.indexOf('?')) > 0)
                {
                    query = uriInContext.substring(q + 1);
                    uriInContext = uriInContext.substring(0,q);
                }

                String pathInContext = URIUtil.canonicalPath(URIUtil.decodePath(uriInContext));
                if (pathInContext!=null)
                {
                    String uri = URIUtil.addPaths(getContextPath(),uriInContext);
                    ContextHandler context = ContextHandler.this;
                    return new Dispatcher(context,uri,pathInContext,query);
                }
            }
            catch (Exception e)
            {
                LOG.ignore(e);
            }
            return null;
        }

        /* ------------------------------------------------------------ */
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

        /* ------------------------------------------------------------ */
        @Override
        public URL getResource(String path) throws MalformedURLException
        {
            Resource resource = ContextHandler.this.getResource(path);
            if (resource != null && resource.exists())
                return resource.getURL();
            return null;
        }

        /* ------------------------------------------------------------ */
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
                return r.getInputStream();
            }
            catch (Exception e)
            {
                LOG.ignore(e);
                return null;
            }
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#getResourcePaths(java.lang.String)
         */
        @Override
        public Set getResourcePaths(String path)
        {
            return ContextHandler.this.getResourcePaths(path);
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#getServerInfo()
         */
        @Override
        public String getServerInfo()
        {
            return "jetty/" + Server.getVersion();
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#getServlet(java.lang.String)
         */
        @Override
        @Deprecated
        public Servlet getServlet(String name) throws ServletException
        {
            return null;
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#getServletNames()
         */
        @SuppressWarnings("unchecked")
        @Override
        @Deprecated
        public Enumeration getServletNames()
        {
            return Collections.enumeration(Collections.EMPTY_LIST);
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#getServlets()
         */
        @SuppressWarnings("unchecked")
        @Override
        @Deprecated
        public Enumeration getServlets()
        {
            return Collections.enumeration(Collections.EMPTY_LIST);
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#log(java.lang.Exception, java.lang.String)
         */
        @Override
        public void log(Exception exception, String msg)
        {
            _logger.warn(msg,exception);
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#log(java.lang.String)
         */
        @Override
        public void log(String msg)
        {
            _logger.info(msg);
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#log(java.lang.String, java.lang.Throwable)
         */
        @Override
        public void log(String message, Throwable throwable)
        {
            _logger.warn(message,throwable);
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#getInitParameter(java.lang.String)
         */
        @Override
        public String getInitParameter(String name)
        {
            return ContextHandler.this.getInitParameter(name);
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#getInitParameterNames()
         */
        @SuppressWarnings("unchecked")
        @Override
        public Enumeration getInitParameterNames()
        {
            return ContextHandler.this.getInitParameterNames();
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#getAttribute(java.lang.String)
         */
        @Override
        public synchronized Object getAttribute(String name)
        {
            Object o = ContextHandler.this.getAttribute(name);
            if (o == null && _contextAttributes != null)
                o = _contextAttributes.getAttribute(name);
            return o;
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#getAttributeNames()
         */
        @SuppressWarnings("unchecked")
        @Override
        public synchronized Enumeration getAttributeNames()
        {
            HashSet<String> set = new HashSet<String>();
            if (_contextAttributes != null)
            {
                Enumeration<String> e = _contextAttributes.getAttributeNames();
                while (e.hasMoreElements())
                    set.add(e.nextElement());
            }
            Enumeration<String> e = _attributes.getAttributeNames();
            while (e.hasMoreElements())
                set.add(e.nextElement());

            return Collections.enumeration(set);
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#setAttribute(java.lang.String, java.lang.Object)
         */
        @Override
        public synchronized void setAttribute(String name, Object value)
        {
            checkManagedAttribute(name,value);
            Object old_value = _contextAttributes.getAttribute(name);

            if (value == null)
                _contextAttributes.removeAttribute(name);
            else
                _contextAttributes.setAttribute(name,value);

            if (_contextAttributeListeners != null)
            {
                ServletContextAttributeEvent event = new ServletContextAttributeEvent(_scontext,name,old_value == null?value:old_value);

                for (int i = 0; i < LazyList.size(_contextAttributeListeners); i++)
                {
                    ServletContextAttributeListener l = (ServletContextAttributeListener)LazyList.get(_contextAttributeListeners,i);

                    if (old_value == null)
                        l.attributeAdded(event);
                    else if (value == null)
                        l.attributeRemoved(event);
                    else
                        l.attributeReplaced(event);
                }
            }
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletContext#removeAttribute(java.lang.String)
         */
        @Override
        public synchronized void removeAttribute(String name)
        {
            checkManagedAttribute(name,null);

            if (_contextAttributes == null)
            {
                // Set it on the handler
                _attributes.removeAttribute(name);
                return;
            }

            Object old_value = _contextAttributes.getAttribute(name);
            _contextAttributes.removeAttribute(name);
            if (old_value != null)
            {
                if (_contextAttributeListeners != null)
                {
                    ServletContextAttributeEvent event = new ServletContextAttributeEvent(_scontext,name,old_value);

                    for (int i = 0; i < LazyList.size(_contextAttributeListeners); i++)
                        ((ServletContextAttributeListener)LazyList.get(_contextAttributeListeners,i)).attributeRemoved(event);
                }
            }
        }

        /* ------------------------------------------------------------ */
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

        /* ------------------------------------------------------------ */
        @Override
        public String getContextPath()
        {
            if ((_contextPath != null) && _contextPath.equals(URIUtil.SLASH))
                return "";

            return _contextPath;
        }

        /* ------------------------------------------------------------ */
        @Override
        public String toString()
        {
            return "ServletContext@" + ContextHandler.this.toString();
        }

        /* ------------------------------------------------------------ */
        @Override
        public boolean setInitParameter(String name, String value)
        {
            if (ContextHandler.this.getInitParameter(name) != null)
                return false;
            ContextHandler.this.getInitParams().put(name,value);
            return true;
        }

        /* ------------------------------------------------------------ */
        final private static String __unimplmented="Unimplemented - use org.eclipse.jetty.servlet.ServletContextHandler";

        @Override
        public Dynamic addFilter(String filterName, Class<? extends Filter> filterClass)
        {
            LOG.warn(__unimplmented);
            return null;
        }

        @Override
        public Dynamic addFilter(String filterName, Filter filter)
        {
            LOG.warn(__unimplmented);
            return null;
        }

        @Override
        public Dynamic addFilter(String filterName, String className)
        {
            LOG.warn(__unimplmented);
            return null;
        }

        @Override
        public javax.servlet.ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass)
        {
            LOG.warn(__unimplmented);
            return null;
        }

        @Override
        public javax.servlet.ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet)
        {
            LOG.warn(__unimplmented);
            return null;
        }

        @Override
        public javax.servlet.ServletRegistration.Dynamic addServlet(String servletName, String className)
        {
            LOG.warn(__unimplmented);
            return null;
        }

        @Override
        public <T extends Filter> T createFilter(Class<T> c) throws ServletException
        {
            LOG.warn(__unimplmented);
            return null;
        }

        @Override
        public <T extends Servlet> T createServlet(Class<T> c) throws ServletException
        {
            LOG.warn(__unimplmented);
            return null;
        }

        @Override
        public Set<SessionTrackingMode> getDefaultSessionTrackingModes()
        {
            LOG.warn(__unimplmented);
            return null;
        }

        @Override
        public Set<SessionTrackingMode> getEffectiveSessionTrackingModes()
        {
            LOG.warn(__unimplmented);
            return null;
        }

        @Override
        public FilterRegistration getFilterRegistration(String filterName)
        {
            LOG.warn(__unimplmented);
            return null;
        }

        @Override
        public Map<String, ? extends FilterRegistration> getFilterRegistrations()
        {
            LOG.warn(__unimplmented);
            return null;
        }

        @Override
        public ServletRegistration getServletRegistration(String servletName)
        {
            LOG.warn(__unimplmented);
            return null;
        }

        @Override
        public Map<String, ? extends ServletRegistration> getServletRegistrations()
        {
            LOG.warn(__unimplmented);
            return null;
        }

        @Override
        public SessionCookieConfig getSessionCookieConfig()
        {
            LOG.warn(__unimplmented);
            return null;
        }

        @Override
        public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes)
        {
            LOG.warn(__unimplmented);
        }

        @Override
        public void addListener(String className)
        {
            if (!_enabled)
                throw new UnsupportedOperationException();
            
            try
            {
                Class<? extends EventListener> clazz = _classLoader==null?Loader.loadClass(ContextHandler.class,className):_classLoader.loadClass(className);
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
            ContextHandler.this.addEventListener(t);
            ContextHandler.this.restrictEventListener(t);
        }

        @Override
        public void addListener(Class<? extends EventListener> listenerClass)
        {            
            if (!_enabled)
                throw new UnsupportedOperationException();

            try
            {
                EventListener e = createListener(listenerClass);
                ContextHandler.this.addEventListener(e);
                ContextHandler.this.restrictEventListener(e);
            }
            catch (ServletException e)
            {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException
        {
            try
            {
                return clazz.newInstance();
            }
            catch (InstantiationException e)
            {
                throw new ServletException(e);
            }
            catch (IllegalAccessException e)
            {
                throw new ServletException(e);
            }
        }

        @Override
        public ClassLoader getClassLoader()
        {
            AccessController.checkPermission(new RuntimePermission("getClassLoader"));
            return _classLoader;
        }

        @Override
        public int getEffectiveMajorVersion()
        {
            return _majorVersion;
        }

        @Override
        public int getEffectiveMinorVersion()
        {
            return _minorVersion;
        }

        public void setEffectiveMajorVersion (int v)
        {
            _majorVersion = v;
        }
        
        public void setEffectiveMinorVersion (int v)
        {
            _minorVersion = v;
        }
        
        @Override
        public JspConfigDescriptor getJspConfigDescriptor()
        {
            LOG.warn(__unimplmented);
            return null;
        }

        public void setJspConfigDescriptor(JspConfigDescriptor d)
        {
            
        }
        
        @Override
        public void declareRoles(String... roleNames)
        {
            if (!isStarting())
                throw new IllegalStateException ();
            if (!_enabled)
                throw new UnsupportedOperationException();
            
            // TODO Auto-generated method stub
            
        }

        public void setEnabled(boolean enabled)
        {
            _enabled = enabled;
        }

        public boolean isEnabled()
        {
            return _enabled;
        }
    }

    private static class CLDump implements Dumpable
    {
        final ClassLoader _loader;

        CLDump(ClassLoader loader)
        {
            _loader = loader;
        }

        public String dump()
        {
            return AggregateLifeCycle.dump(this);
        }

        public void dump(Appendable out, String indent) throws IOException
        {
            out.append(String.valueOf(_loader)).append("\n");

            if (_loader != null)
            {
                Object parent = _loader.getParent();
                if (parent != null)
                {
                    if (!(parent instanceof Dumpable))
                        parent = new CLDump((ClassLoader)parent);

                    if (_loader instanceof URLClassLoader)
                        AggregateLifeCycle.dump(out,indent,TypeUtil.asList(((URLClassLoader)_loader).getURLs()),Collections.singleton(parent));
                    else
                        AggregateLifeCycle.dump(out,indent,Collections.singleton(parent));
                }
            }
        }
    }
    
    
    /* ------------------------------------------------------------ */
    /** Interface to check aliases
     */
    public interface AliasCheck
    {
        /* ------------------------------------------------------------ */
        /** Check an alias
         * @param path The path the aliased resource was created for
         * @param resource The aliased resourced
         * @return True if the resource is OK to be served.
         */
        boolean check(String path, Resource resource);
    }
    
    
    /* ------------------------------------------------------------ */
    /** Approve Aliases with same suffix.
     * Eg. a symbolic link from /foobar.html to /somewhere/wibble.html would be
     * approved because both the resource and alias end with ".html".
     */
    @Deprecated
    public static class ApproveSameSuffixAliases implements AliasCheck
    {
        {
            LOG.warn("ApproveSameSuffixAlias is not safe for production");
        }
        
        public boolean check(String path, Resource resource)
        {
            int dot = path.lastIndexOf('.');
            if (dot<0)
                return false;
            String suffix=path.substring(dot);
            return resource.toString().endsWith(suffix);
        }
    }
    
    
    /* ------------------------------------------------------------ */
    /** Approve Aliases with a path prefix.
     * Eg. a symbolic link from /dirA/foobar.html to /dirB/foobar.html would be
     * approved because both the resource and alias end with "/foobar.html".
     */
    @Deprecated
    public static class ApprovePathPrefixAliases implements AliasCheck
    {
        {
            LOG.warn("ApprovePathPrefixAliases is not safe for production");
        }
        
        public boolean check(String path, Resource resource)
        {
            int slash = path.lastIndexOf('/');
            if (slash<0 || slash==path.length()-1)
                return false;
            String suffix=path.substring(slash);
            return resource.toString().endsWith(suffix);
        }
    }
    
    /* ------------------------------------------------------------ */
    /** Approve Aliases of a non existent directory.
     * If a directory "/foobar/" does not exist, then the resource is 
     * aliased to "/foobar".  Accept such aliases.
     */
    public static class ApproveNonExistentDirectoryAliases implements AliasCheck
    {
        public boolean check(String path, Resource resource)
        {
            if (resource.exists())
                return false;
            
            String a=resource.getAlias().toString();
            String r=resource.getURL().toString();
            
            if (a.length()>r.length())
                return a.startsWith(r) && a.length()==r.length()+1 && a.endsWith("/");
            else
                return r.startsWith(a) && r.length()==a.length()+1 && r.endsWith("/");
        }
    }
    
}
