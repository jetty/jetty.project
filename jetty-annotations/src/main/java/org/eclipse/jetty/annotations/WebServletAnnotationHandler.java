// ========================================================================
// Copyright (c) 2009 Mort Bay Consulting Pty. Ltd.
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
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;

import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebInitParam;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.annotations.AnnotationParser.DiscoverableAnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.Holder;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * WebServletAnnotationHandler
 *
 * Process a WebServlet annotation on a class.
 * 
 */
public class WebServletAnnotationHandler implements DiscoverableAnnotationHandler
{
    protected WebAppContext _wac;
    
    public WebServletAnnotationHandler (WebAppContext wac)
    {
        _wac = wac;
    }
    
    
    
    /** 
     * Handle a WebServlet annotation.
     * 
     * If web.xml does not define a servlet of the same name, then this is an entirely
     * new servlet definition.
     * 
     * Otherwise, the values from web.xml override the values from the annotation except for:
     * 
     * <ul>
     * <li>init-params: if the annotation contains a different init-param name, then it is added to the
     *     effective init-params for the servlet<li>
     * <li>url-patterns: if the annotation contains a different url-pattern, then it is added to the
     *     effective url-patterns for the servlet</li>
     * </ul>
     *  
     * @see org.eclipse.jetty.annotations.AnnotationParser.DiscoverableAnnotationHandler#handleClass(java.lang.String, int, int, java.lang.String, java.lang.String, java.lang.String[], java.lang.String, java.util.List)
     */
    public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotationName,
                            List<Value> values)
    {
        if (!"javax.servlet.annotation.WebServlet".equals(annotationName))
            return;    
       
        //TODO Do we want to load the class now and look at the annotation values
        //with reflection, or do we want to use the raw annotation values from 
        //asm parsing?
        Class clazz;
        try
        {
            clazz = Loader.loadClass(null, className);
        }
        catch (Exception e)
        {
            Log.warn(e);
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

        //Find out if a <servlet> from web.xml of this type already exists with this name
        ServletHolder[] holders = _wac.getServletHandler().getServlets();
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
            holder = _wac.getServletHandler().newServletHolder(Holder.Source.ANNOTATION);
            holder.setHeldClass(clazz);   
            holder.setName(servletName);
            holder.setDisplayName(annotation.displayName());
            holder.setInitOrder(annotation.loadOnStartup());
            holder.setAsyncSupported(annotation.asyncSupported());
            for (WebInitParam ip:annotation.initParams())
            {
                holder.setInitParameter(ip.name(), ip.value());
            }
          
            _wac.getServletHandler().addServlet(holder);
            ServletMapping mapping = new ServletMapping();  
            mapping.setServletName(holder.getName());
            mapping.setPathSpecs( LazyList.toStringArray(urlPatternList));
            _wac.getServletHandler().addServletMapping(mapping);
        }
        else
        {
            //check if the existing servlet has each init-param from the annotation
            //if not, add it
            for (WebInitParam ip:annotation.initParams())
            {
                if (holder.getInitParameter(ip.name()) == null)
                    holder.setInitParameter(ip.name(), ip.value());
            }
            
            //check the url-patterns, if there annotation has a new one, add it
            ServletMapping[] mappings = _wac.getServletHandler().getServletMappings();
            
            //find which patterns aren't already in web.xml
            if (mappings != null)
            {
                for (ServletMapping mapping : mappings)
                {   
                    List<String> specs = LazyList.array2List(mapping.getPathSpecs());
                    urlPatternList.removeAll(specs);
                }
            }
            //add the remaining new ones in as mapping patterns
            ServletMapping mapping = new ServletMapping();
            mapping.setServletName(servletName);
            mapping.setPathSpecs(LazyList.toStringArray(urlPatternList));
            _wac.getServletHandler().addServletMapping(mapping); 
        }
    }

    public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                            List<Value> values)
    {
        Log.warn ("@WebServlet annotation not supported for fields");
    }

    public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                             List<Value> values)
    {
        Log.warn ("@WebServlet annotation not supported for methods");
    }    
}
