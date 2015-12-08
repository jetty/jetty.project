//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.annotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.servlet.Servlet;
import javax.servlet.annotation.WebInitParam;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.servlet.Holder;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.DiscoveredAnnotation;
import org.eclipse.jetty.webapp.MetaData;
import org.eclipse.jetty.webapp.Origin;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * WebServletAnnotation
 *
 *
 */
public class WebServletAnnotation extends DiscoveredAnnotation
{
    private static final Logger LOG = Log.getLogger(WebServletAnnotation.class);

    public WebServletAnnotation (WebAppContext context, String className)
    {
        super(context, className);
    }


    public WebServletAnnotation (WebAppContext context, String className, Resource resource)
    {
        super(context, className, resource);
    }

    /**
     * @see DiscoveredAnnotation#apply()
     */
    public void apply()
    {
        //TODO check this algorithm with new rules for applying descriptors and annotations in order
        Class<? extends Servlet> clazz = (Class<? extends Servlet>)getTargetClass();

        if (clazz == null)
        {
            LOG.warn(_className+" cannot be loaded");
            return;
        }

        //Servlet Spec 8.1.1
        if (!HttpServlet.class.isAssignableFrom(clazz))
        {
            LOG.warn(clazz.getName()+" is not assignable from javax.servlet.http.HttpServlet");
            return;
        }

        WebServlet annotation = (WebServlet)clazz.getAnnotation(WebServlet.class);

        if (annotation.urlPatterns().length > 0 && annotation.value().length > 0)
        {
            LOG.warn(clazz.getName()+ " defines both @WebServlet.value and @WebServlet.urlPatterns");
            return;
        }

        String[] urlPatterns = annotation.value();
        if (urlPatterns.length == 0)
            urlPatterns = annotation.urlPatterns();

        if (urlPatterns.length == 0)
        {
            LOG.warn(clazz.getName()+ " defines neither @WebServlet.value nor @WebServlet.urlPatterns");
            return;
        }

        //canonicalize the patterns
        ArrayList<String> urlPatternList = new ArrayList<String>();
        for (String p : urlPatterns)
            urlPatternList.add(Util.normalizePattern(p));

        String servletName = (annotation.name().equals("")?clazz.getName():annotation.name());

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
            holder = _context.getServletHandler().newServletHolder(Holder.Source.ANNOTATION);
            holder.setHeldClass(clazz);
            metaData.setOrigin(servletName+".servlet.servlet-class",annotation,clazz);

            holder.setName(servletName);
            holder.setDisplayName(annotation.displayName());
            metaData.setOrigin(servletName+".servlet.display-name",annotation,clazz);

            holder.setInitOrder(annotation.loadOnStartup());
            metaData.setOrigin(servletName+".servlet.load-on-startup",annotation,clazz);

            holder.setAsyncSupported(annotation.asyncSupported());
            metaData.setOrigin(servletName+".servlet.async-supported",annotation,clazz);

            for (WebInitParam ip:annotation.initParams())
            {
                holder.setInitParameter(ip.name(), ip.value());
                metaData.setOrigin(servletName+".servlet.init-param."+ip.name(),ip,clazz);
            }

            _context.getServletHandler().addServlet(holder);


            mapping = new ServletMapping();
            mapping.setServletName(holder.getName());
            mapping.setPathSpecs( LazyList.toStringArray(urlPatternList));
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
            for (WebInitParam ip:annotation.initParams())
            {
                if (metaData.getOrigin(servletName+".servlet.init-param."+ip.name())==Origin.NotSet)
                {
                    holder.setInitParameter(ip.name(), ip.value());
                    metaData.setOrigin(servletName+".servlet.init-param."+ip.name(),ip,clazz);
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
                mapping = new ServletMapping();
                mapping.setServletName(servletName);
                mapping.setPathSpecs(LazyList.toStringArray(urlPatternList));
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
            for (String p:urlPatternList)
            {
                ServletMapping existingMapping = _context.getServletHandler().getServletMapping(p);
                if (existingMapping != null && existingMapping.isDefault())
                {
                    String[] updatedPaths = ArrayUtil.removeFromArray(existingMapping.getPathSpecs(), p);
                    //if we removed the last path from a servletmapping, delete the servletmapping
                    if (updatedPaths == null || updatedPaths.length == 0)
                    {
                        boolean success = allMappings.remove(existingMapping);
                        if (LOG.isDebugEnabled()) LOG.debug("Removed empty mapping {} from defaults descriptor success:{}",existingMapping, success);
                    }
                    else
                    {
                        existingMapping.setPathSpecs(updatedPaths);
                        if (LOG.isDebugEnabled()) LOG.debug("Removed path {} from mapping {} from defaults descriptor ", p,existingMapping);
                    }
                }
                _context.getMetaData().setOrigin(servletName+".servlet.mapping."+p, annotation, clazz);
            }
            allMappings.add(mapping);
            _context.getServletHandler().setServletMappings(allMappings.toArray(new ServletMapping[allMappings.size()]));
        }
    }




    /**
     * @param name
     * @return
     */
    private List<ServletMapping>  getServletMappingsForServlet (String name)
    {
        ServletMapping[] allMappings = _context.getServletHandler().getServletMappings();
        if (allMappings == null)
            return Collections.emptyList();

        List<ServletMapping> mappings = new ArrayList<ServletMapping>();
        for (ServletMapping m:allMappings)
        {
            if (m.getServletName() != null && name.equals(m.getServletName()))
            {
                mappings.add(m);
            }
        }
        return mappings;
    }


    /**
     * @param mappings
     * @return
     */
    private boolean containsNonDefaultMappings (List<ServletMapping> mappings)
    {
        if (mappings == null)
            return false;
        for (ServletMapping m:mappings)
        {
            if (!m.isDefault())
                return true;
        }
        return false;
    }
}
