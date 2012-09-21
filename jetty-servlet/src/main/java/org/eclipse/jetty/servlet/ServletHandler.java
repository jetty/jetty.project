//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.ServletSecurityElement;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.ContinuationThrowable;
import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServletRequestHttpWrapper;
import org.eclipse.jetty.server.ServletResponseHttpWrapper;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ScopedHandler;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* --------------------------------------------------------------------- */
/** Servlet HttpHandler.
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
    private ContextHandler.Context _servletContext;
    private FilterHolder[] _filters=new FilterHolder[0];
    private FilterMapping[] _filterMappings;
    private boolean _filterChainsCached=true;
    private int _maxFilterChainsCacheSize=512;
    private boolean _startWithUnavailable=true;
    private IdentityService _identityService;

    private ServletHolder[] _servlets=new ServletHolder[0];
    private ServletMapping[] _servletMappings;

    private final Map<String,FilterHolder> _filterNameMap= new HashMap<>();
    private List<FilterMapping> _filterPathMappings;
    private MultiMap<FilterMapping> _filterNameMappings;

    private final Map<String,ServletHolder> _servletNameMap=new HashMap<>();
    private PathMap _servletPathMap;

    protected final ConcurrentMap _chainCache[] = new ConcurrentMap[FilterMapping.ALL];
    protected final Queue[] _chainLRU = new Queue[FilterMapping.ALL];


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
        _servletContext=ContextHandler.getCurrentContext();
        _contextHandler=(ServletContextHandler)(_servletContext==null?null:_servletContext.getContextHandler());

        if (_contextHandler!=null)
        {
            SecurityHandler security_handler = _contextHandler.getChildHandlerByClass(SecurityHandler.class);
            if (security_handler!=null)
                _identityService=security_handler.getIdentityService();
        }

        updateNameMappings();
        updateMappings();

        if(_filterChainsCached)
        {
            _chainCache[FilterMapping.REQUEST]=new ConcurrentHashMap<String,FilterChain>();
            _chainCache[FilterMapping.FORWARD]=new ConcurrentHashMap<String,FilterChain>();
            _chainCache[FilterMapping.INCLUDE]=new ConcurrentHashMap<String,FilterChain>();
            _chainCache[FilterMapping.ERROR]=new ConcurrentHashMap<String,FilterChain>();
            _chainCache[FilterMapping.ASYNC]=new ConcurrentHashMap<String,FilterChain>();

            _chainLRU[FilterMapping.REQUEST]=new ConcurrentLinkedQueue<String>();
            _chainLRU[FilterMapping.FORWARD]=new ConcurrentLinkedQueue<String>();
            _chainLRU[FilterMapping.INCLUDE]=new ConcurrentLinkedQueue<String>();
            _chainLRU[FilterMapping.ERROR]=new ConcurrentLinkedQueue<String>();
            _chainLRU[FilterMapping.ASYNC]=new ConcurrentLinkedQueue<String>();
        }

        if (_contextHandler==null)
            initialize();
        
        super.doStart();
    }

    /* ----------------------------------------------------------------- */
    @Override
    protected synchronized void doStop()
        throws Exception
    {
        super.doStop();

        // Stop filters
        if (_filters!=null)
        {
            for (int i=_filters.length; i-->0;)
            {
                try { _filters[i].stop(); }catch(Exception e){LOG.warn(Log.EXCEPTION,e);}
            }
        }

        // Stop servlets
        if (_servlets!=null)
        {
            for (int i=_servlets.length; i-->0;)
            {
                try { _servlets[i].stop(); }catch(Exception e){LOG.warn(Log.EXCEPTION,e);}
            }
        }

        _filterPathMappings=null;
        _filterNameMappings=null;

        _servletPathMap=null;
    }

    /* ------------------------------------------------------------ */
    IdentityService getIdentityService()
    {
        return _identityService;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the contextLog.
     */
    public Object getContextLog()
    {
        return null;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the filterMappings.
     */
    @ManagedAttribute(value="filters", readonly=true)
    public FilterMapping[] getFilterMappings()
    {
        return _filterMappings;
    }

    /* ------------------------------------------------------------ */
    /** Get Filters.
     * @return Array of defined servlets
     */
    @ManagedAttribute(value="filters", readonly=true)
    public FilterHolder[] getFilters()
    {
        return _filters;
    }

    /* ------------------------------------------------------------ */
    /** ServletHolder matching path.
     * @param pathInContext Path within _context.
     * @return PathMap Entries pathspec to ServletHolder
     */
    public PathMap.MappedEntry getHolderEntry(String pathInContext)
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
    /**
     * @return Returns the servletMappings.
     */
    @ManagedAttribute(value="mappings of servlets", readonly=true)
    public ServletMapping[] getServletMappings()
    {
        return _servletMappings;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the servletMappings.
     */
    public ServletMapping getServletMapping(String pattern)
    {
        ServletMapping theMapping = null;
        if (_servletMappings!=null)
        {
            for (ServletMapping m:_servletMappings)
            {
                String[] paths=m.getPathSpecs();
                if (paths!=null)
                {
                    for (String path:paths)
                    {
                        if (pattern.equals(path))
                            theMapping = m;
                    }
                }
            }
        }
        return theMapping;
    }

    /* ------------------------------------------------------------ */
    /** Get Servlets.
     * @return Array of defined servlets
     */
    @ManagedAttribute(value="servlets", readonly=true)
    public ServletHolder[] getServlets()
    {
        return _servlets;
    }

    /* ------------------------------------------------------------ */
    public ServletHolder getServlet(String name)
    {
        return _servletNameMap.get(name);
    }

    /* ------------------------------------------------------------ */
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
            PathMap.MappedEntry entry=getHolderEntry(target);
            if (entry!=null)
            {
                servlet_holder=(ServletHolder)entry.getValue();

                String servlet_path_spec= entry.getKey();
                String servlet_path=entry.getMapped()!=null?entry.getMapped():PathMap.pathMatch(servlet_path_spec,target);
                String path_info=PathMap.pathInfo(servlet_path_spec,target);

                if (DispatcherType.INCLUDE.equals(type))
                {
                    baseRequest.setAttribute(Dispatcher.INCLUDE_SERVLET_PATH,servlet_path);
                    baseRequest.setAttribute(Dispatcher.INCLUDE_PATH_INFO, path_info);
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

            // start manual inline of nextScope(target,baseRequest,request,response);
            if (never())
                nextScope(target,baseRequest,request,response);
            else if (_nextScope!=null)
                _nextScope.doScope(target,baseRequest,request, response);
            else if (_outerScope!=null)
                _outerScope.doHandle(target,baseRequest,request, response);
            else
                doHandle(target,baseRequest,request, response);
            // end manual inline (pathentic attempt to reduce stack depth)
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

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.Handler#handle(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, int)
     */
    @Override
    public void doHandle(String target, Request baseRequest,HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        DispatcherType type = baseRequest.getDispatcherType();

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

        LOG.debug("chain={}",chain);

        try
        {
            if (servlet_holder==null)
            {
                if (getHandler()==null)
                    notFound(request, response);
                else
                    nextHandle(target,baseRequest,request,response);
            }
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
                if (chain!=null)
                    chain.doFilter(req, res);
                else
                    servlet_holder.handle(baseRequest,req,res);
            }
        }
        catch(EofException e)
        {
            throw e;
        }
        catch(RuntimeIOException e)
        {
            throw e;
        }
        catch(ContinuationThrowable e)
        {
            throw e;
        }
        catch(Exception e)
        {
            if (!(DispatcherType.REQUEST.equals(type) || DispatcherType.ASYNC.equals(type)))
            {
                if (e instanceof IOException)
                    throw (IOException)e;
                if (e instanceof RuntimeException)
                    throw (RuntimeException)e;
                if (e instanceof ServletException)
                    throw (ServletException)e;
            }

            // unwrap cause
            Throwable th=e;
            if (th instanceof UnavailableException)
            {
                LOG.debug(th);
            }
            else if (th instanceof ServletException)
            {
                LOG.debug(th);
                Throwable cause=((ServletException)th).getRootCause();
                if (cause!=null)
                    th=cause;
            }
            else if (th instanceof RuntimeIOException)
            {
                LOG.debug(th);
                Throwable cause= th.getCause();
                if (cause!=null)
                    th=cause;
            }

            // handle or log exception
            else if (th instanceof RuntimeIOException)
                throw (RuntimeIOException)th;
            else if (th instanceof EofException)
                throw (EofException)th;

            else if (LOG.isDebugEnabled())
            {
                LOG.warn(request.getRequestURI(), th);
                LOG.debug(request.toString());
            }
            else if (th instanceof IOException || th instanceof UnavailableException)
            {
                LOG.debug(request.getRequestURI(),th);
            }
            else
            {
                LOG.warn(request.getRequestURI(),th);
            }

            if (!response.isCommitted())
            {
                request.setAttribute(Dispatcher.ERROR_EXCEPTION_TYPE,th.getClass());
                request.setAttribute(Dispatcher.ERROR_EXCEPTION,th);
                if (th instanceof UnavailableException)
                {
                    UnavailableException ue = (UnavailableException)th;
                    if (ue.isPermanent())
                        response.sendError(HttpServletResponse.SC_NOT_FOUND,th.getMessage());
                    else
                        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,th.getMessage());
                }
                else
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,th.getMessage());
            }
            else
                LOG.debug("Response already committed for handling "+th);
        }
        catch(Error e)
        {
            if (!(DispatcherType.REQUEST.equals(type) || DispatcherType.ASYNC.equals(type)))
                throw e;
            LOG.warn("Error for "+request.getRequestURI(),e);
            if(LOG.isDebugEnabled())LOG.debug(request.toString());

            // TODO httpResponse.getHttpConnection().forceClose();
            if (!response.isCommitted())
            {
                request.setAttribute(Dispatcher.ERROR_EXCEPTION_TYPE,e.getClass());
                request.setAttribute(Dispatcher.ERROR_EXCEPTION,e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,e.getMessage());
            }
            else
                LOG.debug("Response already committed for handling ",e);
        }
        finally
        {
            if (servlet_holder!=null)
                baseRequest.setHandled(true);
        }
    }

    /* ------------------------------------------------------------ */
    private FilterChain getFilterChain(Request baseRequest, String pathInContext, ServletHolder servletHolder)
    {
        String key=pathInContext==null?servletHolder.getName():pathInContext;
        int dispatch = FilterMapping.dispatch(baseRequest.getDispatcherType());

        if (_filterChainsCached && _chainCache!=null)
        {
            FilterChain chain = (FilterChain)_chainCache[dispatch].get(key);
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
        if (servletHolder != null && _filterNameMappings!=null && _filterNameMappings.size() > 0)
        {
            // Servlet name filters
            if (_filterNameMappings.size() > 0)
            {
                Object o= _filterNameMappings.get(servletHolder.getName());

                for (int i=0; i<LazyList.size(o);i++)
                {
                    FilterMapping mapping = (FilterMapping)LazyList.get(o,i);
                    if (mapping.appliesTo(dispatch))
                        filters.add(mapping.getFilterHolder());
                }

                o= _filterNameMappings.get("*");
                for (int i=0; i<LazyList.size(o);i++)
                {
                    FilterMapping mapping = (FilterMapping)LazyList.get(o,i);
                    if (mapping.appliesTo(dispatch))
                        filters.add(mapping.getFilterHolder());
                }
            }
        }

        if (filters.isEmpty())
            return null;


        FilterChain chain = null;
        if (_filterChainsCached)
        {
            if (filters.size() > 0)
                chain= new CachedChain(filters, servletHolder);

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
    private void invalidateChainsCache()
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
     * Called automatically from start if autoInitializeServlet is true.
     */
    public void initialize()
        throws Exception
    {
        MultiException mx = new MultiException();

        if (_servlets!=null)
        {
            // Sort and Initialize servlets
            ServletHolder[] servlets = _servlets.clone();
            Arrays.sort(servlets);
            for (ServletHolder servlet : servlets)
            {
                try
                {
                    if (servlet.getClassName() == null && servlet.getForcedPath() != null)
                    {
                        ServletHolder forced_holder = (ServletHolder)_servletPathMap.match(servlet.getForcedPath());
                        if (forced_holder == null || forced_holder.getClassName() == null)
                        {
                            mx.add(new IllegalStateException("No forced path servlet for " + servlet.getForcedPath()));
                            continue;
                        }
                        servlet.setClassName(forced_holder.getClassName());
                    }
                }
                catch (Throwable e)
                {
                    LOG.debug(Log.EXCEPTION, e);
                    mx.add(e);
                }
            }
            mx.ifExceptionThrow();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the filterChainsCached.
     */
    public boolean isFilterChainsCached()
    {
        return _filterChainsCached;
    }

    /* ------------------------------------------------------------ */
    /**
     * see also newServletHolder(Class)
     */
    public ServletHolder newServletHolder(Holder.Source source)
    {
        return new ServletHolder(source);
    }

    /* ------------------------------------------------------------ */
    /** Convenience method to add a servlet Holder.
    public ServletHolder newServletHolder(Class<? extends Servlet> servlet)
    {
        return new ServletHolder(servlet);
    }

    /* ------------------------------------------------------------ */
    /** Convenience method to add a servlet.
     * @return The servlet holder.
     */
    public ServletHolder addServletWithMapping (String className,String pathSpec)
    {
        ServletHolder holder = newServletHolder(null);
        holder.setName(className+"-"+_servlets.length);
        holder.setClassName(className);
        addServletWithMapping(holder,pathSpec);
        return holder;
    }

    /* ------------------------------------------------------------ */
    /** conveniance method to add a servlet.
     * @return The servlet holder.
     */
    public ServletHolder addServletWithMapping (Class<? extends Servlet> servlet,String pathSpec)
    {
        ServletHolder holder = newServletHolder(Holder.Source.EMBEDDED);
        holder.setHeldClass(servlet);
        setServlets(ArrayUtil.addToArray(getServlets(), holder, ServletHolder.class));
        addServletWithMapping(holder,pathSpec);

        return holder;
    }

    /* ------------------------------------------------------------ */
    /** conveniance method to add a servlet.
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
            setServlets(ArrayUtil.addToArray(holders, servlet, ServletHolder.class));

            ServletMapping mapping = new ServletMapping();
            mapping.setServletName(servlet.getName());
            mapping.setPathSpec(pathSpec);
            setServletMappings(ArrayUtil.addToArray(getServletMappings(), mapping, ServletMapping.class));
        }
        catch (Exception e)
        {
            setServlets(holders);
            if (e instanceof RuntimeException)
                throw (RuntimeException)e;
            throw new RuntimeException(e);
        }
    }


    /* ------------------------------------------------------------ */
    /**Convenience method to add a pre-constructed ServletHolder.
     * @param holder
     */
    public void addServlet(ServletHolder holder)
    {
        setServlets(ArrayUtil.addToArray(getServlets(), holder, ServletHolder.class));
    }

    /* ------------------------------------------------------------ */
    /** Convenience method to add a pre-constructed ServletMapping.
     * @param mapping
     */
    public void addServletMapping (ServletMapping mapping)
    {
        setServletMappings(ArrayUtil.addToArray(getServletMappings(), mapping, ServletMapping.class));
    }

    public Set<String>  setServletSecurity(ServletRegistration.Dynamic registration, ServletSecurityElement servletSecurityElement) {
        if (_contextHandler != null) {
            return _contextHandler.setServletSecurity(registration, servletSecurityElement);
        }
        return Collections.emptySet();
    }

    /* ------------------------------------------------------------ */
    public FilterHolder newFilterHolder(Holder.Source source)
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
        FilterHolder holder = newFilterHolder(Holder.Source.EMBEDDED);
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
        FilterHolder holder = newFilterHolder(Holder.Source.EMBEDDED);
        holder.setName(className+"-"+_filters.length);
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
            setFilters(ArrayUtil.addToArray(holders, holder, FilterHolder.class));

            FilterMapping mapping = new FilterMapping();
            mapping.setFilterName(holder.getName());
            mapping.setPathSpec(pathSpec);
            mapping.setDispatcherTypes(dispatches);
            setFilterMappings(ArrayUtil.addToArray(getFilterMappings(), mapping, FilterMapping.class));
        }
        catch (RuntimeException e)
        {
            setFilters(holders);
            throw e;
        }
        catch (Error e)
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
        FilterHolder holder = newFilterHolder(Holder.Source.EMBEDDED);
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
        FilterHolder holder = newFilterHolder(null);
        holder.setName(className+"-"+_filters.length);
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
            setFilters(ArrayUtil.addToArray(holders, holder, FilterHolder.class));

            FilterMapping mapping = new FilterMapping();
            mapping.setFilterName(holder.getName());
            mapping.setPathSpec(pathSpec);
            mapping.setDispatches(dispatches);
            setFilterMappings(ArrayUtil.addToArray(getFilterMappings(), mapping, FilterMapping.class));
        }
        catch (RuntimeException e)
        {
            setFilters(holders);
            throw e;
        }
        catch (Error e)
        {
            setFilters(holders);
            throw e;
        }

    }

    /* ------------------------------------------------------------ */
    /** Convenience method to add a filter with a mapping
     * @param className
     * @param pathSpec
     * @param dispatches
     * @return the filter holder created
     * @deprecated use {@link #addFilterWithMapping(Class, String, EnumSet)} instead
     */
    public FilterHolder addFilter (String className,String pathSpec,EnumSet<DispatcherType> dispatches)
    {
        return addFilterWithMapping(className, pathSpec, dispatches);
    }

    /* ------------------------------------------------------------ */
    /**
     * convenience method to add a filter and mapping
     * @param filter
     * @param filterMapping
     */
    public void addFilter (FilterHolder filter, FilterMapping filterMapping)
    {
        if (filter != null)
            setFilters(ArrayUtil.addToArray(getFilters(), filter, FilterHolder.class));
        if (filterMapping != null)
            setFilterMappings(ArrayUtil.addToArray(getFilterMappings(), filterMapping, FilterMapping.class));
    }

    /* ------------------------------------------------------------ */
    /** Convenience method to add a preconstructed FilterHolder
     * @param filter
     */
    public void addFilter (FilterHolder filter)
    {
        if (filter != null)
            setFilters(ArrayUtil.addToArray(getFilters(), filter, FilterHolder.class));
    }

    /* ------------------------------------------------------------ */
    /** Convenience method to add a preconstructed FilterMapping
     * @param mapping
     */
    public void addFilterMapping (FilterMapping mapping)
    {
        if (mapping != null)
            setFilterMappings(ArrayUtil.addToArray(getFilterMappings(), mapping, FilterMapping.class));
    }

    /* ------------------------------------------------------------ */
    /** Convenience method to add a preconstructed FilterMapping
     * @param mapping
     */
    public void prependFilterMapping (FilterMapping mapping)
    {
        if (mapping != null)
        {
            FilterMapping[] mappings =getFilterMappings();
            if (mappings==null || mappings.length==0)
                setFilterMappings(new FilterMapping[] {mapping});
            else
            {

                FilterMapping[] new_mappings=new FilterMapping[mappings.length+1];
                System.arraycopy(mappings,0,new_mappings,1,mappings.length);
                new_mappings[0]=mapping;
                setFilterMappings(new_mappings);
            }
        }
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
            _filterNameMappings=new MultiMap<FilterMapping>();
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
            PathMap<ServletHolder> pm = new PathMap<>();

            // update the maps
            for (ServletMapping servletmapping : _servletMappings)
            {
                ServletHolder servlet_holder = _servletNameMap.get(servletmapping.getServletName());
                if (servlet_holder == null)
                    throw new IllegalStateException("No such servlet: " + servletmapping.getServletName());
                else if (servlet_holder.isEnabled() && servletmapping.getPathSpecs() != null)
                {
                    String[] pathSpecs = servletmapping.getPathSpecs();
                    for (String pathSpec : pathSpecs)
                        if (pathSpec != null)
                            pm.put(pathSpec, servlet_holder);
                }
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
    protected void notFound(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        if(LOG.isDebugEnabled())
            LOG.debug("Not Found "+request.getRequestURI());
        //Override to send an error back, eg with: response.sendError(HttpServletResponse.SC_NOT_FOUND);
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
        updateMappings();
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
        updateMappings();
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
    private class CachedChain implements FilterChain
    {
        FilterHolder _filterHolder;
        CachedChain _next;
        ServletHolder _servletHolder;

        /* ------------------------------------------------------------ */
        /**
         * @param filters list of {@link FilterHolder} objects
         * @param servletHolder
         */
        CachedChain(List<FilterHolder> filters, ServletHolder servletHolder)
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
        public void doFilter(ServletRequest request, ServletResponse response)
            throws IOException, ServletException
        {
            final Request baseRequest=(request instanceof Request)?((Request)request):HttpChannel.getCurrentHttpChannel().getRequest();

            // pass to next filter
            if (_filterHolder!=null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("call filter " + _filterHolder);
                Filter filter= _filterHolder.getFilter();
                if (_filterHolder.isAsyncSupported())
                    filter.doFilter(request, response, _next);
                else
                {
                    final boolean suspendable=baseRequest.isAsyncSupported();
                    if (suspendable)
                    {
                        try
                        {
                            baseRequest.setAsyncSupported(false);
                            filter.doFilter(request, response, _next);
                        }
                        finally
                        {
                            baseRequest.setAsyncSupported(true);
                        }
                    }
                    else
                        filter.doFilter(request, response, _next);
                }
                return;
            }

            // Call servlet

            HttpServletRequest srequest = (HttpServletRequest)request;
            if (_servletHolder != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("call servlet " + _servletHolder);
                _servletHolder.handle(baseRequest,request, response);
            }
            else if (getHandler()==null)
                notFound(srequest, (HttpServletResponse)response);
            else
                nextHandle(URIUtil.addPaths(srequest.getServletPath(),srequest.getPathInfo()),
                           baseRequest,srequest,(HttpServletResponse)response);

        }

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
        Chain(Request baseRequest, List<FilterHolder> filters, ServletHolder servletHolder)
        {
            _baseRequest=baseRequest;
            _chain= filters;
            _servletHolder= servletHolder;
        }

        /* ------------------------------------------------------------ */
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

                if (holder.isAsyncSupported() || !_baseRequest.isAsyncSupported())
                {
                    filter.doFilter(request, response, this);
                }
                else
                {
                    try
                    {
                        _baseRequest.setAsyncSupported(false);
                        filter.doFilter(request, response, this);
                    }
                    finally
                    {
                        _baseRequest.setAsyncSupported(true);
                    }
                }

                return;
            }

            // Call servlet
            HttpServletRequest srequest = (HttpServletRequest)request;
            if (_servletHolder != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("call servlet " + _servletHolder);
                _servletHolder.handle(_baseRequest,request, response);
            }
            else if (getHandler()==null)
                notFound(srequest, (HttpServletResponse)response);
            else
            {
                Request baseRequest=(request instanceof Request)?((Request)request):HttpChannel.getCurrentHttpChannel().getRequest();
                nextHandle(URIUtil.addPaths(srequest.getServletPath(),srequest.getPathInfo()),
                           baseRequest,srequest,(HttpServletResponse)response);
            }
        }

        /* ------------------------------------------------------------ */
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
}
