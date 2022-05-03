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

package org.eclipse.jetty.annotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.Servlet;
import jakarta.servlet.annotation.WebInitParam;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.servlet.Source;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.DiscoveredAnnotation;
import org.eclipse.jetty.webapp.MetaData;
import org.eclipse.jetty.webapp.Origin;
import org.eclipse.jetty.webapp.WebAppContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebServletAnnotation
 */
public class WebServletAnnotation extends DiscoveredAnnotation
{
    private static final Logger LOG = LoggerFactory.getLogger(WebServletAnnotation.class);

    public WebServletAnnotation(WebAppContext context, String className)
    {
        super(context, className);
    }

    public WebServletAnnotation(WebAppContext context, String className, Resource resource)
    {
        super(context, className, resource);
    }

    @Override
    public void apply()
    {
        //TODO check this algorithm with new rules for applying descriptors and annotations in order
        Class<? extends Servlet> clazz = (Class<? extends Servlet>)getTargetClass();

        if (clazz == null)
        {
            LOG.warn("{} cannot be loaded", _className);
            return;
        }

        //Servlet Spec 8.1.1
        if (!HttpServlet.class.isAssignableFrom(clazz))
        {
            LOG.warn("{} is not assignable from jakarta.servlet.http.HttpServlet", clazz.getName());
            return;
        }

        WebServlet annotation = (WebServlet)clazz.getAnnotation(WebServlet.class);

        if (annotation.urlPatterns().length > 0 && annotation.value().length > 0)
        {
            LOG.warn("{} defines both @WebServlet.value and @WebServlet.urlPatterns", clazz.getName());
            return;
        }

        String[] urlPatterns = annotation.value();
        if (urlPatterns.length == 0)
            urlPatterns = annotation.urlPatterns();

        if (urlPatterns.length == 0)
        {
            LOG.warn("{} defines neither @WebServlet.value nor @WebServlet.urlPatterns", clazz.getName());
            return;
        }

        //canonicalize the patterns
        ArrayList<String> urlPatternList = new ArrayList<String>();
        for (String p : urlPatterns)
        {
            urlPatternList.add(ServletPathSpec.normalize(p));
        }

        String servletName = (annotation.name().equals("") ? clazz.getName() : annotation.name());

        MetaData metaData = _context.getMetaData();
        ServletMapping mapping = null; //the new mapping

        //Find out if a <servlet> already exists with this name
        ServletHolder[] holders = _context.getServletHandler().getServlets();

        ServletHolder holder = null;
        if (holders != null)
        {
            for (ServletHolder h : holders)
            {
                if (h.getName() != null && servletName.equals(h.getName()))
                {
                    holder = h;
                    break;
                }
            }
        }

        //handle creation/completion of a servlet
        if (holder == null)
        {
            //No servlet of this name has already been defined, either by a descriptor
            //or another annotation (which would be impossible).
            Source source = new Source(Source.Origin.ANNOTATION, clazz.getName());

            holder = _context.getServletHandler().newServletHolder(source);
            holder.setHeldClass(clazz);
            metaData.setOrigin(servletName + ".servlet.servlet-class", annotation, clazz);

            holder.setName(servletName);
            holder.setDisplayName(annotation.displayName());
            metaData.setOrigin(servletName + ".servlet.display-name", annotation, clazz);

            holder.setInitOrder(annotation.loadOnStartup());
            metaData.setOrigin(servletName + ".servlet.load-on-startup", annotation, clazz);

            holder.setAsyncSupported(annotation.asyncSupported());
            metaData.setOrigin(servletName + ".servlet.async-supported", annotation, clazz);

            for (WebInitParam ip : annotation.initParams())
            {
                holder.setInitParameter(ip.name(), ip.value());
                metaData.setOrigin(servletName + ".servlet.init-param." + ip.name(), ip, clazz);
            }

            _context.getServletHandler().addServlet(holder);

            mapping = new ServletMapping(source);
            mapping.setServletName(holder.getName());
            mapping.setPathSpecs(LazyList.toStringArray(urlPatternList));
            _context.getMetaData().setOrigin(servletName + ".servlet.mapping." + Long.toHexString(mapping.hashCode()), annotation, clazz);
        }
        else
        {
            //set the class according to the servlet that is annotated, if it wasn't already
            //NOTE: this may be considered as "completing" an incomplete servlet registration, and it is
            //not clear from servlet 3.0 spec whether this is intended, or if only a ServletContext.addServlet() call
            //can complete it, see http://java.net/jira/browse/SERVLET_SPEC-42
            if (holder.getClassName() == null)
                holder.setClassName(clazz.getName());
            if (holder.getHeldClass() == null)
                holder.setHeldClass(clazz);

            //check if the existing servlet has each init-param from the annotation
            //if not, add it
            for (WebInitParam ip : annotation.initParams())
            {
                if (metaData.getOrigin(servletName + ".servlet.init-param." + ip.name()) == Origin.NotSet)
                {
                    holder.setInitParameter(ip.name(), ip.value());
                    metaData.setOrigin(servletName + ".servlet.init-param." + ip.name(), ip, clazz);
                }
            }

            //check the url-patterns
            //ServletSpec 3.0 p81 If a servlet already has url mappings from a
            //webxml or fragment descriptor the annotation is ignored.
            //However, we want to be able to replace mappings that were given in webdefault.xml
            List<ServletMapping> existingMappings = getServletMappingsForServlet(servletName);

            //if any mappings for this servlet already set by a descriptor that is not webdefault.xml forget
            //about processing these url mappings
            if (existingMappings.isEmpty() || !containsNonDefaultMappings(existingMappings))
            {
                mapping = new ServletMapping(new Source(Source.Origin.ANNOTATION, clazz.getName()));
                mapping.setServletName(servletName);
                mapping.setPathSpecs(LazyList.toStringArray(urlPatternList));
                _context.getMetaData().setOrigin(servletName + ".servlet.mapping." + Long.toHexString(mapping.hashCode()), annotation, clazz);
            }
        }

        //We also want to be able to replace mappings that were defined in webdefault.xml
        //that were for a different servlet eg a mapping in webdefault.xml for / to the jetty
        //default servlet should be able to be replaced by an annotation for / to a different
        //servlet
        if (mapping != null)
        {
            //url mapping was permitted by annotation processing rules

            //take a copy of the existing servlet mappings that we can iterate over and remove from. This is
            //because the ServletHandler interface does not support removal of individual mappings.
            List<ServletMapping> allMappings = ArrayUtil.asMutableList(_context.getServletHandler().getServletMappings());

            //for each of the urls in the annotation, check if a mapping to same/different servlet exists
            //  if mapping exists and is from a default descriptor, it can be replaced. NOTE: we do not
            //  guard against duplicate path mapping here: that is the job of the ServletHandler
            for (String p : urlPatternList)
            {
                ServletMapping existingMapping = _context.getServletHandler().getServletMapping(p);
                if (existingMapping != null && existingMapping.isFromDefaultDescriptor())
                {
                    String[] updatedPaths = ArrayUtil.removeFromArray(existingMapping.getPathSpecs(), p);
                    //if we removed the last path from a servletmapping, delete the servletmapping
                    if (updatedPaths == null || updatedPaths.length == 0)
                    {
                        boolean success = allMappings.remove(existingMapping);
                        if (LOG.isDebugEnabled())
                            LOG.debug("Removed empty mapping {} from defaults descriptor success:{}", existingMapping, success);
                    }
                    else
                    {
                        existingMapping.setPathSpecs(updatedPaths);
                        if (LOG.isDebugEnabled())
                            LOG.debug("Removed path {} from mapping {} from defaults descriptor ", p, existingMapping);
                    }
                }
                _context.getMetaData().setOrigin(servletName + ".servlet.mapping.url" + p, annotation, clazz);
            }
            allMappings.add(mapping);
            _context.getServletHandler().setServletMappings(allMappings.toArray(new ServletMapping[allMappings.size()]));
        }
    }

    /**
     *
     */
    private List<ServletMapping> getServletMappingsForServlet(String name)
    {
        ServletMapping[] allMappings = _context.getServletHandler().getServletMappings();
        if (allMappings == null)
            return Collections.emptyList();

        List<ServletMapping> mappings = new ArrayList<ServletMapping>();
        for (ServletMapping m : allMappings)
        {
            if (m.getServletName() != null && name.equals(m.getServletName()))
            {
                mappings.add(m);
            }
        }
        return mappings;
    }

    /**
     *
     */
    private boolean containsNonDefaultMappings(List<ServletMapping> mappings)
    {
        if (mappings == null)
            return false;
        for (ServletMapping m : mappings)
        {
            if (!m.isFromDefaultDescriptor())
                return true;
        }
        return false;
    }
}
