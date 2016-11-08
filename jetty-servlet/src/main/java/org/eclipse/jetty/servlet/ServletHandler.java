//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.ServletSecurityElement;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.ServletRequestHttpWrapper;
import org.eclipse.jetty.server.ServletResponseHttpWrapper;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ScopedHandler;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Servlet HttpHandler.
 * <p>
 * This handler maps requests to servlets that implement the
 * javax.servlet.http.HttpServlet API.
 * <P>
 * This handler does not implement the full J2EE features and is intended to
 * be used directly when a full web application is not required.  If a Web application is required,
 * then this handler should be used as part of a <code>org.eclipse.jetty.webapp.WebAppContext</code>.
 * <p>
 * Unless run as part of a {@link ServletContextHandler} or derivative, the {@link #initialize()}
 * method must be called manually after start().
 */
@ManagedObject("Servlet Handler")
public class ServletHandler extends ScopedHandler
{
    private static final Logger LOG = Log.getLogger(ServletHandler.class);

    /* ------------------------------------------------------------ */
    public static final String __DEFAULT_SERVLET="default";

    /* ------------------------------------------------------------ */
    private ServletContextHandler _contextHandler;
    private ServletContext _servletContext;
    private FilterHolder[] _filters=new FilterHolder[0];
    private FilterMapping[] _filterMappings;
    private int _matchBeforeIndex = -1; //index of last programmatic FilterMapping with isMatchAfter=false
    private int _matchAfterIndex = -1;  //index of 1st programmatic FilterMapping with isMatchAfter=true
    private boolean _filterChainsCached=true;
    private int _maxFilterChainsCacheSize=512;
    private boolean _startWithUnavailable=false;
    private boolean _ensureDefaultServlet=true;
    private IdentityService _identityService;
    private boolean _allowDuplicateMappings=false;

    private ServletHolder[] _servlets=new ServletHolder[0];
    private ServletMapping[] _servletMappings;
    private final Map<String,FilterHolder> _filterNameMap= new HashMap<>();
    private List<FilterMapping> _filterPathMappings;
    private MultiMap<FilterMapping> _filterNameMappings;

    private final Map<String,ServletHolder> _servletNameMap=new HashMap<>();
    // private PathMap<ServletHolder> _servletPathMap;
    private PathMappings<ServletHolder> _servletPathMap;
    
    private ListenerHolder[] _listeners=new ListenerHolder[0];

    @SuppressWarnings("unchecked")
    protected final ConcurrentMap<String, FilterChain> _chainCache[] = new ConcurrentMap[FilterMapping.ALL];

    @SuppressWarnings("unchecked")
    protected final Queue<String>[] _chainLRU = new Queue[FilterMapping.ALL];



    /* ------------------------------------------------------------ */
    /** Constructor.
     */
    public ServletHandler()
    {
    }

    /* ----------------------------------------------------------------- */
    @Override
    protected synchronized void doStart()
        throws Exception
    {
        ContextHandler.Context context=ContextHandler.getCurrentContext();
        _servletContext=context==null?new ContextHandler.StaticContext():context;
        _contextHandler=(ServletContextHandler)(context==null?null:context.getContextHandler());

        if (_contextHandler!=null)
        {
            SecurityHandler security_handler = _contextHandler.getChildHandlerByClass(SecurityHandler.class);
            if (security_handler!=null)
                _identityService=security_handler.getIdentityService();
        }

        updateNameMappings();
        updateMappings();        
        
        if (getServletMapping("/")==null && isEnsureDefaultServlet())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Adding Default404Servlet to {}",this);
            addServletWithMapping(Default404Servlet.class,"/");
            updateMappings();  
            getServletMapping("/").setDefault(true);
        }

        if (isFilterChainsCached())
        {
            _chainCache[FilterMapping.REQUEST]=new ConcurrentHashMap<>();
            _chainCache[FilterMapping.FORWARD]=new ConcurrentHashMap<>();
            _chainCache[FilterMapping.INCLUDE]=new ConcurrentHashMap<>();
            _chainCache[FilterMapping.ERROR]=new ConcurrentHashMap<>();
            _chainCache[FilterMapping.ASYNC]=new ConcurrentHashMap<>();

            _chainLRU[FilterMapping.REQUEST]=new ConcurrentLinkedQueue<>();
            _chainLRU[FilterMapping.FORWARD]=new ConcurrentLinkedQueue<>();
            _chainLRU[FilterMapping.INCLUDE]=new ConcurrentLinkedQueue<>();
            _chainLRU[FilterMapping.ERROR]=new ConcurrentLinkedQueue<>();
            _chainLRU[FilterMapping.ASYNC]=new ConcurrentLinkedQueue<>();
        }

        if (_contextHandler==null)
            initialize();
        
        super.doStart();
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * @return true if ServletHandler always has a default servlet, using {@link Default404Servlet} if no other
     * default servlet is configured.
     */
    public boolean isEnsureDefaultServlet()
    {
        return _ensureDefaultServlet;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param ensureDefaultServlet true if ServletHandler always has a default servlet, using {@link Default404Servlet} if no other
     * default servlet is configured.
     */
    public void setEnsureDefaultServlet(boolean ensureDefaultServlet)
    {
        _ensureDefaultServlet=ensureDefaultServlet;
    }

    /* ----------------------------------------------------------------- */
    @Override
    protected void start(LifeCycle l) throws Exception
    {
        //Don't start the whole object tree (ie all the servlet and filter Holders) when
        //this handler starts. They have a slightly special lifecycle, and should only be
        //started AFTER the handlers have all started (and the ContextHandler has called
        //the context listeners).
        if (!(l instanceof Holder))
            super.start(l);
    }

    /* ----------------------------------------------------------------- */
    @Override
    protected synchronized void doStop()
        throws Exception
    {
        super.doStop();

        // Stop filters
        List<FilterHolder> filterHolders = new ArrayList<>();
        List<FilterMapping> filterMappings = ArrayUtil.asMutableList(_filterMappings);
        if (_filters!=null)
        {
            for (int i=_filters.length; i-->0;)
            {
                try 
                {
                    _filters[i].stop(); 
                }
                catch(Exception e)
                {
                    LOG.warn(Log.EXCEPTION,e);
                }
                if (_filters[i].getSource() != Source.EMBEDDED)
                {
                    //remove all of the mappings that were for non-embedded filters
                    _filterNameMap.remove(_filters[i].getName());
                    //remove any mappings associated with this filter
                    ListIterator<FilterMapping> fmitor = filterMappings.listIterator();
                    while (fmitor.hasNext())
                    {
                        FilterMapping fm = fmitor.next();
                        if (fm.getFilterName().equals(_filters[i].getName()))
                            fmitor.remove();
                    }
                }
                else
                    filterHolders.add(_filters[i]); //only retain embedded
            }
        }
        
        //Retain only filters and mappings that were added using jetty api (ie Source.EMBEDDED)
        FilterHolder[] fhs = (FilterHolder[]) LazyList.toArray(filterHolders, FilterHolder.class);
        updateBeans(_filters, fhs);
        _filters = fhs;
        FilterMapping[] fms = (FilterMapping[]) LazyList.toArray(filterMappings, FilterMapping.class);
        updateBeans(_filterMappings, fms);
        _filterMappings = fms;
        
        _matchAfterIndex = (_filterMappings == null || _filterMappings.length == 0 ? -1 : _filterMappings.length-1);
        _matchBeforeIndex = -1;

        // Stop servlets
        List<ServletHolder> servletHolders = new ArrayList<>();  //will be remaining servlets
        List<ServletMapping> servletMappings = ArrayUtil.asMutableList(_servletMappings); //will be remaining mappings
        if (_servlets!=null)
        {
            for (int i=_servlets.length; i-->0;)
            {
                try 
                { 
                    _servlets[i].stop(); 
                }
                catch(Exception e)
                {
                    LOG.warn(Log.EXCEPTION,e);
                }
                
                if (_servlets[i].getSource() != Source.EMBEDDED)
                {
                    //remove from servlet name map
                    _servletNameMap.remove(_servlets[i].getName());
                    //remove any mappings associated with this servlet
                    ListIterator<ServletMapping> smitor = servletMappings.listIterator();
                    while (smitor.hasNext())
                    {
                        ServletMapping sm = smitor.next();
                        if (sm.getServletName().equals(_servlets[i].getName()))
                            smitor.remove();
                    }
                }
                else
                    servletHolders.add(_servlets[i]); //only retain embedded 
            }
        }

        //Retain only Servlets and mappings added via jetty apis (ie Source.EMBEDDED)
        ServletHolder[] shs = (ServletHolder[]) LazyList.toArray(servletHolders, ServletHolder.class);
        updateBeans(_servlets, shs);
        _servlets = shs;
        ServletMapping[] sms = (ServletMapping[])LazyList.toArray(servletMappings, ServletMapping.class); 
        updateBeans(_servletMappings, sms);
        _servletMappings = sms;

        //Retain only Listeners added via jetty apis (is Source.EMBEDDED)
        List<ListenerHolder> listenerHolders = new ArrayList<>();
        if (_listeners != null)
        { 
            for (int i=_listeners.length; i-->0;)
            {
                try
                {
                    _listeners[i].stop();
                } 
                catch(Exception e)
                {
                    LOG.warn(Log.EXCEPTION,e);
                }
                if (_listeners[i].getSource() == Source.EMBEDDED)
                    listenerHolders.add(_listeners[i]);
            }
        }
        ListenerHolder[] listeners = (ListenerHolder[])LazyList.toArray(listenerHolders, ListenerHolder.class);
        updateBeans(_listeners, listeners);
        _listeners = listeners;

        //will be regenerated on next start
        _filterPathMappings=null;
        _filterNameMappings=null;
        _servletPathMap=null;
    }

    /* ------------------------------------------------------------ */
    protected IdentityService getIdentityService()
    {
        return _identityService;
    }

    @ManagedAttribute(value="filters", readonly=true)
    public FilterMapping[] getFilterMappings()
    {
        return _filterMappings;
    }

    @ManagedAttribute(value="filters", readonly=true)
    public FilterHolder[] getFilters()
    {
        return _filters;
    }

    /* ------------------------------------------------------------ */
    /**
     * ServletHolder matching path.
     *
     * @param pathInContext Path within _context.
     * @return PathMap Entries pathspec to ServletHolder
     */
    public MappedResource<ServletHolder> getHolderEntry(String pathInContext)
    {
        if (_servletPathMap==null)
            return null;
        return _servletPathMap.getMatch(pathInContext);
    }

    /* ------------------------------------------------------------ */
    public ServletContext getServletContext()
    {
        return _servletContext;
    }

    /* ------------------------------------------------------------ */
    @ManagedAttribute(value="mappings of servlets", readonly=true)
    public ServletMapping[] getServletMappings()
    {
        return _servletMappings;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Get the ServletMapping matching the path
     * 
     * @param pathSpec the path spec
     * @return the servlet mapping for the path spec (or null if not found)
     */
    public ServletMapping getServletMapping(String pathSpec)
    {
        if (pathSpec == null || _servletMappings == null)
            return null;

        ServletMapping mapping = null;
        for (int i=0; i<_servletMappings.length && mapping == null; i++)
        {
            ServletMapping m = _servletMappings[i];
            if (m.getPathSpecs() != null)
            {
                for (String p:m.getPathSpecs())
                {
                    if (pathSpec.equals(p))
                    {
                        mapping = m;
                        break;
                    }
                }
            }
        }
        return mapping;
    }

    @ManagedAttribute(value="servlets", readonly=true)
    public ServletHolder[] getServlets()
    {
        return _servlets;
    }

    public ServletHolder getServlet(String name)
    {
        return _servletNameMap.get(name);
    }

    @Override
    public void doScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        // Get the base requests
        final String old_servlet_path=baseRequest.getServletPath();
        final String old_path_info=baseRequest.getPathInfo();

        DispatcherType type = baseRequest.getDispatcherType();

        ServletHolder servlet_holder=null;
        UserIdentity.Scope old_scope=null;

        // find the servlet
        if (target.startsWith("/"))
        {
            // Look for the servlet by path
            MappedResource<ServletHolder> entry=getHolderEntry(target);
            if (entry!=null)
            {
                PathSpec pathSpec = entry.getPathSpec();
                servlet_holder=entry.getResource();

                String servlet_path=pathSpec.getPathMatch(target);
                String path_info=pathSpec.getPathInfo(target);

                if (DispatcherType.INCLUDE.equals(type))
                {
                    baseRequest.setAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH,servlet_path);
                    baseRequest.setAttribute(RequestDispatcher.INCLUDE_PATH_INFO, path_info);
                }
                else
                {
                    baseRequest.setServletPath(servlet_path);
                    baseRequest.setPathInfo(path_info);
                }
            }
        }
        else
        {
            // look for a servlet by name!
            servlet_holder= _servletNameMap.get(target);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("servlet {}|{}|{} -> {}",baseRequest.getContextPath(),baseRequest.getServletPath(),baseRequest.getPathInfo(),servlet_holder);

        try
        {
            // Do the filter/handling thang
            old_scope=baseRequest.getUserIdentityScope();
            baseRequest.setUserIdentityScope(servlet_holder);

            nextScope(target,baseRequest,request,response);
        }
        finally
        {
            if (old_scope!=null)
                baseRequest.setUserIdentityScope(old_scope);

            if (!(DispatcherType.INCLUDE.equals(type)))
            {
                baseRequest.setServletPath(old_servlet_path);
                baseRequest.setPathInfo(old_path_info);
            }
        }
    }

    @Override
    public void doHandle(String target, Request baseRequest,HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        ServletHolder servlet_holder=(ServletHolder) baseRequest.getUserIdentityScope();
        FilterChain chain=null;

        // find the servlet
        if (target.startsWith("/"))
        {
            if (servlet_holder!=null && _filterMappings!=null && _filterMappings.length>0)
                chain=getFilterChain(baseRequest, target, servlet_holder);
        }
        else
        {
            if (servlet_holder!=null)
            {
                if (_filterMappings!=null && _filterMappings.length>0)
                {
                    chain=getFilterChain(baseRequest, null,servlet_holder);
                }
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("chain={}",chain);

        try
        {
            if (servlet_holder==null)
                notFound(baseRequest,request, response);
            else
            {
                // unwrap any tunnelling of base Servlet request/responses
                ServletRequest req = request;
                if (req instanceof ServletRequestHttpWrapper)
                    req = ((ServletRequestHttpWrapper)req).getRequest();
                ServletResponse res = response;
                if (res instanceof ServletResponseHttpWrapper)
                    res = ((ServletResponseHttpWrapper)res).getResponse();

                // Do the filter/handling thang
                servlet_holder.prepare(baseRequest, req, res);
                
                if (chain!=null)
                    chain.doFilter(req, res);
                else
                    servlet_holder.handle(baseRequest,req,res);
            }
        }
        finally
        {
            if (servlet_holder!=null)
                baseRequest.setHandled(true);
        }
    }

    protected FilterChain getFilterChain(Request baseRequest, String pathInContext, ServletHolder servletHolder)
    {
        String key=pathInContext==null?servletHolder.getName():pathInContext;
        int dispatch = FilterMapping.dispatch(baseRequest.getDispatcherType());

        if (_filterChainsCached && _chainCache!=null)
        {
            FilterChain chain = _chainCache[dispatch].get(key);
            if (chain!=null)
                return chain;
        }

        // Build list of filters (list of FilterHolder objects)
        List<FilterHolder> filters = new ArrayList<>();

        // Path filters
        if (pathInContext!=null && _filterPathMappings!=null)
        {
            for (FilterMapping filterPathMapping : _filterPathMappings)
            {
                if (filterPathMapping.appliesTo(pathInContext, dispatch))
                    filters.add(filterPathMapping.getFilterHolder());
            }
        }

        // Servlet name filters
        if (servletHolder != null && _filterNameMappings!=null && !_filterNameMappings.isEmpty())
        {
            Object o= _filterNameMappings.get(servletHolder.getName());

            for (int i=0; i<LazyList.size(o);i++)
            {
                FilterMapping mapping = LazyList.get(o,i);
                if (mapping.appliesTo(dispatch))
                    filters.add(mapping.getFilterHolder());
            }

            o= _filterNameMappings.get("*");
            for (int i=0; i<LazyList.size(o);i++)
            {
                FilterMapping mapping = LazyList.get(o,i);
                if (mapping.appliesTo(dispatch))
                    filters.add(mapping.getFilterHolder());
            }
        }

        if (filters.isEmpty())
            return null;


        FilterChain chain = null;
        if (_filterChainsCached)
        {
            if (filters.size() > 0)
                chain = newCachedChain(filters, servletHolder);

            final Map<String,FilterChain> cache=_chainCache[dispatch];
            final Queue<String> lru=_chainLRU[dispatch];

                // Do we have too many cached chains?
                while (_maxFilterChainsCacheSize>0 && cache.size()>=_maxFilterChainsCacheSize)
                {
                    // The LRU list is not atomic with the cache map, so be prepared to invalidate if
                    // a key is not found to delete.
                    // Delete by LRU (where U==created)
                    String k=lru.poll();
                    if (k==null)
                    {
                        cache.clear();
                        break;
                    }
                    cache.remove(k);
                }

                cache.put(key,chain);
                lru.add(key);
        }
        else if (filters.size() > 0)
            chain = new Chain(baseRequest,filters, servletHolder);

        return chain;
    }

    /* ------------------------------------------------------------ */
    protected void invalidateChainsCache()
    {
        if (_chainLRU[FilterMapping.REQUEST]!=null)
        {
            _chainLRU[FilterMapping.REQUEST].clear();
            _chainLRU[FilterMapping.FORWARD].clear();
            _chainLRU[FilterMapping.INCLUDE].clear();
            _chainLRU[FilterMapping.ERROR].clear();
            _chainLRU[FilterMapping.ASYNC].clear();

            _chainCache[FilterMapping.REQUEST].clear();
            _chainCache[FilterMapping.FORWARD].clear();
            _chainCache[FilterMapping.INCLUDE].clear();
            _chainCache[FilterMapping.ERROR].clear();
            _chainCache[FilterMapping.ASYNC].clear();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return true if the handler is started and there are no unavailable servlets
     */
    public boolean isAvailable()
    {
        if (!isStarted())
            return false;
        ServletHolder[] holders = getServlets();
        for (ServletHolder holder : holders)
        {
            if (holder != null && !holder.isAvailable())
                return false;
        }
        return true;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param start True if this handler will start with unavailable servlets
     */
    public void setStartWithUnavailable(boolean start)
    {
        _startWithUnavailable=start;
    }

    /**
     * @return the allowDuplicateMappings
     */
    public boolean isAllowDuplicateMappings()
    {
        return _allowDuplicateMappings;
    }

    /**
     * @param allowDuplicateMappings the allowDuplicateMappings to set
     */
    public void setAllowDuplicateMappings(boolean allowDuplicateMappings)
    {
        _allowDuplicateMappings = allowDuplicateMappings;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if this handler will start with unavailable servlets
     */
    public boolean isStartWithUnavailable()
    {
        return _startWithUnavailable;
    }



    /* ------------------------------------------------------------ */
    /** Initialize filters and load-on-startup servlets.
     * @throws Exception if unable to initialize
     */
    public void initialize()
        throws Exception
    {
        MultiException mx = new MultiException();

        //start filter holders now
        if (_filters != null)
        {
            for (FilterHolder f: _filters)
            {
                try
                {
                    f.start();
                    f.initialize();
                }
                catch (Exception e)
                {
                    mx.add(e);
                }
            }
        }
        
        // Sort and Initialize servlets
        if (_servlets!=null)
        {
            ServletHolder[] servlets = _servlets.clone();
            Arrays.sort(servlets);
            for (ServletHolder servlet : servlets)
            {
                try
                {
                    servlet.start();
                    servlet.initialize();
                }
                catch (Throwable e)
                {
                    LOG.debug(Log.EXCEPTION, e);
                    mx.add(e);
                }
            }
        }

        //any other beans
        for (Holder<?> h: getBeans(Holder.class))
        {
            try
            {
                if (!h.isStarted())
                {
                    h.start();
                    h.initialize();
                }
            }
            catch (Exception e)
            {
                mx.add(e);
            }
        }
        
        mx.ifExceptionThrow();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return whether the filter chains are cached.
     */
    public boolean isFilterChainsCached()
    {
        return _filterChainsCached;
    }
    
    /* ------------------------------------------------------------ */
    /** Add a holder for a listener
     * @param listener the listener for the holder
     */
    public void addListener (ListenerHolder listener)
    {
        if (listener != null)
            setListeners(ArrayUtil.addToArray(getListeners(), listener, ListenerHolder.class));
    }
    
    
    /* ------------------------------------------------------------ */
    public ListenerHolder[] getListeners()
    {
        return _listeners;
    }
    
    /* ------------------------------------------------------------ */
    public void setListeners(ListenerHolder[] listeners)
    {
        if (listeners!=null)
            for (ListenerHolder holder:listeners)
                holder.setServletHandler(this);

        updateBeans(_listeners,listeners);
        _listeners = listeners;
    }
    
    /* ------------------------------------------------------------ */
    public ListenerHolder newListenerHolder(Source source)
    {
        return new ListenerHolder(source);
    }

    /* ------------------------------------------------------------ */
    /**
     * Create a new CachedChain 
     */
    public CachedChain newCachedChain(List<FilterHolder> filters, ServletHolder servletHolder)
    {
        return new CachedChain(filters, servletHolder);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Add a new servlet holder
     * @param source the holder source
     * @return the servlet holder
     */
    public ServletHolder newServletHolder(Source source)
    {
        return new ServletHolder(source);
    }

    /* ------------------------------------------------------------ */
    /** Convenience method to add a servlet.
     * @param className the class name
     * @param pathSpec the path spec
     * @return The servlet holder.
     */
    public ServletHolder addServletWithMapping (String className,String pathSpec)
    {
        ServletHolder holder = newServletHolder(Source.EMBEDDED);
        holder.setClassName(className);
        addServletWithMapping(holder,pathSpec);
        return holder;
    }

    /* ------------------------------------------------------------ */
    /** Convenience method to add a servlet.
     * @param servlet the servlet class
     * @param pathSpec the path spec
     * @return The servlet holder.
     */
    public ServletHolder addServletWithMapping (Class<? extends Servlet> servlet,String pathSpec)
    {
        ServletHolder holder = newServletHolder(Source.EMBEDDED);
        holder.setHeldClass(servlet);
        addServletWithMapping(holder,pathSpec);

        return holder;
    }

    /* ------------------------------------------------------------ */
    /** Convenience method to add a servlet.
     * @param servlet servlet holder to add
     * @param pathSpec servlet mappings for the servletHolder
     */
    public void addServletWithMapping (ServletHolder servlet,String pathSpec)
    {
        ServletHolder[] holders=getServlets();
        if (holders!=null)
            holders = holders.clone();

        try
        {
            synchronized (this)
            {
                if (servlet != null && !containsServletHolder(servlet))
                    setServlets(ArrayUtil.addToArray(holders, servlet, ServletHolder.class));
            }

            ServletMapping mapping = new ServletMapping();
            mapping.setServletName(servlet.getName());
            mapping.setPathSpec(pathSpec);
            setServletMappings(ArrayUtil.addToArray(getServletMappings(), mapping, ServletMapping.class));
        }
        catch (RuntimeException e)
        {
            setServlets(holders);
            throw e;
        }
    }


    /* ------------------------------------------------------------ */
    /**
     * Convenience method to add a pre-constructed ServletHolder.
     * @param holder the servlet holder
     */
    public void addServlet(ServletHolder holder)
    {
        if (holder == null)
            return;
        
        synchronized (this)
        {
            if (!containsServletHolder(holder))
                setServlets(ArrayUtil.addToArray(getServlets(), holder, ServletHolder.class));
        }
    }

    /* ------------------------------------------------------------ */
    /** 
     * Convenience method to add a pre-constructed ServletMapping.
     * @param mapping the servlet mapping
     */
    public void addServletMapping (ServletMapping mapping)
    {
        setServletMappings(ArrayUtil.addToArray(getServletMappings(), mapping, ServletMapping.class));
    }
    
    /* ------------------------------------------------------------ */
    public Set<String>  setServletSecurity(ServletRegistration.Dynamic registration, ServletSecurityElement servletSecurityElement) 
    {
        if (_contextHandler != null) 
        {
            return _contextHandler.setServletSecurity(registration, servletSecurityElement);
        }
        return Collections.emptySet();
    }

    /* ------------------------------------------------------------ */
    public FilterHolder newFilterHolder(Source source)
    {
        return new FilterHolder(source);
    }

    /* ------------------------------------------------------------ */
    public FilterHolder getFilter(String name)
    {
        return _filterNameMap.get(name);
    }


    /* ------------------------------------------------------------ */
    /** Convenience method to add a filter.
     * @param filter  class of filter to create
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     * @return The filter holder.
     */
    public FilterHolder addFilterWithMapping (Class<? extends Filter> filter,String pathSpec,EnumSet<DispatcherType> dispatches)
    {
        FilterHolder holder = newFilterHolder(Source.EMBEDDED);
        holder.setHeldClass(filter);
        addFilterWithMapping(holder,pathSpec,dispatches);

        return holder;
    }

    /* ------------------------------------------------------------ */
    /** Convenience method to add a filter.
     * @param className of filter
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     * @return The filter holder.
     */
    public FilterHolder addFilterWithMapping (String className,String pathSpec,EnumSet<DispatcherType> dispatches)
    {
        FilterHolder holder = newFilterHolder(Source.EMBEDDED);
        holder.setClassName(className);

        addFilterWithMapping(holder,pathSpec,dispatches);
        return holder;
    }

    /* ------------------------------------------------------------ */
    /** Convenience method to add a filter.
     * @param holder filter holder to add
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     */
    public void addFilterWithMapping (FilterHolder holder,String pathSpec,EnumSet<DispatcherType> dispatches)
    {
        FilterHolder[] holders = getFilters();
        if (holders!=null)
            holders = holders.clone();

        try
        {
            synchronized (this)
            {
                if (holder != null && !containsFilterHolder(holder))
                    setFilters(ArrayUtil.addToArray(holders, holder, FilterHolder.class));
            }

            FilterMapping mapping = new FilterMapping();
            mapping.setFilterName(holder.getName());
            mapping.setPathSpec(pathSpec);
            mapping.setDispatcherTypes(dispatches);
            addFilterMapping(mapping);
            
        }
        catch (Throwable e)
        {
            setFilters(holders);
            throw e;
        }
    }

    /* ------------------------------------------------------------ */
    /** Convenience method to add a filter.
     * @param filter  class of filter to create
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     * @return The filter holder.
     */
    public FilterHolder addFilterWithMapping (Class<? extends Filter> filter,String pathSpec,int dispatches)
    {
        FilterHolder holder = newFilterHolder(Source.EMBEDDED);
        holder.setHeldClass(filter);
        addFilterWithMapping(holder,pathSpec,dispatches);

        return holder;
    }

    /* ------------------------------------------------------------ */
    /** Convenience method to add a filter.
     * @param className of filter
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     * @return The filter holder.
     */
    public FilterHolder addFilterWithMapping (String className,String pathSpec,int dispatches)
    {
        FilterHolder holder = newFilterHolder(Source.EMBEDDED);
        holder.setClassName(className);

        addFilterWithMapping(holder,pathSpec,dispatches);
        return holder;
    }

    /* ------------------------------------------------------------ */
    /** Convenience method to add a filter.
     * @param holder filter holder to add
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     */
    public void addFilterWithMapping (FilterHolder holder,String pathSpec,int dispatches)
    {
        FilterHolder[] holders = getFilters();
        if (holders!=null)
            holders = holders.clone();

        try
        {
            synchronized (this)
            {
                if (holder != null && !containsFilterHolder(holder))
                    setFilters(ArrayUtil.addToArray(holders, holder, FilterHolder.class));
            }

            FilterMapping mapping = new FilterMapping();
            mapping.setFilterName(holder.getName());
            mapping.setPathSpec(pathSpec);
            mapping.setDispatches(dispatches);
            addFilterMapping(mapping);
        }
        catch (Throwable e)
        {
            setFilters(holders);
            throw e;
        }

    }

    /* ------------------------------------------------------------ */
    /** 
     * Convenience method to add a filter with a mapping
     *
     * @param className the filter class name
     * @param pathSpec the path spec
     * @param dispatches the dispatcher types for this filter
     * @return the filter holder created
     * @deprecated use {@link #addFilterWithMapping(Class, String, EnumSet)} instead
     */
    @Deprecated
    public FilterHolder addFilter (String className,String pathSpec,EnumSet<DispatcherType> dispatches)
    {
        return addFilterWithMapping(className, pathSpec, dispatches);
    }

    /* ------------------------------------------------------------ */
    /**
     * Convenience method to add a filter and mapping
     * @param filter the filter holder
     * @param filterMapping the filter mapping
     */
    public void addFilter (FilterHolder filter, FilterMapping filterMapping)
    {
        if (filter != null)
        {
            synchronized (this)
            {
                if (!containsFilterHolder(filter))
                    setFilters(ArrayUtil.addToArray(getFilters(), filter, FilterHolder.class));
            }
        }
        if (filterMapping != null)
            addFilterMapping(filterMapping);
    }

    /* ------------------------------------------------------------ */
    /** 
     * Convenience method to add a preconstructed FilterHolder
     *
     * @param filter the filter holder
     */
    public void addFilter (FilterHolder filter)
    {
        if (filter == null)
            return;

        synchronized (this)
        {
            if (!containsFilterHolder(filter))
                setFilters(ArrayUtil.addToArray(getFilters(), filter, FilterHolder.class));
        }
    }

    /* ------------------------------------------------------------ */
    /** 
     * Convenience method to add a preconstructed FilterMapping
     *
     * @param mapping the filter mapping
     */
    public void addFilterMapping (FilterMapping mapping)
    {
        if (mapping != null)
        {
            Source source = (mapping.getFilterHolder()==null?null:mapping.getFilterHolder().getSource());
            FilterMapping[] mappings =getFilterMappings();
            if (mappings==null || mappings.length==0)
            {
                setFilterMappings(insertFilterMapping(mapping,0,false));
                if (source != null && source == Source.JAVAX_API)
                    _matchAfterIndex = 0;
            }
            else
            {
                //there are existing entries. If this is a programmatic filtermapping, it is added at the end of the list.
                //If this is a normal filtermapping, it is inserted after all the other filtermappings (matchBefores and normals), 
                //but before the first matchAfter filtermapping.
                if (source != null && Source.JAVAX_API == source)
                {
                    setFilterMappings(insertFilterMapping(mapping,mappings.length-1, false));
                    if (_matchAfterIndex < 0)
                        _matchAfterIndex = getFilterMappings().length-1;
                }
                else
                {
                    //insert non-programmatic filter mappings before any matchAfters, if any
                    if (_matchAfterIndex < 0)
                        setFilterMappings(insertFilterMapping(mapping,mappings.length-1, false));
                    else
                    {
                        FilterMapping[] new_mappings = insertFilterMapping(mapping, _matchAfterIndex, true);
                        ++_matchAfterIndex;
                        setFilterMappings(new_mappings);
                    }
                }
            }
        }
    }
    

    /* ------------------------------------------------------------ */
    /** 
     * Convenience method to add a preconstructed FilterMapping
     * @param mapping the filter mapping
     */
    public void prependFilterMapping (FilterMapping mapping)
    {
        if (mapping != null)
        {
            Source source = (mapping.getFilterHolder()==null?null:mapping.getFilterHolder().getSource());
            FilterMapping[] mappings = getFilterMappings();
            if (mappings==null || mappings.length==0)
            {
                setFilterMappings(insertFilterMapping(mapping, 0, false));
                if (source != null && Source.JAVAX_API == source)
                    _matchBeforeIndex = 0;
            }
            else
            {
                if (source != null && Source.JAVAX_API == source)
                {
                    //programmatically defined filter mappings are prepended to mapping list in the order
                    //in which they were defined. In other words, insert this mapping at the tail of the 
                    //programmatically prepended filter mappings, BEFORE the first web.xml defined filter mapping.

                    if (_matchBeforeIndex < 0)
                    { 
                        //no programmatically defined prepended filter mappings yet, prepend this one
                        _matchBeforeIndex = 0;
                        FilterMapping[] new_mappings = insertFilterMapping(mapping, 0, true);
                        setFilterMappings(new_mappings);
                    }
                    else
                    {
                        FilterMapping[] new_mappings = insertFilterMapping(mapping,_matchBeforeIndex, false);
                        ++_matchBeforeIndex;
                        setFilterMappings(new_mappings);
                    }
                }
                else
                {
                    //non programmatically defined, just prepend to list
                    FilterMapping[] new_mappings = insertFilterMapping(mapping, 0, true);
                    setFilterMappings(new_mappings);
                }
                
                //adjust matchAfterIndex ptr to take account of the mapping we just prepended
                if (_matchAfterIndex >= 0)
                    ++_matchAfterIndex;
            }
        }
    }
    
    
    
    /**
     * Insert a filtermapping in the list
     * @param mapping the FilterMapping to add
     * @param pos the position in the existing arry at which to add it
     * @param before if true, insert before  pos, if false insert after it
     * @return the new FilterMappings post-insert
     */
    protected FilterMapping[] insertFilterMapping (FilterMapping mapping, int pos, boolean before)
    {
        if (pos < 0)
            throw new IllegalArgumentException("FilterMapping insertion pos < 0");
        FilterMapping[] mappings = getFilterMappings();
        
        if (mappings==null || mappings.length==0)
        {
            return new FilterMapping[] {mapping};
        }
        FilterMapping[] new_mappings = new FilterMapping[mappings.length+1];

    
        if (before)
        {
            //copy existing filter mappings up to but not including the pos
            System.arraycopy(mappings,0,new_mappings,0,pos);

            //add in the new mapping
            new_mappings[pos] = mapping; 

            //copy the old pos mapping and any remaining existing mappings
            System.arraycopy(mappings,pos,new_mappings,pos+1, mappings.length-pos);

        }
        else
        {
            //copy existing filter mappings up to and including the pos
            System.arraycopy(mappings,0,new_mappings,0,pos+1);
            //add in the new mapping after the pos
            new_mappings[pos+1] = mapping;   

            //copy the remaining existing mappings
            if (mappings.length > pos+1)
                System.arraycopy(mappings,pos+1,new_mappings,pos+2, mappings.length-(pos+1));
        }
        return new_mappings;
    }
    
    
    /* ------------------------------------------------------------ */
    protected synchronized void updateNameMappings()
    {
        // update filter name map
        _filterNameMap.clear();
        if (_filters!=null)
        {
            for (FilterHolder filter : _filters)
            {
                _filterNameMap.put(filter.getName(), filter);
                filter.setServletHandler(this);
            }
        }

        // Map servlet names to holders
        _servletNameMap.clear();
        if (_servlets!=null)
        {
            // update the maps
            for (ServletHolder servlet : _servlets)
            {
                _servletNameMap.put(servlet.getName(), servlet);
                servlet.setServletHandler(this);
            }
        }
    }

    /* ------------------------------------------------------------ */
    protected synchronized void updateMappings()
    {
        // update filter mappings
        if (_filterMappings==null)
        {
            _filterPathMappings=null;
            _filterNameMappings=null;
        }
        else
        {
            _filterPathMappings=new ArrayList<>();
            _filterNameMappings=new MultiMap<>();
            for (FilterMapping filtermapping : _filterMappings)
            {
                FilterHolder filter_holder = _filterNameMap.get(filtermapping.getFilterName());
                if (filter_holder == null)
                    throw new IllegalStateException("No filter named " + filtermapping.getFilterName());
                filtermapping.setFilterHolder(filter_holder);
                if (filtermapping.getPathSpecs() != null)
                    _filterPathMappings.add(filtermapping);

                if (filtermapping.getServletNames() != null)
                {
                    String[] names = filtermapping.getServletNames();
                    for (String name : names)
                    {
                        if (name != null)
                            _filterNameMappings.add(name, filtermapping);
                    }
                }
            }
        }

        // Map servlet paths to holders
        if (_servletMappings==null || _servletNameMap==null)
        {
            _servletPathMap=null;
        }
        else
        {
            PathMappings<ServletHolder> pm = new PathMappings<>();
            Map<String,ServletMapping> servletPathMappings = new HashMap<>();

            //create a map of paths to set of ServletMappings that define that mapping
            HashMap<String, List<ServletMapping>> sms = new HashMap<>();
            for (ServletMapping servletMapping : _servletMappings)
            {
                String[] pathSpecs = servletMapping.getPathSpecs();
                if (pathSpecs != null)
                {
                    for (String pathSpec : pathSpecs)
                    {
                        List<ServletMapping> mappings = sms.get(pathSpec);
                        if (mappings == null)
                        {
                            mappings = new ArrayList<>();
                            sms.put(pathSpec, mappings);
                        }
                        mappings.add(servletMapping);
                    }
                }
            }
         
            //evaluate path to servlet map based on servlet mappings
            for (String pathSpec : sms.keySet())
            {
                //for each path, look at the mappings where it is referenced
                //if a mapping is for a servlet that is not enabled, skip it
                List<ServletMapping> mappings = sms.get(pathSpec);

                ServletMapping finalMapping = null;
                for (ServletMapping mapping : mappings)
                {
                    //Get servlet associated with the mapping and check it is enabled
                    ServletHolder servlet_holder = _servletNameMap.get(mapping.getServletName());
                    if (servlet_holder == null)
                        throw new IllegalStateException("No such servlet: " + mapping.getServletName());
                    //if the servlet related to the mapping is not enabled, skip it from consideration
                    if (!servlet_holder.isEnabled())
                        continue;

                    //only accept a default mapping if we don't have any other
                    if (finalMapping == null)
                        finalMapping = mapping;
                    else
                    {
                        //already have a candidate - only accept another one 
                        //if the candidate is a default, or we're allowing duplicate mappings
                        if (finalMapping.isDefault())
                            finalMapping = mapping;
                        else if (isAllowDuplicateMappings())
                        {
                            LOG.warn("Multiple servlets map to path {}: {} and {}, choosing {}", pathSpec, finalMapping.getServletName(), mapping.getServletName(), mapping);
                            finalMapping = mapping;
                        }
                        else
                        {
                            //existing candidate isn't a default, if the one we're looking at isn't a default either, then its an error
                            if (!mapping.isDefault())          
                            {
                                ServletHolder finalMappedServlet = _servletNameMap.get(finalMapping.getServletName());
                                throw new IllegalStateException("Multiple servlets map to path "
                                        +pathSpec+": "
                                        +finalMappedServlet.getName()+"[mapped:"+finalMapping.getSource()+"],"
                                        +mapping.getServletName()+"[mapped:"+mapping.getSource()+"]");
                            }
                        }
                    }
                }
                if (finalMapping == null)
                    throw new IllegalStateException ("No acceptable servlet mappings for "+pathSpec);
           
                if (LOG.isDebugEnabled()) 
                    LOG.debug("Path={}[{}] mapped to servlet={}[{}]",
                              pathSpec,
                              finalMapping.getSource(),
                              finalMapping.getServletName(),
                              _servletNameMap.get(finalMapping.getServletName()).getSource());

                servletPathMappings.put(pathSpec, finalMapping);
                pm.put(new ServletPathSpec(pathSpec),_servletNameMap.get(finalMapping.getServletName()));
            }
     
            _servletPathMap=pm;
        }

        // flush filter chain cache
        if (_chainCache!=null)
        {
            for (int i=_chainCache.length;i-->0;)
            {
                if (_chainCache[i]!=null)
                    _chainCache[i].clear();
            }
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("filterNameMap="+_filterNameMap);
            LOG.debug("pathFilters="+_filterPathMappings);
            LOG.debug("servletFilterMap="+_filterNameMappings);
            LOG.debug("servletPathMap="+_servletPathMap);
            LOG.debug("servletNameMap="+_servletNameMap);
        }

        try
        {
            if (_contextHandler!=null && _contextHandler.isStarted() || _contextHandler==null && isStarted())
                initialize();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /* ------------------------------------------------------------ */
    protected void notFound(Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Not Found {}",request.getRequestURI());
        if (getHandler()!=null)
            nextHandle(URIUtil.addPaths(request.getServletPath(),request.getPathInfo()),baseRequest,request,response);
    }
    
    
    protected synchronized boolean containsFilterHolder (FilterHolder holder)
    {
        if (_filters == null)
            return false;
        boolean found = false;
        for (FilterHolder f:_filters)
        {
            if (f == holder)
                found = true;
        }
        return found;
    }

    
    protected synchronized boolean containsServletHolder (ServletHolder holder)
    {
        if (_servlets == null)
            return false;
        boolean found = false;
        for (ServletHolder s:_servlets)
        {
            if (s == holder)
                found = true;
        }
        return found;
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * @param filterChainsCached The filterChainsCached to set.
     */
    public void setFilterChainsCached(boolean filterChainsCached)
    {
        _filterChainsCached = filterChainsCached;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param filterMappings The filterMappings to set.
     */
    public void setFilterMappings(FilterMapping[] filterMappings)
    {
        updateBeans(_filterMappings,filterMappings);
        _filterMappings = filterMappings;
        if (isStarted()) updateMappings();
        invalidateChainsCache();
    }

    /* ------------------------------------------------------------ */
    public synchronized void setFilters(FilterHolder[] holders)
    {
        if (holders!=null)
            for (FilterHolder holder:holders)
                holder.setServletHandler(this);
        
        updateBeans(_filters,holders);
        _filters=holders;
        updateNameMappings();
        invalidateChainsCache();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param servletMappings The servletMappings to set.
     */
    public void setServletMappings(ServletMapping[] servletMappings)
    {
        updateBeans(_servletMappings,servletMappings);
        _servletMappings = servletMappings;
        if (isStarted()) updateMappings();
        invalidateChainsCache();
    }

    /* ------------------------------------------------------------ */
    /** Set Servlets.
     * @param holders Array of servlets to define
     */
    public synchronized void setServlets(ServletHolder[] holders)
    {
        if (holders!=null)
            for (ServletHolder holder:holders)
                holder.setServletHandler(this);
        
        updateBeans(_servlets,holders);
        _servlets=holders;
        updateNameMappings();
        invalidateChainsCache();
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    protected class CachedChain implements FilterChain
    {
        FilterHolder _filterHolder;
        CachedChain _next;
        ServletHolder _servletHolder;

        /* ------------------------------------------------------------ */
        /**
         * @param filters list of {@link FilterHolder} objects
         * @param servletHolder
         */
        protected CachedChain(List<FilterHolder> filters, ServletHolder servletHolder)
        {
            if (filters.size()>0)
            {
                _filterHolder=filters.get(0);
                filters.remove(0);
                _next=new CachedChain(filters,servletHolder);
            }
            else
                _servletHolder=servletHolder;
        }

        /* ------------------------------------------------------------ */
        @Override
        public void doFilter(ServletRequest request, ServletResponse response)
            throws IOException, ServletException
        {
            final Request baseRequest=Request.getBaseRequest(request);

            // pass to next filter
            if (_filterHolder!=null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("call filter {}", _filterHolder);
                Filter filter= _filterHolder.getFilter();
                
                //if the request already does not support async, then the setting for the filter
                //is irrelevant. However if the request supports async but this filter does not
                //temporarily turn it off for the execution of the filter
                if (baseRequest.isAsyncSupported() && !_filterHolder.isAsyncSupported())
                { 
                    try
                    {
                        baseRequest.setAsyncSupported(false,_filterHolder.toString());
                        filter.doFilter(request, response, _next);
                    }
                    finally
                    {
                        baseRequest.setAsyncSupported(true,null);
                    }
                }
                else
                    filter.doFilter(request, response, _next);

                return;
            }

            // Call servlet
            HttpServletRequest srequest = (HttpServletRequest)request;
            if (_servletHolder == null)
                notFound(baseRequest, srequest, (HttpServletResponse)response);
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("call servlet " + _servletHolder);
                _servletHolder.handle(baseRequest,request, response);
            }
        }

        @Override
        public String toString()
        {
            if (_filterHolder!=null)
                return _filterHolder+"->"+_next.toString();
            if (_servletHolder!=null)
                return _servletHolder.toString();
            return "null";
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class Chain implements FilterChain
    {
        final Request _baseRequest;
        final List<FilterHolder> _chain;
        final ServletHolder _servletHolder;
        int _filter= 0;

        /* ------------------------------------------------------------ */
        private Chain(Request baseRequest, List<FilterHolder> filters, ServletHolder servletHolder)
        {
            _baseRequest=baseRequest;
            _chain= filters;
            _servletHolder= servletHolder;
        }

        /* ------------------------------------------------------------ */
        @Override
        public void doFilter(ServletRequest request, ServletResponse response)
            throws IOException, ServletException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("doFilter " + _filter);

            // pass to next filter
            if (_filter < _chain.size())
            {
                FilterHolder holder= _chain.get(_filter++);
                if (LOG.isDebugEnabled())
                    LOG.debug("call filter " + holder);
                Filter filter= holder.getFilter();

                //if the request already does not support async, then the setting for the filter
                //is irrelevant. However if the request supports async but this filter does not
                //temporarily turn it off for the execution of the filter
                if (!holder.isAsyncSupported() && _baseRequest.isAsyncSupported())
                {
                    try
                    {
                        _baseRequest.setAsyncSupported(false,holder.toString());
                        filter.doFilter(request, response, this);
                    }
                    finally
                    {
                        _baseRequest.setAsyncSupported(true,null);
                    }
                }
                else
                    filter.doFilter(request, response, this);

                return;
            }

            // Call servlet
            HttpServletRequest srequest = (HttpServletRequest)request;
            if (_servletHolder == null)
                notFound(Request.getBaseRequest(request), srequest, (HttpServletResponse)response);
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("call servlet {}", _servletHolder);
                _servletHolder.handle(_baseRequest,request, response);
            }    
        }

        /* ------------------------------------------------------------ */
        @Override
        public String toString()
        {
            StringBuilder b = new StringBuilder();
            for(FilterHolder f: _chain)
            {
                b.append(f.toString());
                b.append("->");
            }
            b.append(_servletHolder);
            return b.toString();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The maximum entries in a filter chain cache.
     */
    public int getMaxFilterChainsCacheSize()
    {
        return _maxFilterChainsCacheSize;
    }

    /* ------------------------------------------------------------ */
    /** Set the maximum filter chain cache size.
     * Filter chains are cached if {@link #isFilterChainsCached()} is true. If the max cache size
     * is greater than zero, then the cache is flushed whenever it grows to be this size.
     *
     * @param maxFilterChainsCacheSize  the maximum number of entries in a filter chain cache.
     */
    public void setMaxFilterChainsCacheSize(int maxFilterChainsCacheSize)
    {
        _maxFilterChainsCacheSize = maxFilterChainsCacheSize;
    }

    /* ------------------------------------------------------------ */
    void destroyServlet(Servlet servlet)
    {
        if (_contextHandler!=null)
            _contextHandler.destroyServlet(servlet);
    }

    /* ------------------------------------------------------------ */
    void destroyFilter(Filter filter)
    {
        if (_contextHandler!=null)
            _contextHandler.destroyFilter(filter);
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public static class Default404Servlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException
        {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }
}
