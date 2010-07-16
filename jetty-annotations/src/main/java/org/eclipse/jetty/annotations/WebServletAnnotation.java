// ========================================================================
// Copyright (c) 2010 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.webapp.DiscoveredAnnotation;
import org.eclipse.jetty.webapp.MetaData;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.MetaData.Origin;

/**
 * WebServletAnnotation
 *
 *
 */
public class WebServletAnnotation extends DiscoveredAnnotation
{
    
    public WebServletAnnotation (WebAppContext context, String className)
    {
        super(context, className);
    }

    /** 
     * @see org.eclipse.jetty.annotations.ClassAnnotation#apply()
     */
    public void apply()
    {
        //TODO check this algorithm with new rules for applying descriptors and annotations in order
        Class clazz = getTargetClass();
        
        if (clazz == null)
        {
            Log.warn(_className+" cannot be loaded");
            return;
        }
        
        //Servlet Spec 8.1.1
        if (!HttpServlet.class.isAssignableFrom(clazz))
        {
            Log.warn(clazz.getName()+" is not assignable from javax.servlet.http.HttpServlet");
            return;
        }
        
        WebServlet annotation = (WebServlet)clazz.getAnnotation(WebServlet.class);
        
        if (annotation.urlPatterns().length > 0 && annotation.value().length > 0)
        {
            Log.warn(clazz.getName()+ " defines both @WebServlet.value and @WebServlet.urlPatterns");
            return;
        }
        
        String[] urlPatterns = annotation.value();
        if (urlPatterns.length == 0)
            urlPatterns = annotation.urlPatterns();
        
        if (urlPatterns.length == 0)
        {
            Log.warn(clazz.getName()+ " defines neither @WebServlet.value nor @WebServlet.urlPatterns");
            return;
        }
        
        //canonicalize the patterns
        ArrayList<String> urlPatternList = new ArrayList<String>();
        for (String p : urlPatterns)
            urlPatternList.add(Util.normalizePattern(p));
        
        String servletName = (annotation.name().equals("")?clazz.getName():annotation.name());
        
        MetaData metaData = _context.getMetaData();

        //Find out if a <servlet>  of this type already exists with this name
        ServletHolder[] holders = _context.getServletHandler().getServlets();
        boolean isNew = true;
        ServletHolder holder = null;
        if (holders != null)
        {
            for (ServletHolder h : holders)
            {
                if (h.getClassName().equals(clazz.getName()) && h.getName().equals(servletName))
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
            //check if the existing servlet has each init-param from the annotation
            //if not, add it
            for (WebInitParam ip:annotation.initParams())
            {
              //if (holder.getInitParameter(ip.name()) == null)
                if (metaData.getOrigin(servletName+".servlet.init-param"+ip.name())==Origin.NotSet)
                {
                    holder.setInitParameter(ip.name(), ip.value());
                    metaData.setOrigin(servletName+".servlet.init-param."+ip.name());
                }  
            }
            
            //check the url-patterns, if there annotation has a new one, add it
            ServletMapping[] mappings = _context.getServletHandler().getServletMappings();

            //ServletSpec 3.0 p81 If a servlet already has url mappings from a 
            //descriptor the annotation is ignored
            if (mappings == null && metaData.getOriginDescriptor(servletName+".servlet.mappings") != null)
            {
                ServletMapping mapping = new ServletMapping();
                mapping.setServletName(servletName);
                mapping.setPathSpecs(LazyList.toStringArray(urlPatternList));
                _context.getServletHandler().addServletMapping(mapping); 
            }
        }
    }   
}
