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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.security.IdentityService;
import org.eclipse.jetty.ee10.servlet.security.SecurityHandler;
import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.MatchedPath;
import org.eclipse.jetty.http.pathmap.MatchedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Servlet HttpHandler.
 * <p>
 * This handler maps requests to servlets that implement the
 * jakarta.servlet.http.HttpServlet API.
 * <P>
 * This handler does not implement the full J2EE features and is intended to
 * be used directly when a full web application is not required.  If a Web application is required,
 * then this handler should be used as part of a <code>org.eclipse.jetty.webapp.WebAppContext</code>.
 * <p>
 * Unless run as part of a {@link ServletContextHandler} or derivative, the {@link #initialize()}
 * method must be called manually after start().
 */
@ManagedObject("Servlet Handler")
public class ServletHandler extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(ServletHandler.class);

    private final AutoLock _lock = new AutoLock();
    private ServletContextHandler _servletContextHandler;
    private IdentityService _identityService;
    private ServletContext _servletContext;
    private final List<FilterHolder> _filters = new ArrayList<>();
    private final List<FilterMapping> _filterMappings = new ArrayList<>();
    private int _matchBeforeIndex = -1; //index of last programmatic FilterMapping with isMatchAfter=false
    private int _matchAfterIndex = -1;  //index of 1st programmatic FilterMapping with isMatchAfter=true
    private boolean _filterChainsCached = true;
    private int _maxFilterChainsCacheSize = 1024;
    private boolean _startWithUnavailable = false;
    private boolean _ensureDefaultServlet = true;
    private boolean _allowDuplicateMappings = false;

    private final List<ServletHolder> _servlets = new ArrayList<>();
    private final List<ServletMapping> _servletMappings = new ArrayList<>();
    private final Map<String, FilterHolder> _filterNameMap = new HashMap<>();
    private List<FilterMapping> _filterPathMappings;
    private MultiMap<FilterMapping> _filterNameMappings;
    private List<FilterMapping> _wildFilterNameMappings;
    private final List<BaseHolder<?>> _durable = new ArrayList<>();

    private final Map<String, MappedServlet> _servletNameMap = new HashMap<>();
    private PathMappings<MappedServlet> _servletPathMap;

    private final List<ListenerHolder> _listeners = new ArrayList<>();
    private boolean _initialized = false;

    @SuppressWarnings("unchecked")
    protected final ConcurrentMap<String, FilterChain>[] _chainCache = new ConcurrentMap[FilterMapping.ALL];

    /**
     * Constructor.
     */
    public ServletHandler()
    {
    }

    AutoLock lock()
    {
        return _lock.lock();
    }

    private <T> void updateAndSet(java.util.Collection<T> target, java.util.Collection<T> values)
    {
        updateBeans(target, values);
        target.clear();
        target.addAll(values);
    }

    @Override
    public boolean isDumpable(Object o)
    {
        return !(o instanceof BaseHolder || o instanceof FilterMapping || o instanceof ServletMapping);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent,
            DumpableCollection.from("listeners " + this, _listeners),
            DumpableCollection.from("filters " + this, _filters),
            DumpableCollection.from("filterMappings " + this, _filterMappings),
            DumpableCollection.from("servlets " + this, _servlets),
            DumpableCollection.from("servletMappings " + this, _servletMappings),
            DumpableCollection.from("durable " + this, _durable));
    }

    @Override
    protected void doStart() throws Exception
    {
        try (AutoLock ignored = lock())
        {
            Context context = ContextHandler.getCurrentContext();
            if (!(context instanceof ServletContextHandler.ServletScopedContext))
                throw new IllegalStateException("Cannot use ServletHandler without ServletContextHandler");
            _servletContext = ((ServletContextHandler.ServletScopedContext)context).getServletContext();
            _servletContextHandler = ((ServletContextHandler.ServletScopedContext)context).getServletContextHandler();

            if (_servletContextHandler != null)
            {
                SecurityHandler securityHandler = _servletContextHandler.getDescendant(SecurityHandler.class);
                if (securityHandler != null)
                    _identityService = securityHandler.getIdentityService();
            }

            _durable.clear();
            _durable.addAll(Arrays.asList(getFilters()));
            _durable.addAll(Arrays.asList(getServlets()));
            _durable.addAll(Arrays.asList(getListeners()));

            updateNameMappings();
            updateMappings();

            if (getServletMapping("/") == null && isEnsureDefaultServlet())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Adding Default404Servlet to {}", this);
                addServletWithMapping(Default404Servlet.class, "/");
                updateMappings();
                getServletMapping("/").setFromDefaultDescriptor(true);
            }

            if (isFilterChainsCached())
            {
                _chainCache[FilterMapping.REQUEST] = new ConcurrentHashMap<>();
                _chainCache[FilterMapping.FORWARD] = new ConcurrentHashMap<>();
                _chainCache[FilterMapping.INCLUDE] = new ConcurrentHashMap<>();
                _chainCache[FilterMapping.ERROR] = new ConcurrentHashMap<>();
                _chainCache[FilterMapping.ASYNC] = new ConcurrentHashMap<>();
            }

            if (_servletContextHandler == null)
                initialize();

            super.doStart();
        }
    }

    /**
     * @return true if ServletHandler always has a default servlet, using {@link Default404Servlet} if no other
     * default servlet is configured.
     */
    public boolean isEnsureDefaultServlet()
    {
        return _ensureDefaultServlet;
    }

    /**
     * @param ensureDefaultServlet true if ServletHandler always has a default servlet, using {@link Default404Servlet} if no other
     * default servlet is configured.
     */
    public void setEnsureDefaultServlet(boolean ensureDefaultServlet)
    {
        _ensureDefaultServlet = ensureDefaultServlet;
    }

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
    
    @Override
    protected void stop(LifeCycle l) throws Exception
    {
        if (!(l instanceof Holder))
            super.stop(l);
    }

    @Override
    protected void doStop() throws Exception
    {
        try (AutoLock ignored = lock())
        {
            super.doStop();

            // Stop filters
            List<FilterHolder> filterHolders = new ArrayList<>();
            for (int i = _filters.size(); i-- > 0; )
            {
                FilterHolder filter = _filters.get(i);
                try
                {
                    filter.stop();
                }
                catch (Exception e)
                {
                    LOG.warn("Unable to stop filter {}", filter, e);
                }
                if (_durable.contains(filter))
                {
                    filterHolders.add(filter); //only retain durable
                }
            }

            //Retain only durable filters
            updateBeans(_filters, filterHolders);
            _filters.clear();
            _filters.addAll(filterHolders);

            // Stop servlets
            List<ServletHolder> servletHolders = new ArrayList<>();  //will be remaining servlets
            for (int i = _servlets.size(); i-- > 0; )
            {
                ServletHolder servlet = _servlets.get(i);
                try
                {
                    servlet.stop();
                }
                catch (Exception e)
                {
                    LOG.warn("Unable to stop servlet {}", servlet, e);
                }

                if (_durable.contains(servlet))
                {
                    servletHolders.add(servlet); //only retain embedded
                }
            }

            //Retain only durable Servlets
            updateBeans(_servlets, servletHolders);
            _servlets.clear();
            _servlets.addAll(servletHolders);

            updateNameMappings();
            updateAndSet(_servletMappings, _servletMappings.stream().filter(m -> _servletNameMap.containsKey(m.getServletName())).collect(Collectors.toList()));
            updateAndSet(_filterMappings, _filterMappings.stream().filter(m -> _filterNameMap.containsKey(m.getFilterName())).collect(Collectors.toList()));
            updateMappings();
            
            //The listeners must be called before the listener list changes
            if (_servletContextHandler != null)
                _servletContextHandler.contextDestroyed();
            
            //Retain only Listeners added via jetty apis (is Source.EMBEDDED)
            List<ListenerHolder> listenerHolders = new ArrayList<>();
            for (int i = _listeners.size(); i-- > 0; )
            {
                ListenerHolder listener = _listeners.get(i);
                try
                {
                    listener.stop();
                }
                catch (Exception e)
                {
                    LOG.warn("Unable to stop listener {}", listener, e);
                }
                if (_durable.contains(listener))
                    listenerHolders.add(listener);
            }

            updateBeans(_listeners, listenerHolders);
            _listeners.clear();
            _listeners.addAll(listenerHolders);

            // Update indexes for prepending filters
            _matchAfterIndex = (_filterMappings.size() == 0 ? -1 : _filterMappings.size() - 1);
            _matchBeforeIndex = -1;

            _durable.clear();
            _filterPathMappings = null;
            _filterNameMappings = null;
            _servletPathMap = null;
            _initialized = false;
        }
    }

    @ManagedAttribute(value = "filters", readonly = true)
    public FilterMapping[] getFilterMappings()
    {
        return _filterMappings.toArray(new FilterMapping[0]);
    }

    @ManagedAttribute(value = "filters", readonly = true)
    public FilterHolder[] getFilters()
    {
        return _filters.toArray(new FilterHolder[0]);
    }

    public ServletContext getServletContext()
    {
        return _servletContext;
    }

    public ServletContextHandler getServletContextHandler()
    {
        return _servletContextHandler;
    }

    @ManagedAttribute(value = "mappings of servlets", readonly = true)
    public ServletMapping[] getServletMappings()
    {
        return _servletMappings.toArray(new ServletMapping[0]);
    }

    /**
     * Get the ServletMapping matching the path
     *
     * @param pathSpec the path spec
     * @return the servlet mapping for the path spec (or null if not found)
     */
    public ServletMapping getServletMapping(String pathSpec)
    {
        if (pathSpec == null)
            return null;

        ServletMapping mapping = null;
        for (int i = 0; i < _servletMappings.size() && mapping == null; i++)
        {
            ServletMapping m = _servletMappings.get(i);
            if (m.getPathSpecs() != null)
            {
                for (String p : m.getPathSpecs())
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

    @ManagedAttribute(value = "servlets", readonly = true)
    public ServletHolder[] getServlets()
    {
        return _servlets.toArray(new ServletHolder[0]);
    }

    public List<ServletHolder> getServlets(Class<?> clazz)
    {
        List<ServletHolder> holders = null;
        for (ServletHolder holder : _servlets)
        {
            Class<? extends Servlet> held = holder.getHeldClass();
            if ((held == null && holder.getClassName() != null && holder.getClassName().equals(clazz.getName())) ||
                (held != null && clazz.isAssignableFrom(holder.getHeldClass())))
            {
                if (holders == null)
                    holders = new ArrayList<>();
                holders.add(holder);
            }
        }
        return holders == null ? Collections.emptyList() : holders;
    }

    public ServletHolder getServlet(String name)
    {
        MappedServlet mapped = _servletNameMap.get(name);
        if (mapped != null)
            return mapped.getServletHolder();
        return null;
    }
    
    protected IdentityService getIdentityService()
    {
        return _identityService;
    }

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        // TODO avoid lambda creation
        return (req, resp, cb) ->
        {
            // We will always have a ServletScopedRequest and MappedServlet otherwise we will not reach ServletHandler.
            ServletContextRequest servletRequest = Request.as(request, ServletContextRequest.class);
            servletRequest.getServletChannel().handle();
        };
    }

    /**
     * ServletHolder matching target path.
     *
     * @param target Path within _context or servlet name
     * @return MatchedResource, pointing to the {@link MappedResource} for the {@link ServletHolder}, and also the pathspec specific name/info sections for the match.
     *      Named servlets have a null PathSpec and {@link MatchedResource}.
     */
    public MatchedResource<MappedServlet> getMatchedServlet(String target)
    {
        if (target.startsWith("/"))
        {
            if (_servletPathMap == null)
                return null;
            return _servletPathMap.getMatched(target);
        }

        MappedServlet holder = _servletNameMap.get(target);
        if (holder == null)
            return null;
        return new MatchedResource<>(holder, null, MatchedPath.EMPTY);
    }

    /**
     * ServletHolder matching path.
     *
     * @param target Path within _context or servlet name
     * @return MappedResource to the ServletHolder.  Named servlets have a null PathSpec
     */
    public MappedServlet getMappedServlet(String target)
    {
        MatchedResource<MappedServlet> matchedResource = getMatchedServlet(target);
        return matchedResource.getResource();
    }

    protected FilterChain getFilterChain(HttpServletRequest request, String pathInContext, ServletHolder servletHolder)
    {
        DispatcherType dispatcherType = request.getDispatcherType();
        Objects.requireNonNull(servletHolder);
        String key = pathInContext == null ? servletHolder.getName() : pathInContext;
        int dispatch = FilterMapping.dispatch(dispatcherType);

        if (_filterChainsCached)
        {
            FilterChain chain = _chainCache[dispatch].get(key);
            if (chain != null)
                return chain;
        }

        // Build the filter chain from the inside out.
        // ie first wrap the servlet with the last filter to be applied.
        // The mappings lists have been reversed to make this simple and fast.
        FilterChain chain = null;

        if (_filterNameMappings != null && !_filterNameMappings.isEmpty())
        {
            if (_wildFilterNameMappings != null)
                for (FilterMapping mapping : _wildFilterNameMappings)
                    chain = newFilterChain(mapping.getFilterHolder(), chain == null ? new ChainEnd(servletHolder) : chain);

            List<FilterMapping> nameMappings = _filterNameMappings.get(servletHolder.getName());
            if (nameMappings != null)
            {
                for (FilterMapping mapping : nameMappings)
                {
                    if (mapping.appliesTo(dispatch))
                        chain = newFilterChain(mapping.getFilterHolder(), chain == null ? new ChainEnd(servletHolder) : chain);
                }
            }
        }

        if (pathInContext != null && _filterPathMappings != null)
        {
            for (FilterMapping mapping : _filterPathMappings)
            {
                if (mapping.appliesTo(pathInContext, dispatch))
                    chain = newFilterChain(mapping.getFilterHolder(), chain == null ? new ChainEnd(servletHolder) : chain);
            }
        }

        if (_filterChainsCached)
        {
            final Map<String, FilterChain> cache = _chainCache[dispatch];
            // Do we have too many cached chains?
            if (_maxFilterChainsCacheSize > 0 && cache.size() >= _maxFilterChainsCacheSize)
            {
                // flush the cache
                LOG.debug("{} flushed filter chain cache for {}", this, dispatcherType);
                cache.clear();
            }
            chain = chain == null ? new ChainEnd(servletHolder) : chain;
            // flush the cache
            LOG.debug("{} cached filter chain for {}: {}", this, dispatcherType, chain);
            cache.put(key, chain);
        }
        return chain;
    }

    /**
     * Create a FilterChain that calls the passed filter with the passed chain
     * @param filterHolder The filter to invoke
     * @param chain The chain to pass to the filter
     * @return A FilterChain that invokes the filter with the chain
     */
    protected FilterChain newFilterChain(FilterHolder filterHolder, FilterChain chain)
    {
        return new Chain(filterHolder, chain);
    }

    protected void invalidateChainsCache()
    {
        if (_chainCache[FilterMapping.REQUEST] != null)
        {
            _chainCache[FilterMapping.REQUEST].clear();
            _chainCache[FilterMapping.FORWARD].clear();
            _chainCache[FilterMapping.INCLUDE].clear();
            _chainCache[FilterMapping.ERROR].clear();
            _chainCache[FilterMapping.ASYNC].clear();
        }
    }

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

    /**
     * @param start True if this handler will start with unavailable servlets
     */
    public void setStartWithUnavailable(boolean start)
    {
        _startWithUnavailable = start;
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

    /**
     * @return True if this handler will start with unavailable servlets
     */
    public boolean isStartWithUnavailable()
    {
        return _startWithUnavailable;
    }

    /**
     * Initialize filters and load-on-startup servlets.
     *
     * @throws Exception if unable to initialize
     */
    public void initialize()
        throws Exception
    {
        ExceptionUtil.MultiException multiException = new ExceptionUtil.MultiException();

        Consumer<BaseHolder<?>> c = h ->
        {
            if (!h.isStarted())
            {
                multiException.callAndCatch(() ->
                {
                    h.start();
                    h.initialize();
                });
            }
        };
        
        //Start the listeners so we can call them
        _listeners.forEach(c);

        //call listeners contextInitialized
        if (_servletContextHandler != null)
            _servletContextHandler.contextInitialized();

        
        //Only set initialized true AFTER the listeners have been called
        _initialized = true;
            
        //Start the filters then the servlets
        Stream.concat(
            _filters.stream(),
            _servlets.stream().sorted())
            .forEach(c);

        multiException.ifExceptionThrow();
    }
    
    /**
     * @return true if initialized has been called, false otherwise
     */
    public boolean isInitialized()
    {
        return _initialized;
    }

    protected void initializeHolders(java.util.Collection<? extends BaseHolder<?>> holders)
    {
        for (BaseHolder<?> holder : holders)
        {
            holder.setServletHandler(this);
            if (isInitialized())
            {
                try
                {
                    if (!holder.isStarted())
                    {
                        holder.start();
                        holder.initialize();
                    }
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    /**
     * @return whether the filter chains are cached.
     */
    public boolean isFilterChainsCached()
    {
        return _filterChainsCached;
    }

    /**
     * Add a holder for a listener
     *
     * @param listener the listener for the holder
     */
    public void addListener(ListenerHolder listener)
    {
        if (listener != null)
            setListeners(ArrayUtil.addToArray(getListeners(), listener, ListenerHolder.class));
    }

    public ListenerHolder[] getListeners()
    {
        return _listeners.toArray(new ListenerHolder[0]);
    }

    public void setListeners(ListenerHolder[] holders)
    {
        List<ListenerHolder> listeners = holders == null ? Collections.emptyList() : Arrays.asList(holders);
        initializeHolders(listeners);
        updateBeans(_listeners, listeners);
        _listeners.clear();
        _listeners.addAll(listeners);
    }

    public ListenerHolder newListenerHolder(Source source)
    {
        return new ListenerHolder(source);
    }

    /**
     * Add a new servlet holder
     *
     * @param source the holder source
     * @return the servlet holder
     */
    public ServletHolder newServletHolder(Source source)
    {
        return new ServletHolder(source);
    }

    /**
     * Convenience method to add a servlet.
     *
     * @param className the class name
     * @param pathSpec the path spec
     * @return The servlet holder.
     */
    public ServletHolder addServletWithMapping(String className, String pathSpec)
    {
        ServletHolder holder = newServletHolder(Source.EMBEDDED);
        holder.setClassName(className);
        addServletWithMapping(holder, pathSpec);
        return holder;
    }

    /**
     * Convenience method to add a servlet.
     *
     * @param servlet the servlet class
     * @param pathSpec the path spec
     * @return The servlet holder.
     */
    public ServletHolder addServletWithMapping(Class<? extends Servlet> servlet, String pathSpec)
    {
        ServletHolder holder = newServletHolder(Source.EMBEDDED);
        holder.setHeldClass(servlet);
        addServletWithMapping(holder, pathSpec);

        return holder;
    }

    /**
     * Convenience method to add a servlet.
     *
     * @param servlet servlet holder to add
     * @param pathSpec servlet mappings for the servletHolder
     */
    public void addServletWithMapping(ServletHolder servlet, String pathSpec)
    {
        Objects.requireNonNull(servlet);
        ServletHolder[] holders = getServlets();
        try
        {
            try (AutoLock ignored = lock())
            {
                if (!containsServletHolder(servlet))
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

    /**
     * Convenience method to add a pre-constructed ServletHolder.
     *
     * @param holder the servlet holder
     */
    public void addServlet(ServletHolder holder)
    {
        if (holder == null)
            return;

        try (AutoLock ignored = lock())
        {
            if (!containsServletHolder(holder))
                setServlets(ArrayUtil.addToArray(getServlets(), holder, ServletHolder.class));
        }
    }

    /**
     * Convenience method to add a pre-constructed ServletMapping.
     *
     * @param mapping the servlet mapping
     */
    public void addServletMapping(ServletMapping mapping)
    {
        setServletMappings(ArrayUtil.addToArray(getServletMappings(), mapping, ServletMapping.class));
    }

    public Set<String> setServletSecurity(ServletRegistration.Dynamic registration, ServletSecurityElement servletSecurityElement)
    {
        if (_servletContextHandler != null)
        {
            return _servletContextHandler.setServletSecurity(registration, servletSecurityElement);
        }
        return Collections.emptySet();
    }

    public FilterHolder newFilterHolder(Source source)
    {
        return new FilterHolder(source);
    }

    public FilterHolder getFilter(String name)
    {
        return _filterNameMap.get(name);
    }

    /**
     * Convenience method to add a filter.
     *
     * @param filter class of filter to create
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     * @return The filter holder.
     */
    public FilterHolder addFilterWithMapping(Class<? extends Filter> filter, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        FilterHolder holder = newFilterHolder(Source.EMBEDDED);
        holder.setHeldClass(filter);
        addFilterWithMapping(holder, pathSpec, dispatches);

        return holder;
    }

    /**
     * Convenience method to add a filter.
     *
     * @param className of filter
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     * @return The filter holder.
     */
    public FilterHolder addFilterWithMapping(String className, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        FilterHolder holder = newFilterHolder(Source.EMBEDDED);
        holder.setClassName(className);

        addFilterWithMapping(holder, pathSpec, dispatches);
        return holder;
    }

    /**
     * Convenience method to add a filter.
     *
     * @param holder filter holder to add
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     */
    public void addFilterWithMapping(FilterHolder holder, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        Objects.requireNonNull(holder);
        FilterHolder[] holders = getFilters();

        try
        {
            try (AutoLock ignored = lock())
            {
                if (!containsFilterHolder(holder))
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

    /**
     * Convenience method to add a filter.
     *
     * @param filter class of filter to create
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     * @return The filter holder.
     */
    public FilterHolder addFilterWithMapping(Class<? extends Filter> filter, String pathSpec, int dispatches)
    {
        FilterHolder holder = newFilterHolder(Source.EMBEDDED);
        holder.setHeldClass(filter);
        addFilterWithMapping(holder, pathSpec, dispatches);

        return holder;
    }

    /**
     * Convenience method to add a filter.
     *
     * @param className of filter
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     * @return The filter holder.
     */
    public FilterHolder addFilterWithMapping(String className, String pathSpec, int dispatches)
    {
        FilterHolder holder = newFilterHolder(Source.EMBEDDED);
        holder.setClassName(className);

        addFilterWithMapping(holder, pathSpec, dispatches);
        return holder;
    }

    /**
     * Convenience method to add a filter.
     *
     * @param holder filter holder to add
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     */
    public void addFilterWithMapping(FilterHolder holder, String pathSpec, int dispatches)
    {
        Objects.requireNonNull(holder);
        FilterHolder[] holders = getFilters();
        if (holders != null)
            holders = holders.clone();

        try
        {
            try (AutoLock ignored = lock())
            {
                if (!containsFilterHolder(holder))
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

    /**
     * Convenience method to add a filter and mapping
     *
     * @param filter the filter holder
     * @param filterMapping the filter mapping
     */
    public void addFilter(FilterHolder filter, FilterMapping filterMapping)
    {
        if (filter != null)
        {
            try (AutoLock ignored = lock())
            {
                if (!containsFilterHolder(filter))
                    setFilters(ArrayUtil.addToArray(getFilters(), filter, FilterHolder.class));
            }
        }
        if (filterMapping != null)
            addFilterMapping(filterMapping);
    }

    /**
     * Convenience method to add a preconstructed FilterHolder
     *
     * @param filter the filter holder
     */
    public void addFilter(FilterHolder filter)
    {
        if (filter == null)
            return;

        try (AutoLock ignored = lock())
        {
            if (!containsFilterHolder(filter))
                setFilters(ArrayUtil.addToArray(getFilters(), filter, FilterHolder.class));
        }
    }

    /**
     * Convenience method to add a preconstructed FilterHolder
     *
     * @param filter the filter holder
     */
    public void prependFilter(FilterHolder filter)
    {
        if (filter == null)
            return;

        try (AutoLock ignored = lock())
        {
            if (!containsFilterHolder(filter))
                setFilters(ArrayUtil.prependToArray(filter, getFilters(), FilterHolder.class));
        }
    }

    /**
     * Convenience method to add a preconstructed FilterMapping
     *
     * @param mapping the filter mapping
     */
    public void addFilterMapping(FilterMapping mapping)
    {
        if (mapping == null)
            return;

        try (AutoLock ignored = lock())
        {
            Source source = (mapping.getFilterHolder() == null ? null : mapping.getFilterHolder().getSource());

            if (_filterMappings.isEmpty())
            {
                _filterMappings.add(mapping);
                if (source == Source.JAVAX_API)
                    _matchAfterIndex = 0;
            }
            else
            {
                //there are existing entries. If this is a programmatic filtermapping, it is added at the end of the list.
                //If this is a normal filtermapping, it is inserted after all the other filtermappings (matchBefores and normals),
                //but before the first matchAfter filtermapping.
                if (Source.JAVAX_API == source)
                {
                    _filterMappings.add(mapping);
                    if (_matchAfterIndex < 0)
                        _matchAfterIndex = getFilterMappings().length - 1;
                }
                else
                {
                    //insert non-programmatic filter mappings before any matchAfters, if any
                    if (_matchAfterIndex < 0)
                        _filterMappings.add(mapping);
                    else
                        _filterMappings.add(_matchAfterIndex++, mapping);
                }
            }
            addBean(mapping);
            if (isRunning())
                updateMappings();
            invalidateChainsCache();
        }
    }

    /**
     * Convenience method to add a preconstructed FilterMapping
     *
     * @param mapping the filter mapping
     */
    public void prependFilterMapping(FilterMapping mapping)
    {
        if (mapping == null)
            return;

        try (AutoLock ignored = lock())
        {
            Source source = (mapping.getFilterHolder() == null ? null : mapping.getFilterHolder().getSource());
            if (_filterMappings.isEmpty())
            {
                _filterMappings.add(mapping);
                if (Source.JAVAX_API == source)
                    _matchBeforeIndex = 0;
            }
            else
            {
                if (Source.JAVAX_API == source)
                {
                    //programmatically defined filter mappings are prepended to mapping list in the order
                    //in which they were defined. In other words, insert this mapping at the tail of the 
                    //programmatically prepended filter mappings, BEFORE the first web.xml defined filter mapping.

                    if (_matchBeforeIndex < 0)
                    {
                        //no programmatically defined prepended filter mappings yet, prepend this one
                        _matchBeforeIndex = 0;
                        _filterMappings.add(0, mapping);
                    }
                    else
                    {
                        _filterMappings.add(1 + _matchBeforeIndex++, mapping);
                    }
                }
                else
                {
                    //non programmatically defined, just prepend to list
                    _filterMappings.add(0, mapping);
                }

                //adjust matchAfterIndex ptr to take account of the mapping we just prepended
                if (_matchAfterIndex >= 0)
                    ++_matchAfterIndex;
            }
            addBean(mapping);
            if (isRunning())
                updateMappings();
            invalidateChainsCache();
        }
    }

    public void removeFilterHolder(FilterHolder holder)
    {
        if (holder == null)
            return;

        try (AutoLock ignored = lock())
        {
            FilterHolder[] holders = Arrays.stream(getFilters())
                .filter(h -> h != holder)
                .toArray(FilterHolder[]::new);
            setFilters(holders);
        }
    }

    public void removeFilterMapping(FilterMapping mapping)
    {
        if (mapping == null)
            return;

        try (AutoLock ignored = lock())
        {
            FilterMapping[] mappings = Arrays.stream(getFilterMappings())
                .filter(m -> m != mapping)
                .toArray(FilterMapping[]::new);
            setFilterMappings(mappings);
        }
    }

    protected void updateNameMappings()
    {
        try (AutoLock ignored = lock())
        {
            // update filter name map
            _filterNameMap.clear();
            for (FilterHolder filter : _filters)
            {
                _filterNameMap.put(filter.getName(), filter);
                filter.setServletHandler(this);
            }

            // Map servlet names to holders
            _servletNameMap.clear();
            // update the maps
            for (ServletHolder servlet : _servlets)
            {
                _servletNameMap.put(servlet.getName(), new MappedServlet(null, servlet));
                servlet.setServletHandler(this);
            }
        }
    }

    protected PathSpec asPathSpec(String pathSpec)
    {
        // By default only allow servlet path specs
        return new ServletPathSpec(pathSpec);
    }

    protected void updateMappings()
    {
        try (AutoLock ignored = lock())
        {
            // update filter mappings
            _filterPathMappings = new ArrayList<>();
            _filterNameMappings = new MultiMap<>();
            for (FilterMapping filtermapping : _filterMappings)
            {
                FilterHolder filterHolder = _filterNameMap.get(filtermapping.getFilterName());
                if (filterHolder == null)
                    throw new IllegalStateException("No filter named " + filtermapping.getFilterName());
                filtermapping.setFilterHolder(filterHolder);
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
            // Reverse filter mappings to apply as wrappers last filter wrapped first
            for (Map.Entry<String, List<FilterMapping>> entry : _filterNameMappings.entrySet())
                Collections.reverse(entry.getValue());
            Collections.reverse(_filterPathMappings);
            _wildFilterNameMappings = _filterNameMappings.get("*");
            if (_wildFilterNameMappings != null)
                Collections.reverse(_wildFilterNameMappings);

            // Map servlet paths to holders
            PathMappings<MappedServlet> pm = new PathMappings<>();

            //create a map of paths to set of ServletMappings that define that mapping
            HashMap<String, List<ServletMapping>> sms = new HashMap<>();
            for (ServletMapping servletMapping : _servletMappings)
            {
                String[] pathSpecs = servletMapping.getPathSpecs();
                if (pathSpecs != null)
                {
                    for (String pathSpec : pathSpecs)
                    {
                        List<ServletMapping> mappings = sms.computeIfAbsent(pathSpec, k -> new ArrayList<>());
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
                    ServletHolder servletHolder = getServlet(mapping.getServletName());
                    if (servletHolder == null)
                        throw new IllegalStateException("No such servlet: " + mapping.getServletName());
                    //if the servlet related to the mapping is not enabled, skip it from consideration
                    if (!servletHolder.isEnabled())
                        continue;

                    //only accept a default mapping if we don't have any other
                    if (finalMapping == null)
                        finalMapping = mapping;
                    else
                    {
                        //already have a candidate - only accept another one
                        //if the candidate is a default, or we're allowing duplicate mappings
                        if (finalMapping.isFromDefaultDescriptor())
                            finalMapping = mapping;
                        else if (isAllowDuplicateMappings())
                        {
                            LOG.warn("Multiple servlets map to path {}: {} and {}, choosing {}", pathSpec, finalMapping.getServletName(), mapping.getServletName(), mapping);
                            finalMapping = mapping;
                        }
                        else
                        {
                            //existing candidate isn't a default, if the one we're looking at isn't a default either, then its an error
                            if (!mapping.isFromDefaultDescriptor())
                            {
                                ServletHolder finalMappedServlet = getServlet(finalMapping.getServletName());
                                throw new IllegalStateException("Multiple servlets map to path " +
                                    pathSpec + ": " +
                                    finalMappedServlet.getName() + "[mapped:" + finalMapping.getSource() + "]," +
                                    mapping.getServletName() + "[mapped:" + mapping.getSource() + "]");
                            }
                        }
                    }
                }
                if (finalMapping == null)
                    throw new IllegalStateException("No acceptable servlet mappings for " + pathSpec);

                if (LOG.isDebugEnabled())
                    LOG.debug("Path={}[{}] mapped to servlet={}[{}]",
                        pathSpec,
                        finalMapping.getSource(),
                        finalMapping.getServletName(),
                        getServlet(finalMapping.getServletName()).getSource());

                PathSpec servletPathSpec = asPathSpec(pathSpec);
                MappedServlet mappedServlet = new MappedServlet(servletPathSpec, getServlet(finalMapping.getServletName()));
                pm.put(servletPathSpec, mappedServlet);
            }

            _servletPathMap = pm;

            // flush filter chain cache
            for (int i = _chainCache.length; i-- > 0; )
            {
                if (_chainCache[i] != null)
                    _chainCache[i].clear();
            }

            if (LOG.isDebugEnabled())
            {
                LOG.debug("filterNameMap={} pathFilters={} servletFilterMap={} servletPathMap={} servletNameMap={}",
                    _filterNameMap, _filterPathMappings, _filterNameMappings, _servletPathMap, _servletNameMap);
            }
        }
    }

    protected void notFound(Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Not Found {}", request.getRequestURI());
    }

    protected boolean containsFilterHolder(FilterHolder holder)
    {
        try (AutoLock ignored = lock())
        {
            return _filters.contains(holder);
        }
    }

    protected boolean containsServletHolder(ServletHolder holder)
    {
        try (AutoLock ignored = lock())
        {
            return _servlets.contains(holder);
        }
    }

    /**
     * @param filterChainsCached The filterChainsCached to set.
     */
    public void setFilterChainsCached(boolean filterChainsCached)
    {
        _filterChainsCached = filterChainsCached;
    }

    /**
     * @param filterMappings The filterMappings to set.
     */
    public void setFilterMappings(FilterMapping[] filterMappings)
    {
        try (AutoLock ignored = lock())
        {
            List<FilterMapping> mappings = filterMappings == null ? Collections.emptyList() : Arrays.asList(filterMappings);
            updateAndSet(_filterMappings, mappings);
            if (isRunning())
                updateMappings();
            invalidateChainsCache();
        }
    }

    public void setFilters(FilterHolder[] holders)
    {
        try (AutoLock ignored = lock())
        {
            List<FilterHolder> filters = holders == null ? Collections.emptyList() : Arrays.asList(holders);
            initializeHolders(filters);
            updateAndSet(_filters, filters);
            updateNameMappings();
            invalidateChainsCache();
        }
    }

    /**
     * @param servletMappings The servletMappings to set.
     */
    public void setServletMappings(ServletMapping[] servletMappings)
    {
        List<ServletMapping> mappings = servletMappings == null ? Collections.emptyList() : Arrays.asList(servletMappings);
        updateAndSet(_servletMappings, mappings);
        if (isRunning())
            updateMappings();
        invalidateChainsCache();
    }

    /**
     * Set Servlets.
     *
     * @param holders Array of servlets to define
     */
    public void setServlets(ServletHolder[] holders)
    {
        try (AutoLock ignored = lock())
        {
            List<ServletHolder> servlets = holders == null ? Collections.emptyList() : Arrays.asList(holders);
            initializeHolders(servlets);
            updateAndSet(_servlets, servlets);
            updateNameMappings();
            invalidateChainsCache();
        }
    }

    /**
     * @return The maximum entries in a filter chain cache.
     */
    public int getMaxFilterChainsCacheSize()
    {
        return _maxFilterChainsCacheSize;
    }

    /**
     * Set the maximum filter chain cache size.
     * Filter chains are cached if {@link #isFilterChainsCached()} is true. If the max cache size
     * is greater than zero, then the cache is flushed whenever it grows to be this size.
     *
     * @param maxFilterChainsCacheSize the maximum number of entries in a filter chain cache.
     */
    public void setMaxFilterChainsCacheSize(int maxFilterChainsCacheSize)
    {
        _maxFilterChainsCacheSize = maxFilterChainsCacheSize;
    }

    void destroyServlet(Servlet servlet)
    {
        if (_servletContextHandler != null)
            _servletContextHandler.destroyServlet(servlet);
    }

    void destroyFilter(Filter filter)
    {
        if (_servletContextHandler != null)
            _servletContextHandler.destroyFilter(filter);
    }

    void destroyListener(EventListener listener)
    {
        if (_servletContextHandler != null)
            _servletContextHandler.destroyListener(listener);
    }

    /**
     * A mapping of a servlet by pathSpec or by name
     */
    public static class MappedServlet
    {
        private final PathSpec _pathSpec;
        private final ServletHolder _servletHolder;
        private final ServletPathMapping _servletPathMapping;

        MappedServlet(PathSpec pathSpec, ServletHolder servletHolder)
        {
            _pathSpec = pathSpec;
            _servletHolder = servletHolder;

            // Create the HttpServletMapping only once if possible.
            if (pathSpec instanceof ServletPathSpec)
            {
                switch (pathSpec.getGroup())
                {
                    case EXACT:
                        _servletPathMapping = new ServletPathMapping(_pathSpec, _servletHolder.getName(), _pathSpec.getDeclaration());
                        break;
                    case ROOT:
                        _servletPathMapping = new ServletPathMapping(_pathSpec, _servletHolder.getName(), "/");
                        break;
                    default:
                        _servletPathMapping = null;
                        break;
                }
            }
            else
            {
                _servletPathMapping = null;
            }
        }

        public PathSpec getPathSpec()
        {
            return _pathSpec;
        }

        public ServletHolder getServletHolder()
        {
            return _servletHolder;
        }

        public ServletPathMapping getServletPathMapping(String pathInContext)
        {
            if (_servletPathMapping != null)
                return _servletPathMapping;
            if (_pathSpec != null)
                return new ServletPathMapping(_pathSpec, _servletHolder.getName(), pathInContext, null);
            return null;
        }

        public ServletPathMapping getServletPathMapping(String pathInContext, MatchedPath matchedPath)
        {
            if (_servletPathMapping != null)
                return _servletPathMapping;
            if (_pathSpec != null)
                return new ServletPathMapping(_pathSpec, _servletHolder.getName(), pathInContext, matchedPath);
            return null;
        }

        public void handle(ServletHandler servletHandler, String pathInContext, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            FilterChain filterChain = servletHandler.getFilterChain(request, pathInContext, _servletHolder);
            if (LOG.isDebugEnabled())
                LOG.debug("chain={}", filterChain);

            _servletHolder.prepare(request, response);
            if (filterChain != null)
                filterChain.doFilter(request, response);
            else
                _servletHolder.handle(request, response);
        }

        @Override
        public String toString()
        {
            return String.format("MappedServlet%x{%s->%s}",
                hashCode(), _pathSpec == null ? null : _pathSpec.getDeclaration(), _servletHolder);
        }
    }

    @SuppressWarnings("serial")
    public static class Default404Servlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException
        {
            //TODO
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    static class Chain implements FilterChain
    {
        private final FilterHolder _filterHolder;
        private final FilterChain _filterChain;

        Chain(FilterHolder filter, FilterChain chain)
        {
            _filterHolder = filter;
            _filterChain = chain;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException
        {
            _filterHolder.doFilter(request, response, _filterChain);
        }

        @Override
        public String toString()
        {
            return String.format("Chain@%x(%s)->%s", hashCode(), _filterHolder, _filterChain);
        }
    }

    static class ChainEnd implements FilterChain
    {
        private final ServletHolder _servletHolder;

        ChainEnd(ServletHolder holder)
        {
            Objects.requireNonNull(holder);
            _servletHolder = holder;
        }

        public ServletHolder getServletHolder()
        {
            return _servletHolder;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException
        {
            _servletHolder.handle(request, response);
        }

        @Override
        public String toString()
        {
            return String.format("ChainEnd@%x(%s)", hashCode(), _servletHolder);
        }
    }
}
