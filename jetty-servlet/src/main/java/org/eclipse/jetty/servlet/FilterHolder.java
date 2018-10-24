//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterConfig;
import javax.servlet.FilterRegistration;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

public class  FilterHolder extends Holder<Filter>
{
    private static final Logger LOG = Log.getLogger(FilterHolder.class);

    /* ------------------------------------------------------------ */
    private transient Filter _filter;
    private transient Config _config;
    private transient FilterRegistration.Dynamic _registration;

    /* ---------------------------------------------------------------- */
    /** Constructor
     */
    public FilterHolder()
    {
        this(Source.EMBEDDED);
    }


    /* ---------------------------------------------------------------- */
    /** Constructor
     * @param source the holder source
     */
    public FilterHolder(Source source)
    {
        super(source);
    }

    /* ---------------------------------------------------------------- */
    /** Constructor
     * @param filter the filter class
     */
    public FilterHolder(Class<? extends Filter> filter)
    {
        this(Source.EMBEDDED);
        setHeldClass(filter);
    }

    /* ---------------------------------------------------------------- */
    /** Constructor for existing filter.
     * @param filter the filter
     */
    public FilterHolder(Filter filter)
    {
        this(Source.EMBEDDED);
        setFilter(filter);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void doStart()
        throws Exception
    {
        super.doStart();

        if (!javax.servlet.Filter.class
            .isAssignableFrom(_class))
        {
            String msg = _class+" is not a javax.servlet.Filter";
            super.stop();
            throw new IllegalStateException(msg);
        }
    }
    

    /* ------------------------------------------------------------ */
    @Override
    public void initialize() throws Exception
    {
        if (!_initialized)
        {
            super.initialize();

            if (_filter==null)
            {
                try
                {
                    ServletContext context=_servletHandler.getServletContext();
                    _filter=(context instanceof ServletContextHandler.Context)
                            ?context.createFilter(getHeldClass())
                            :getHeldClass().getDeclaredConstructor().newInstance();
                }
                catch (ServletException se)
                {
                    Throwable cause = se.getRootCause();
                    if (cause instanceof InstantiationException)
                        throw (InstantiationException)cause;
                    if (cause instanceof IllegalAccessException)
                        throw (IllegalAccessException)cause;
                    throw se;
                }
            }

            _config=new Config();
            if (LOG.isDebugEnabled())
                LOG.debug("Filter.init {}",_filter);
            _filter.init(_config);
        }
        
        _initialized = true;
    }


    /* ------------------------------------------------------------ */
    @Override
    public void doStop()
        throws Exception
    {
        if (_filter!=null)
        {
            try
            {
                destroyInstance(_filter);
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
        }
        if (!_extInstance)
            _filter=null;

        _config=null;
        _initialized = false;
        super.doStop();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void destroyInstance (Object o)
        throws Exception
    {
        if (o==null)
            return;
        Filter f = (Filter)o;
        f.destroy();
        getServletHandler().destroyFilter(f);
    }

    /* ------------------------------------------------------------ */
    public synchronized void setFilter(Filter filter)
    {
        _filter=filter;
        _extInstance=true;
        setHeldClass(filter.getClass());
        if (getName()==null)
            setName(filter.getClass().getName());
    }

    /* ------------------------------------------------------------ */
    public Filter getFilter()
    {
        return _filter;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        if (_initParams.isEmpty())
            Dumpable.dumpObjects(out, indent, this,
                _filter == null?getHeldClass():_filter);
        else
            Dumpable.dumpObjects(out, indent, this,
                _filter == null?getHeldClass():_filter,
                new DumpableCollection("initParams", _initParams.entrySet()));
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return String.format("%s@%x==%s,inst=%b,async=%b",_name,hashCode(),_className,_filter!=null,isAsyncSupported());
    }

    /* ------------------------------------------------------------ */
    public FilterRegistration.Dynamic getRegistration()
    {
        if (_registration == null)
            _registration = new Registration();
        return _registration;
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
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
                _servletHandler.addFilterMapping(mapping);
            else
                _servletHandler.prependFilterMapping(mapping);
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
                _servletHandler.addFilterMapping(mapping);
            else
                _servletHandler.prependFilterMapping(mapping);
        }

        @Override
        public Collection<String> getServletNameMappings()
        {
            FilterMapping[] mappings =_servletHandler.getFilterMappings();
            List<String> names=new ArrayList<String>();
            for (FilterMapping mapping : mappings)
            {
                if (mapping.getFilterHolder()!=FilterHolder.this)
                    continue;
                String[] servlets=mapping.getServletNames();
                if (servlets!=null && servlets.length>0)
                    names.addAll(Arrays.asList(servlets));
            }
            return names;
        }

        @Override
        public Collection<String> getUrlPatternMappings()
        {
            FilterMapping[] mappings =_servletHandler.getFilterMappings();
            List<String> patterns=new ArrayList<String>();
            for (FilterMapping mapping : mappings)
            {
                if (mapping.getFilterHolder()!=FilterHolder.this)
                    continue;
                String[] specs=mapping.getPathSpecs();
                patterns.addAll(TypeUtil.asList(specs));
            }
            return patterns;
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class Config extends HolderConfig implements FilterConfig
    {
        /* ------------------------------------------------------------ */
        @Override
        public String getFilterName()
        {
            return _name;
        }
    }
}
