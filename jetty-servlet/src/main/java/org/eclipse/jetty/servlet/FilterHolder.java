//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
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
            String msg = getHeldClass() + " is not a jakarta.servlet.Filter";
            doStop();
            throw new IllegalStateException(msg);
        }
    }

    @Override
    public void initialize() throws Exception
    {
        synchronized (this)
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
            _config = new Config();
            if (LOG.isDebugEnabled())
                LOG.debug("Filter.init {}", _filter);
            _filter.init(_config);
        }
    }

    @Override
    protected synchronized Filter createInstance() throws Exception
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
        throws Exception
    {
        if (o == null)
            return;
        Filter f = (Filter)o;
        f.destroy();
        getServletHandler().destroyFilter(f);
    }

    public synchronized void setFilter(Filter filter)
    {
        setInstance(filter);
    }

    public Filter getFilter()
    {
        return _filter;
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
        return String.format("%s@%x==%s,inst=%b,async=%b", getName(), hashCode(), getClassName(), _filter != null, isAsyncSupported());
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
}
