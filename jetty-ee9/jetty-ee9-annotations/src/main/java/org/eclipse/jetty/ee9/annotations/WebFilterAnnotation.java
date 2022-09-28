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

package org.eclipse.jetty.ee9.annotations;

import java.util.ArrayList;
import java.util.EnumSet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebInitParam;
import org.eclipse.jetty.ee9.servlet.FilterHolder;
import org.eclipse.jetty.ee9.servlet.FilterMapping;
import org.eclipse.jetty.ee9.servlet.Source;
import org.eclipse.jetty.ee9.webapp.DiscoveredAnnotation;
import org.eclipse.jetty.ee9.webapp.MetaData;
import org.eclipse.jetty.ee9.webapp.Origin;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebFilterAnnotation
 */
public class WebFilterAnnotation extends DiscoveredAnnotation
{
    private static final Logger LOG = LoggerFactory.getLogger(WebFilterAnnotation.class);

    public WebFilterAnnotation(WebAppContext context, String className)
    {
        super(context, className);
    }

    public WebFilterAnnotation(WebAppContext context, String className, Resource resource)
    {
        super(context, className, resource);
    }

    @Override
    public void apply()
    {
        // TODO verify against rules for annotation v descriptor

        Class clazz = getTargetClass();
        if (clazz == null)
        {
            LOG.warn("{} cannot be loaded", _className);
            return;
        }

        //Servlet Spec 8.1.2
        if (!Filter.class.isAssignableFrom(clazz))
        {
            LOG.warn("{} is not assignable from jakarta.servlet.Filter", clazz.getName());
            return;
        }
        MetaData metaData = _context.getMetaData();

        WebFilter filterAnnotation = (WebFilter)clazz.getAnnotation(WebFilter.class);

        if (filterAnnotation.value().length > 0 && filterAnnotation.urlPatterns().length > 0)
        {
            LOG.warn("{} defines both @WebFilter.value and @WebFilter.urlPatterns", clazz.getName());
            return;
        }

        String name = (filterAnnotation.filterName().equals("") ? clazz.getName() : filterAnnotation.filterName());
        String[] urlPatterns = filterAnnotation.value();
        if (urlPatterns.length == 0)
            urlPatterns = filterAnnotation.urlPatterns();

        FilterHolder holder = _context.getServletHandler().getFilter(name);
        if (holder == null)
        {
            //Filter with this name does not already exist, so add it
            holder = _context.getServletHandler().newFilterHolder(new Source(Source.Origin.ANNOTATION, clazz));
            holder.setName(name);

            holder.setHeldClass(clazz);
            metaData.setOrigin(name + ".filter.filter-class", filterAnnotation, clazz);

            holder.setDisplayName(filterAnnotation.displayName());
            metaData.setOrigin(name + ".filter.display-name", filterAnnotation, clazz);

            for (WebInitParam ip : filterAnnotation.initParams())
            {
                holder.setInitParameter(ip.name(), ip.value());
                metaData.setOrigin(name + ".filter.init-param." + ip.name(), ip, clazz);
            }

            FilterMapping mapping = new FilterMapping();
            mapping.setFilterName(holder.getName());
            metaData.setOrigin(name + ".filter.mapping." + Long.toHexString(mapping.hashCode()), filterAnnotation, clazz);
            if (urlPatterns.length > 0)
            {
                ArrayList<String> paths = new ArrayList<String>();
                for (String s : urlPatterns)
                {
                    paths.add(ServletPathSpec.normalize(s));
                }
                mapping.setPathSpecs(paths.toArray(new String[paths.size()]));
            }

            if (filterAnnotation.servletNames().length > 0)
            {
                ArrayList<String> names = new ArrayList<String>();
                for (String s : filterAnnotation.servletNames())
                {
                    names.add(s);
                }
                mapping.setServletNames(names.toArray(new String[names.size()]));
            }

            EnumSet<DispatcherType> dispatcherSet = EnumSet.noneOf(DispatcherType.class);
            for (DispatcherType d : filterAnnotation.dispatcherTypes())
            {
                dispatcherSet.add(d);
            }
            mapping.setDispatcherTypes(dispatcherSet);
            metaData.setOrigin(name + ".filter.mappings", filterAnnotation, clazz);

            holder.setAsyncSupported(filterAnnotation.asyncSupported());
            metaData.setOrigin(name + ".filter.async-supported", filterAnnotation, clazz);

            _context.getServletHandler().addFilter(holder);
            _context.getServletHandler().addFilterMapping(mapping);
        }
        else
        {
            //A Filter definition for the same name already exists from web.xml
            //ServletSpec 3.0 p81 if the Filter is already defined and has mappings,
            //they override the annotation. If it already has DispatcherType set, that
            //also overrides the annotation. Init-params are additive, but web.xml overrides
            //init-params of the same name.
            for (WebInitParam ip : filterAnnotation.initParams())
            {
                //if (holder.getInitParameter(ip.name()) == null)
                if (metaData.getOrigin(name + ".filter.init-param." + ip.name()) == Origin.NotSet)
                {
                    holder.setInitParameter(ip.name(), ip.value());
                    metaData.setOrigin(name + ".filter.init-param." + ip.name(), ip, clazz);
                }
            }

            FilterMapping[] mappings = _context.getServletHandler().getFilterMappings();
            boolean mappingExists = false;
            if (mappings != null)
            {
                for (FilterMapping m : mappings)
                {
                    if (m.getFilterName().equals(name))
                    {
                        mappingExists = true;
                        break;
                    }
                }
            }
            //if a descriptor didn't specify at least one mapping, use the mappings from the annotation and the DispatcherTypes
            //from the annotation
            if (!mappingExists)
            {
                FilterMapping mapping = new FilterMapping();
                mapping.setFilterName(holder.getName());
                metaData.setOrigin(holder.getName() + ".filter.mapping." + Long.toHexString(mapping.hashCode()), filterAnnotation, clazz);
                if (urlPatterns.length > 0)
                {
                    ArrayList<String> paths = new ArrayList<String>();
                    for (String s : urlPatterns)
                    {
                        paths.add(ServletPathSpec.normalize(s));
                    }
                    mapping.setPathSpecs(paths.toArray(new String[paths.size()]));
                }
                if (filterAnnotation.servletNames().length > 0)
                {
                    ArrayList<String> names = new ArrayList<String>();
                    for (String s : filterAnnotation.servletNames())
                    {
                        names.add(s);
                    }
                    mapping.setServletNames(names.toArray(new String[names.size()]));
                }

                EnumSet<DispatcherType> dispatcherSet = EnumSet.noneOf(DispatcherType.class);
                for (DispatcherType d : filterAnnotation.dispatcherTypes())
                {
                    dispatcherSet.add(d);
                }
                mapping.setDispatcherTypes(dispatcherSet);
                _context.getServletHandler().addFilterMapping(mapping);
                metaData.setOrigin(name + ".filter.mappings", filterAnnotation, clazz);
            }
        }
    }
}
