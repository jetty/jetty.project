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

package org.eclipse.jetty.ee9.servlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.eclipse.jetty.ee9.nested.Request;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FilterHolder extends Holder<Filter>
{
    private static final Logger LOG = LoggerFactory.getLogger(FilterHolder.class);

    private transient Filter _filter;
    private transient Config _config;
    private transient FilterRegistration.Dynamic _registration;

    /**
     * Constructor
     */
    public FilterHolder()
    {
        this(Source.EMBEDDED);
    }

    /**
     * Constructor
     *
     * @param source the holder source
     */
    public FilterHolder(Source source)
    {
        super(source);
    }

    /**
     * Constructor
     *
     * @param filter the filter class
     */
    public FilterHolder(Class<? extends Filter> filter)
    {
        this(Source.EMBEDDED);
        setHeldClass(filter);
    }

    /**
     * Constructor for existing filter.
     *
     * @param filter the filter
     */
    public FilterHolder(Filter filter)
    {
        this(Source.EMBEDDED);
        setFilter(filter);
    }

    @Override
    public void doStart()
        throws Exception
    {
        super.doStart();

        if (!jakarta.servlet.Filter.class.isAssignableFrom(getHeldClass()))
        {
            String msg = getHeldClass() + " is not a " + Filter.class.getName();
            doStop();
            throw new IllegalStateException(msg);
        }
    }

    @Override
    public void initialize() throws Exception
    {
        try (AutoLock l = lock())
        {
            if (_filter != null)
                return;

            super.initialize();
            _filter = getInstance();
            if (_filter == null)
            {
                try
                {
                    _filter = createInstance();
                }
                catch (ServletException ex)
                {
                    Throwable cause = ex.getRootCause();
                    if (cause instanceof InstantiationException)
                        throw (InstantiationException)cause;
                    if (cause instanceof IllegalAccessException)
                        throw (IllegalAccessException)cause;
                    throw ex;
                }
            }
            _filter = wrap(_filter, FilterHolder.WrapFunction.class, FilterHolder.WrapFunction::wrapFilter);
            _config = new Config();
            if (LOG.isDebugEnabled())
                LOG.debug("Filter.init {}", _filter);
            _filter.init(_config);
        }
    }

    @Override
    protected Filter createInstance() throws Exception
    {
        try (AutoLock l = lock())
        {
            Filter filter = super.createInstance();
            if (filter == null)
            {
                ServletContext context = getServletContext();
                if (context != null)
                    filter = context.createFilter(getHeldClass());
            }
            return filter;
        }
    }

    @Override
    public void doStop()
        throws Exception
    {
        super.doStop();
        _config = null;
        if (_filter != null)
        {
            try
            {
                destroyInstance(_filter);
            }
            finally
            {
                _filter = null;
            }
        }
    }

    @Override
    public void destroyInstance(Object o)
    {
        if (o == null)
            return;

        Filter filter = (Filter)o;

        // need to use the unwrapped filter because lifecycle callbacks such as
        // postconstruct and predestroy are based off the classname and the wrapper
        // classes are unknown outside the ServletHolder
        getServletHandler().destroyFilter(unwrap(filter));

        // destroy the wrapped filter, in case there is special behaviour
        filter.destroy();
    }

    public void setFilter(Filter filter)
    {
        setInstance(filter);
    }

    public Filter getFilter()
    {
        return _filter;
    }

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        if (isAsyncSupported() || !request.isAsyncSupported())
            getFilter().doFilter(request, response, chain);
        else
        {
            Request baseRequest = Request.getBaseRequest(request);
            Objects.requireNonNull(baseRequest);
            try
            {
                baseRequest.setAsyncSupported(false, this);
                getFilter().doFilter(request, response, chain);
            }
            finally
            {
                baseRequest.setAsyncSupported(true, null);
            }
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        if (getInitParameters().isEmpty())
            Dumpable.dumpObjects(out, indent, this,
                _filter == null ? getHeldClass() : _filter);
        else
            Dumpable.dumpObjects(out, indent, this,
                _filter == null ? getHeldClass() : _filter,
                new DumpableCollection("initParams", getInitParameters().entrySet()));
    }

    @Override
    public String toString()
    {
        return String.format("%s==%s@%x{inst=%b,async=%b,src=%s}",
            getName(), getClassName(), hashCode(), _filter != null, isAsyncSupported(), getSource());
    }

    public FilterRegistration.Dynamic getRegistration()
    {
        if (_registration == null)
            _registration = new Registration();
        return _registration;
    }

    protected class Registration extends HolderRegistration implements FilterRegistration.Dynamic
    {
        @Override
        public void addMappingForServletNames(EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter, String... servletNames)
        {
            illegalStateIfContextStarted();
            FilterMapping mapping = new FilterMapping();
            mapping.setFilterHolder(FilterHolder.this);
            mapping.setServletNames(servletNames);
            mapping.setDispatcherTypes(dispatcherTypes);
            if (isMatchAfter)
                getServletHandler().addFilterMapping(mapping);
            else
                getServletHandler().prependFilterMapping(mapping);
        }

        @Override
        public void addMappingForUrlPatterns(EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter, String... urlPatterns)
        {
            illegalStateIfContextStarted();
            FilterMapping mapping = new FilterMapping();
            mapping.setFilterHolder(FilterHolder.this);
            mapping.setPathSpecs(urlPatterns);
            mapping.setDispatcherTypes(dispatcherTypes);
            if (isMatchAfter)
                getServletHandler().addFilterMapping(mapping);
            else
                getServletHandler().prependFilterMapping(mapping);
        }

        @Override
        public Collection<String> getServletNameMappings()
        {
            FilterMapping[] mappings = getServletHandler().getFilterMappings();
            List<String> names = new ArrayList<>();
            for (FilterMapping mapping : mappings)
            {
                if (mapping.getFilterHolder() != FilterHolder.this)
                    continue;
                String[] servlets = mapping.getServletNames();
                if (servlets != null && servlets.length > 0)
                    names.addAll(Arrays.asList(servlets));
            }
            return names;
        }

        @Override
        public Collection<String> getUrlPatternMappings()
        {
            FilterMapping[] mappings = getServletHandler().getFilterMappings();
            List<String> patterns = new ArrayList<>();
            for (FilterMapping mapping : mappings)
            {
                if (mapping.getFilterHolder() != FilterHolder.this)
                    continue;
                String[] specs = mapping.getPathSpecs();
                patterns.addAll(TypeUtil.asList(specs));
            }
            return patterns;
        }
    }

    class Config extends HolderConfig implements FilterConfig
    {
        @Override
        public String getFilterName()
        {
            return getName();
        }
    }

    /**
     * Experimental Wrapper mechanism for Filter objects.
     * <p>
     * Beans in {@code ServletContextHandler} or {@code WebAppContext} that implement this interface
     * will be called to optionally wrap any newly created Filters
     * (before their {@link Filter#init(FilterConfig)} method is called)
     * </p>
     */
    public interface WrapFunction
    {
        /**
         * Optionally wrap the Filter.
         *
         * @param filter the Filter being passed in.
         * @return the Filter (extend from {@link FilterHolder.Wrapper} if you do wrap the Filter)
         */
        Filter wrapFilter(Filter filter);
    }

    public static class Wrapper implements Filter, Wrapped<Filter>
    {
        private final Filter _filter;

        public Wrapper(Filter filter)
        {
            _filter = Objects.requireNonNull(filter, "Filter cannot be null");
        }

        @Override
        public Filter getWrapped()
        {
            return _filter;
        }

        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {
            _filter.init(filterConfig);
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            _filter.doFilter(request, response, chain);
        }

        @Override
        public void destroy()
        {
            _filter.destroy();
        }

        @Override
        public String toString()
        {
            return String.format("%s:%s", this.getClass().getSimpleName(), _filter.toString());
        }
    }
}
