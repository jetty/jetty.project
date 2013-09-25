//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import javax.servlet.annotation.WebInitParam;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.servlet.Holder;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
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
        Class clazz = getTargetClass();

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

        //Find out if a <servlet> already exists with this name
        ServletHolder[] holders = _context.getServletHandler().getServlets();
        boolean isNew = true;
        ServletHolder holder = null;
        if (holders != null)
        {
            for (ServletHolder h : holders)
            {
                if (h.getName() != null && servletName.equals(h.getName()))
                {
                    holder = h;
                    isNew = false;
                    break;
                }
            }
        }

        if (isNew)
        {
            //No servlet of this name has already been defined, either by a descriptor
            //or another annotation (which would be impossible).
            holder = _context.getServletHandler().newServletHolder(Holder.Source.ANNOTATION);
            holder.setHeldClass(clazz);
            metaData.setOrigin(servletName+".servlet.servlet-class");

            holder.setName(servletName);
            holder.setDisplayName(annotation.displayName());
            metaData.setOrigin(servletName+".servlet.display-name");

            holder.setInitOrder(annotation.loadOnStartup());
            metaData.setOrigin(servletName+".servlet.load-on-startup");

            holder.setAsyncSupported(annotation.asyncSupported());
            metaData.setOrigin(servletName+".servlet.async-supported");

            for (WebInitParam ip:annotation.initParams())
            {
                holder.setInitParameter(ip.name(), ip.value());
                metaData.setOrigin(servletName+".servlet.init-param."+ip.name());
            }

            _context.getServletHandler().addServlet(holder);
            ServletMapping mapping = new ServletMapping();
            mapping.setServletName(holder.getName());
            mapping.setPathSpecs( LazyList.toStringArray(urlPatternList));
            _context.getServletHandler().addServletMapping(mapping);
            metaData.setOrigin(servletName+".servlet.mappings");
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
                    metaData.setOrigin(servletName+".servlet.init-param."+ip.name());
                }
            }

            //check the url-patterns
            //ServletSpec 3.0 p81 If a servlet already has url mappings from a
            //webxml or fragment descriptor the annotation is ignored. However, we want to be able to
            //replace mappings that were given in webdefault.xml
            boolean mappingsExist = false;
            boolean anyNonDefaults = false;
            ServletMapping[] allMappings = _context.getServletHandler().getServletMappings();
            if (allMappings != null)
            {
                for (ServletMapping m:allMappings)
                {
                    if (m.getServletName() != null && servletName.equals(m.getServletName()))
                    {
                        mappingsExist = true;
                        if (!m.isDefault())
                        {
                            anyNonDefaults = true;
                            break;
                        }
                    }
                }
            }

            if (anyNonDefaults)
                return;  //if any mappings already set by a descriptor that is not webdefault.xml, we're done

            boolean clash = false;
            if (mappingsExist)
            {
                for (String p:urlPatternList)
                {
                    ServletMapping m = _context.getServletHandler().getServletMapping(p);
                    if (m != null && !m.isDefault())
                    {
                        //trying to override a servlet-mapping that was added not by webdefault.xml
                        clash = true;
                        break;
                    }
                }
            }

            if (!mappingsExist || !clash)
            {
                ServletMapping m = new ServletMapping();
                m.setServletName(servletName);
                m.setPathSpecs(LazyList.toStringArray(urlPatternList));
                _context.getServletHandler().addServletMapping(m);
            }
        }
    }
}
