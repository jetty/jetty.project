// ========================================================================
// Copyright (c) 1999-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.ContinuationThrowable;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.io.UpgradeConnectionException;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.DispatcherType;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ScopedHandler;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;

/* --------------------------------------------------------------------- */
/** Servlet HttpHandler.
 * This handler maps requests to servlets that implement the
 * javax.servlet.http.HttpServlet API.
 * <P>
 * This handler does not implement the full J2EE features and is intended to
 * be used when a full web application is not required.  Specifically filters
 * and request wrapping are not supported.
 * 
 * Unless run as part of a {@link ServletContextHandler} or derivative, the {@link #initialize()}
 * method must be called manually after start().
 * 
 * @see org.eclipse.jetty.webapp.WebAppContext
 * 
 */
public class ServletHandler extends ScopedHandler
{
    /* ------------------------------------------------------------ */
    public static final String __DEFAULT_SERVLET="default";
        
    /* ------------------------------------------------------------ */
    private ContextHandler _contextHandler;
    private ContextHandler.Context _servletContext;
    private FilterHolder[] _filters;
    private FilterMapping[] _filterMappings;
    private boolean _filterChainsCached=true;
    private int _maxFilterChainsCacheSize=1000;
    private boolean _startWithUnavailable=true;
    private IdentityService _identityService;
    
    private ServletHolder[] _servlets;
    private ServletMapping[] _servletMappings;
    
    private transient Map<String,FilterHolder> _filterNameMap= new HashMap<String,FilterHolder>();
    private transient List<FilterMapping> _filterPathMappings;
    private transient MultiMap<String> _filterNameMappings;
    
    private transient Map<String,ServletHolder> _servletNameMap=new HashMap();
    private transient PathMap _servletPathMap;
    
    protected transient ConcurrentHashMap _chainCache[];


    /* ------------------------------------------------------------ */
    /** Constructor. 
     */
    public ServletHandler()
    {
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.jetty.server.handler.AbstractHandler#setServer(org.eclipse.jetty.server.Server)
     */
    public void setServer(Server server)
    {
        if (getServer()!=null && getServer()!=server)
        {
            getServer().getContainer().update(this, _filters, null, "filter",true);
            getServer().getContainer().update(this, _filterMappings, null, "filterMapping",true);
            getServer().getContainer().update(this, _servlets, null, "servlet",true);
            getServer().getContainer().update(this, _servletMappings, null, "servletMapping",true);
        }
        if (server!=null && getServer()!=server)
        {
            server.getContainer().update(this, null, _filters, "filter",true);
            server.getContainer().update(this, null, _filterMappings, "filterMapping",true);
            server.getContainer().update(this, null, _servlets, "servlet",true);
            server.getContainer().update(this, null, _servletMappings, "servletMapping",true);
        }
        super.setServer(server);
    }

    /* ----------------------------------------------------------------- */
    protected synchronized void doStart()
        throws Exception
    {
        _servletContext=ContextHandler.getCurrentContext();
        _contextHandler=_servletContext==null?null:_servletContext.getContextHandler();

        if (_contextHandler!=null)
        {
            SecurityHandler security_handler = (SecurityHandler)_contextHandler.getChildHandlerByClass(SecurityHandler.class);
            if (security_handler!=null)
                _identityService=security_handler.getIdentityService();
        }
        
        updateNameMappings();
        updateMappings();
        
        if(_filterChainsCached)
            _chainCache= new ConcurrentHashMap[]{null,new ConcurrentHashMap(),new ConcurrentHashMap(),null,new ConcurrentHashMap(),null,null,null,new ConcurrentHashMap(),null,null,null,null,null,null,null,new ConcurrentHashMap()};

        super.doStart();
        
        if (_contextHandler==null || !(_contextHandler instanceof ServletContextHandler))
            initialize();
    }   
    
    /* ----------------------------------------------------------------- */
    protected synchronized void doStop()
        throws Exception
    {
        super.doStop();
        
        // Stop filters
        if (_filters!=null)
        {
            for (int i=_filters.length; i-->0;)
            {
                try { _filters[i].stop(); }catch(Exception e){Log.warn(Log.EXCEPTION,e);}
            }
        }
        
        // Stop servlets
        if (_servlets!=null)
        {
            for (int i=_servlets.length; i-->0;)
            {
                try { _servlets[i].stop(); }catch(Exception e){Log.warn(Log.EXCEPTION,e);}
            }
        }

        _filterPathMappings=null;
        _filterNameMappings=null;
        
        _servletPathMap=null;
        _chainCache=null;
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
    public FilterMapping[] getFilterMappings()
    {
        return _filterMappings;
    }
    
    /* ------------------------------------------------------------ */
    /** Get Filters.
     * @return Array of defined servlets
     */
    public FilterHolder[] getFilters()
    {
        return _filters;
    }
    
    /* ------------------------------------------------------------ */
    /** ServletHolder matching path.
     * @param pathInContext Path within _context.
     * @return PathMap Entries pathspec to ServletHolder
     */
    public PathMap.Entry getHolderEntry(String pathInContext)
    {
        if (_servletPathMap==null)
            return null;
        return _servletPathMap.getMatch(pathInContext);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param uriInContext uri to get dispatcher for
     * @return A {@link RequestDispatcher dispatcher} wrapping the resource at <code>uriInContext</code>,
     *  or <code>null</code> if the specified uri cannot be dispatched to.
     */
    public RequestDispatcher getRequestDispatcher(String uriInContext)
    {
        if (uriInContext == null)
            return null;

        if (!uriInContext.startsWith("/"))
            return null;
        
        try
        {
            String query=null;
            int q;
            if ((q=uriInContext.indexOf('?'))>0)
            {
                query=uriInContext.substring(q+1);
                uriInContext=uriInContext.substring(0,q);
            }
            if ((q=uriInContext.indexOf(';'))>0)
                uriInContext=uriInContext.substring(0,q);

            String pathInContext=URIUtil.canonicalPath(URIUtil.decodePath(uriInContext));
            String uri=URIUtil.addPaths(_contextHandler.getContextPath(), uriInContext);
            return new Dispatcher(_contextHandler, uri, pathInContext, query);
        }
        catch(Exception e)
        {
            Log.ignore(e);
        }
        return null;
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
    public ServletMapping[] getServletMappings()
    {
        return _servletMappings;
    }
        
    /* ------------------------------------------------------------ */
    /** Get Servlets.
     * @return Array of defined servlets
     */
    public ServletHolder[] getServlets()
    {
        return _servlets;
    }

    /* ------------------------------------------------------------ */
    public ServletHolder getServlet(String name)
    {
        return (ServletHolder)_servletNameMap.get(name);
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
            PathMap.Entry entry=getHolderEntry(target);
            if (entry!=null)
            {
                servlet_holder=(ServletHolder)entry.getValue();

                if(Log.isDebugEnabled())Log.debug("servlet="+servlet_holder);

                String servlet_path_spec=(String)entry.getKey(); 
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
            servlet_holder=(ServletHolder)_servletNameMap.get(target);
        }

        Log.debug("servlet holder=",servlet_holder);

        try
        {
            // Do the filter/handling thang
            if (servlet_holder!=null)
            {
                old_scope=baseRequest.getUserIdentityScope();
                baseRequest.setUserIdentityScope(servlet_holder);

                // start manual inline of nextScope(target,baseRequest,request,response);
                if (false)
                    nextScope(target,baseRequest,request,response);
                else if (_nextScope!=null)
                    _nextScope.doScope(target,baseRequest,request, response);
                else if (_outerScope!=null)
                    _outerScope.doHandle(target,baseRequest,request, response);
                else 
                    doHandle(target,baseRequest,request, response);
                // end manual inline (pathentic attempt to reduce stack depth)
            }
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

        Log.debug("chain=",chain);
        
        try
        {
            // Do the filter/handling thang
            if (servlet_holder==null)
            {
                notFound(request, response);
            }
            else
            {
                baseRequest.setHandled(true);

                if (chain!=null)
                    chain.doFilter(request, response);
                else 
                    servlet_holder.handle(baseRequest,request,response);
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
        catch(UpgradeConnectionException e)
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
                Log.debug(th); 
            }
            else if (th instanceof ServletException)
            {
                Log.debug(th);
                Throwable cause=((ServletException)th).getRootCause();
                if (cause!=null)
                    th=cause;
            }
            else if (th instanceof RuntimeIOException)
            {
                Log.debug(th);
                Throwable cause=(IOException)((RuntimeIOException)th).getCause();
                if (cause!=null)
                    th=cause;
            }

            // handle or log exception
            if (th instanceof HttpException)
                throw (HttpException)th;
            else if (th instanceof RuntimeIOException)
                throw (RuntimeIOException)th;
            else if (th instanceof EofException)
                throw (EofException)th;

            else if (Log.isDebugEnabled())
            {
                Log.warn(request.getRequestURI(), th); 
                Log.debug(request.toString()); 
            }
            else if (th instanceof IOException || th instanceof UnavailableException)
            {
                Log.debug(request.getRequestURI(),th);
            }
            else
            {
                Log.warn(request.getRequestURI(),th);
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
                Log.debug("Response already committed for handling "+th);
        }
        catch(Error e)
        {   
            if (!(DispatcherType.REQUEST.equals(type) || DispatcherType.ASYNC.equals(type)))
                throw e;
            Log.warn("Error for "+request.getRequestURI(),e);
            if(Log.isDebugEnabled())Log.debug(request.toString());

            // TODO httpResponse.getHttpConnection().forceClose();
            if (!response.isCommitted())
            {
                request.setAttribute(Dispatcher.ERROR_EXCEPTION_TYPE,e.getClass());
                request.setAttribute(Dispatcher.ERROR_EXCEPTION,e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,e.getMessage());
            }
            else
                Log.debug("Response already committed for handling ",e);
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
        
        // Build list of filters
        Object filters= null;
        // Path filters
        if (pathInContext!=null && _filterPathMappings!=null)
        {
            for (int i= 0; i < _filterPathMappings.size(); i++)
            {
                FilterMapping mapping = (FilterMapping)_filterPathMappings.get(i);
                if (mapping.appliesTo(pathInContext, dispatch))
                    filters= LazyList.add(filters, mapping.getFilterHolder());
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
                        filters=LazyList.add(filters,mapping.getFilterHolder());
                }
                
                o= _filterNameMappings.get("*");
                for (int i=0; i<LazyList.size(o);i++)
                {
                    FilterMapping mapping = (FilterMapping)LazyList.get(o,i);
                    if (mapping.appliesTo(dispatch))
                        filters=LazyList.add(filters,mapping.getFilterHolder());
                }
            }
        }
        
        if (filters==null)
            return null;
        
        FilterChain chain = null;
        if (_filterChainsCached)
        {
        	if (LazyList.size(filters) > 0)
        		chain= new CachedChain(filters, servletHolder);
        	if (_maxFilterChainsCacheSize>0 && _chainCache[dispatch].size()>_maxFilterChainsCacheSize)
        		_chainCache[dispatch].clear();
        	_chainCache[dispatch].put(key,chain);
        }
        else if (LazyList.size(filters) > 0)
            chain = new Chain(baseRequest,filters, servletHolder);
    
        return chain;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the initializeAtStart.
     * @deprecated
     */
    public boolean isInitializeAtStart()
    {
        return false;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param initializeAtStart The initializeAtStart to set.
     * @deprecated
     */
    public void setInitializeAtStart(boolean initializeAtStart)
    {
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
        for (int i=0;i<holders.length;i++)
        {
            ServletHolder holder = holders[i];
            if (holder!=null && !holder.isAvailable())
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

        // Start filters
        if (_filters!=null)
        {
            for (int i=0;i<_filters.length; i++)
                _filters[i].start();
        }
        
        if (_servlets!=null)
        {
            // Sort and Initialize servlets
            ServletHolder[] servlets = (ServletHolder[])_servlets.clone();
            Arrays.sort(servlets);
            for (int i=0; i<servlets.length; i++)
            {
                try
                {
                    if (servlets[i].getClassName()==null && servlets[i].getForcedPath()!=null)
                    {
                        ServletHolder forced_holder = (ServletHolder)_servletPathMap.match(servlets[i].getForcedPath());
                        if (forced_holder==null || forced_holder.getClassName()==null)
                        {    
                            mx.add(new IllegalStateException("No forced path servlet for "+servlets[i].getForcedPath()));
                            continue;
                        }
                        servlets[i].setClassName(forced_holder.getClassName());
                    }
                    
                    servlets[i].start();
                }
                catch(Throwable e)
                {
                    Log.debug(Log.EXCEPTION,e);
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
    public ServletHolder newServletHolder()
    {
        return new ServletHolder();
    }
    
    /* ------------------------------------------------------------ */
    public ServletHolder newServletHolder(Class servlet)
    {
        return new ServletHolder(servlet);
    }
    
    /* ------------------------------------------------------------ */
    /** conveniance method to add a servlet.
     * @return The servlet holder.
     */
    public ServletHolder addServletWithMapping (String className,String pathSpec)
    {
        ServletHolder holder = newServletHolder(null);
        holder.setName(className+"-"+holder.hashCode());
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
        ServletHolder holder = newServletHolder(servlet);
        setServlets((ServletHolder[])LazyList.addToArray(getServlets(), holder, ServletHolder.class));
        
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
            setServlets((ServletHolder[])LazyList.addToArray(holders, servlet, ServletHolder.class));
            
            ServletMapping mapping = new ServletMapping();
            mapping.setServletName(servlet.getName());
            mapping.setPathSpec(pathSpec);
            setServletMappings((ServletMapping[])LazyList.addToArray(getServletMappings(), mapping, ServletMapping.class));
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
    /** Convenience method to add a servlet with a servlet mapping.
     * @param className
     * @param pathSpec
     * @return
     * @deprecated
     */
    public ServletHolder addServlet (String className, String pathSpec)
    {
        return addServletWithMapping (className, pathSpec);
    }

    
    /* ------------------------------------------------------------ */    
    /**Convenience method to add a pre-constructed ServletHolder.
     * @param holder
     */
    public void addServlet(ServletHolder holder)
    {
        setServlets((ServletHolder[])LazyList.addToArray(getServlets(), holder, ServletHolder.class));
    }
    
    /* ------------------------------------------------------------ */    
    /** Convenience method to add a pre-constructed ServletMapping.
     * @param mapping
     */
    public void addServletMapping (ServletMapping mapping)
    {
        setServletMappings((ServletMapping[])LazyList.addToArray(getServletMappings(), mapping, ServletMapping.class));
    }
    
    /* ------------------------------------------------------------ */
    public FilterHolder newFilterHolder(Class<? extends Filter> filter)
    {
        return new FilterHolder(filter);
    }
    
    /* ------------------------------------------------------------ */
    /** 
     * @see {@link #newFilterHolder(Class)}
     */
    public FilterHolder newFilterHolder()
    {
        return new FilterHolder();
    }

    /* ------------------------------------------------------------ */
    public FilterHolder getFilter(String name)
    {
        return (FilterHolder)_filterNameMap.get(name);
    }
    
    /* ------------------------------------------------------------ */
    /** conveniance method to add a filter.
     * @param filter  class of filter to create
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     * @return The filter holder.
     */
    public FilterHolder addFilterWithMapping (Class<? extends Filter> filter,String pathSpec,int dispatches)
    {
        FilterHolder holder = newFilterHolder(filter);
        addFilterWithMapping(holder,pathSpec,dispatches);
        
        return holder;
    }
    
    /* ------------------------------------------------------------ */
    /** conveniance method to add a filter.
     * @param className of filter
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     * @return The filter holder.
     */
    public FilterHolder addFilterWithMapping (String className,String pathSpec,int dispatches)
    {
        FilterHolder holder = newFilterHolder(null);
        holder.setName(className+"-"+holder.hashCode());
        holder.setClassName(className);
        
        addFilterWithMapping(holder,pathSpec,dispatches);
        return holder;
    }
    
    /* ------------------------------------------------------------ */
    /** conveniance method to add a filter.
     * @param holder filter holder to add
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     */
    public void addFilterWithMapping (FilterHolder holder,String pathSpec,int dispatches)
    {
        FilterHolder[] holders = getFilters();
        if (holders!=null)
            holders = (FilterHolder[])holders.clone();
        
        try
        {
            setFilters((FilterHolder[])LazyList.addToArray(holders, holder, FilterHolder.class));
            
            FilterMapping mapping = new FilterMapping();
            mapping.setFilterName(holder.getName());
            mapping.setPathSpec(pathSpec);
            mapping.setDispatches(dispatches);
            setFilterMappings((FilterMapping[])LazyList.addToArray(getFilterMappings(), mapping, FilterMapping.class));
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
     * @return
     * @deprecated
     */
    public FilterHolder addFilter (String className,String pathSpec,int dispatches)
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
            setFilters((FilterHolder[])LazyList.addToArray(getFilters(), filter, FilterHolder.class));
        if (filterMapping != null)
            setFilterMappings((FilterMapping[])LazyList.addToArray(getFilterMappings(), filterMapping, FilterMapping.class));
    }
    
    /* ------------------------------------------------------------ */  
    /** Convenience method to add a preconstructed FilterHolder
     * @param filter
     */
    public void addFilter (FilterHolder filter)
    {
        if (filter != null)
            setFilters((FilterHolder[])LazyList.addToArray(getFilters(), filter, FilterHolder.class));
    }
    
    /* ------------------------------------------------------------ */
    /** Convenience method to add a preconstructed FilterMapping
     * @param mapping
     */
    public void addFilterMapping (FilterMapping mapping)
    {
        if (mapping != null)
            setFilterMappings((FilterMapping[])LazyList.addToArray(getFilterMappings(), mapping, FilterMapping.class));
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
            for (int i=0;i<_filters.length;i++)
            {
                _filterNameMap.put(_filters[i].getName(),_filters[i]);
                _filters[i].setServletHandler(this);
            }
        }

        // Map servlet names to holders
        _servletNameMap.clear();
        if (_servlets!=null)
        {   
            // update the maps
            for (int i=0;i<_servlets.length;i++)
            {
                _servletNameMap.put(_servlets[i].getName(),_servlets[i]);
                _servlets[i].setServletHandler(this);
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
            _filterPathMappings=new ArrayList();
            _filterNameMappings=new MultiMap();
            for (int i=0;i<_filterMappings.length;i++)
            {
                FilterHolder filter_holder = (FilterHolder)_filterNameMap.get(_filterMappings[i].getFilterName());
                if (filter_holder==null)
                    throw new IllegalStateException("No filter named "+_filterMappings[i].getFilterName());
                _filterMappings[i].setFilterHolder(filter_holder);    
                if (_filterMappings[i].getPathSpecs()!=null)
                    _filterPathMappings.add(_filterMappings[i]);
                
                if (_filterMappings[i].getServletNames()!=null)
                {
                    String[] names=_filterMappings[i].getServletNames();
                    for (int j=0;j<names.length;j++)
                    {
                        if (names[j]!=null)
                            _filterNameMappings.add(names[j], _filterMappings[i]);  
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
            PathMap pm = new PathMap();
            
            // update the maps
            for (int i=0;i<_servletMappings.length;i++)
            {
                ServletHolder servlet_holder = (ServletHolder)_servletNameMap.get(_servletMappings[i].getServletName());
                if (servlet_holder==null)
                    throw new IllegalStateException("No such servlet: "+_servletMappings[i].getServletName());
                else if (_servletMappings[i].getPathSpecs()!=null)
                {
                    String[] pathSpecs = _servletMappings[i].getPathSpecs();
                    for (int j=0;j<pathSpecs.length;j++)
                        if (pathSpecs[j]!=null)
                            pm.put(pathSpecs[j],servlet_holder);
                }
            }
            
            _servletPathMap=pm;
        }
        
        

        if (Log.isDebugEnabled()) 
        {
            Log.debug("filterNameMap="+_filterNameMap);
            Log.debug("pathFilters="+_filterPathMappings);
            Log.debug("servletFilterMap="+_filterNameMappings);
            Log.debug("servletPathMap="+_servletPathMap);
            Log.debug("servletNameMap="+_servletNameMap);
        }
        
        try
        {
            if (isStarted())
                initialize();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /* ------------------------------------------------------------ */
    protected void notFound(HttpServletRequest request,
                  HttpServletResponse response)
        throws IOException
    {
        if(Log.isDebugEnabled())Log.debug("Not Found "+request.getRequestURI());
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
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
        if (getServer()!=null)
            getServer().getContainer().update(this,_filterMappings,filterMappings,"filterMapping",true);
        _filterMappings = filterMappings;
        updateMappings();
    }
    
    /* ------------------------------------------------------------ */
    public synchronized void setFilters(FilterHolder[] holders)
    {
        if (getServer()!=null)
            getServer().getContainer().update(this,_filters,holders,"filter",true);
        _filters=holders;
        updateNameMappings();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param servletMappings The servletMappings to set.
     */
    public void setServletMappings(ServletMapping[] servletMappings)
    {
        if (getServer()!=null)
            getServer().getContainer().update(this,_servletMappings,servletMappings,"servletMapping",true);
        _servletMappings = servletMappings;
        updateMappings();
    }
    
    /* ------------------------------------------------------------ */
    /** Set Servlets.
     * @param holders Array of servletsto define
     */
    public synchronized void setServlets(ServletHolder[] holders)
    {
        if (getServer()!=null)
            getServer().getContainer().update(this,_servlets,holders,"servlet",true);
        _servlets=holders;
        updateNameMappings();
    }


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class CachedChain implements FilterChain
    {
        FilterHolder _filterHolder;
        CachedChain _next;
        ServletHolder _servletHolder;

        /* ------------------------------------------------------------ */
        CachedChain(Object filters, ServletHolder servletHolder)
        {
            if (LazyList.size(filters)>0)
            {
                _filterHolder=(FilterHolder)LazyList.get(filters, 0);
                filters=LazyList.remove(filters,0);
                _next=new CachedChain(filters,servletHolder);
            }
            else
                _servletHolder=servletHolder;
        }

        /* ------------------------------------------------------------ */
        public void doFilter(ServletRequest request, ServletResponse response) 
            throws IOException, ServletException
        {
            // pass to next filter
            if (_filterHolder!=null)
            {
                if (Log.isDebugEnabled())
                    Log.debug("call filter " + _filterHolder);
                Filter filter= _filterHolder.getFilter();
                if (_filterHolder.isAsyncSupported())
                    filter.doFilter(request, response, _next);
                else
                {
                    final Request baseRequest=(request instanceof Request)?((Request)request):HttpConnection.getCurrentConnection().getRequest();
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
            if (_servletHolder != null)
            {
                if (Log.isDebugEnabled())
                    Log.debug("call servlet " + _servletHolder);
                final Request baseRequest=(request instanceof Request)?((Request)request):HttpConnection.getCurrentConnection().getRequest();
                _servletHolder.handle(baseRequest,request, response);
            }
            else // Not found
                notFound((HttpServletRequest)request, (HttpServletResponse)response);
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
        final Object _chain;
        final ServletHolder _servletHolder;
        int _filter= 0;

        /* ------------------------------------------------------------ */
        Chain(Request baseRequest, Object filters, ServletHolder servletHolder)
        {
            _baseRequest=baseRequest;
            _chain= filters;
            _servletHolder= servletHolder;
        }

        /* ------------------------------------------------------------ */
        public void doFilter(ServletRequest request, ServletResponse response)
            throws IOException, ServletException
        {
            if (Log.isDebugEnabled()) Log.debug("doFilter " + _filter);

            // pass to next filter
            if (_filter < LazyList.size(_chain))
            {
                FilterHolder holder= (FilterHolder)LazyList.get(_chain, _filter++);
                if (Log.isDebugEnabled()) Log.debug("call filter " + holder);
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
            if (_servletHolder != null)
            {
                if (Log.isDebugEnabled()) Log.debug("call servlet " + _servletHolder);
                _servletHolder.handle(_baseRequest,request, response);
            }
            else // Not found
                notFound((HttpServletRequest)request, (HttpServletResponse)response);
        }

        /* ------------------------------------------------------------ */
        public String toString()
        {
            StringBuilder b = new StringBuilder();
            for (int i=0; i<LazyList.size(_chain);i++)
            {
                Object o=LazyList.get(_chain, i);
                b.append(o.toString());
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
    
    /**
     * Customize a servlet.
     * 
     * Called before the servlet goes into service.
     * Subclasses of ServletHandler should override
     * this method.
     * 
     * @param servlet
     * @return
     * @throws Exception
     */
    public Servlet customizeServlet (Servlet servlet)
    throws Exception
    {
        return servlet;
    }
    
    
    public Servlet customizeServletDestroy (Servlet servlet)
    throws Exception
    {
        return servlet;
    }
    
    
    /**
     * Customize a Filter.
     * 
     * Called before the Filter goes into service.
     * Subclasses of ServletHandler should override
     * this method.
     * 
     * @param filter
     * @return
     * @throws Exception
     */
    public Filter customizeFilter (Filter filter)
    throws Exception
    {
        return filter;
    }
    
    
    public Filter customizeFilterDestroy (Filter filter)
    throws Exception
    {
        return filter;
    }
    

    
    /* ------------------------------------------------------------ */
    protected void dump(StringBuilder b,String indent)
    {
        super.dump(b,indent);

        if (getFilterMappings()!=null)
        {
            for (FilterMapping f : getFilterMappings())
            {
                b.append(indent);
                b.append(" +-");
                b.append(f);
                b.append('\n');
            }
        }
        HashSet<String> servlets = new HashSet<String>();
        if (getServletMappings()!=null)
        {
            for (ServletMapping m : getServletMappings())
            {
                servlets.add(m.getServletName());
                b.append(indent);
                b.append(" +-");
                b.append(m);
                b.append('\n');
            }
        }

        if (getServlets()!=null)
        {
            for (ServletHolder h : getServlets())
            {
                if (servlets.contains(h.getName()))
                    continue;
                b.append(indent);
                b.append(" +-[]==>");
                b.append(h.getName());
                b.append('\n');
            }
        }

    }

    
}
