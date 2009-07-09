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
import java.util.List;

import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebInitParam;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * WebServletAnnotationHandler
 *
 * Process a WebServlet annotation on a class.
 * 
 */
public class WebServletAnnotationHandler implements AnnotationHandler
{
    protected WebAppContext _wac;
    
    public WebServletAnnotationHandler (WebAppContext wac)
    {
        _wac = wac;
    }
    
    
    
    /** 
     * TODO: ensure that web.xml takes precedence:
     *  - if servlet of same name and same class exists in web.xml, then values from annotation cannot override it?
     *  
     *  - if servlet is different name NEED CLARIFICATION FROM JSR. For now, we assume it is a different servlet.
     *  
     * @see org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler#handleClass(java.lang.String, int, int, java.lang.String, java.lang.String, java.lang.String[], java.lang.String, java.util.List)
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

        String servletName = (annotation.name().equals("")?clazz.getName():annotation.name());

        //Find out if a <servlet> from web.xml of this type already exists with this name
        ServletHolder[] holders = _wac.getServletHandler().getServlets();
        ServletHolder holder = null;
        for (ServletHolder h : holders)
        {
            if (h.getClassName().equals(clazz.getName()) && h.getName().equals(servletName))
            {
                holder = h;
                break;
            }
        }
        
        //A <servlet> with matching name already exists in web.xml, ignore
        //the annotation
        //TODO - Get confirmation from JSR!
        if (holder != null)
            return;
        
        // If no <servlet> with matching name was found, we need to make one
        if (holder == null)
            holder = new ServletHolder(clazz);
        
        holder.setName(servletName);
        holder.setInitOrder(annotation.loadOnStartup());

        for (WebInitParam ip:annotation.initParams())
        {
            holder.setInitParameter(ip.name(), ip.value());
        }

        ArrayList paths = new ArrayList();
        ServletMapping mapping = new ServletMapping();
        mapping.setServletName(holder.getName());
        for (String s:urlPatterns)
        {    
            paths.add(Util.normalizePattern(s)); 
        }
        mapping.setPathSpecs((String[])paths.toArray(new String[paths.size()]));     
        holder.setAsyncSupported(annotation.asyncSupported());
        
        //MultipartConfig annotation handled separately
        MultipartConfig multipart = (MultipartConfig)clazz.getAnnotation(MultipartConfig.class);
        if (multipart != null)
        {
            //TODO handle in here
        }
        
        //If the ServletHolder did not already exist, then add it
         _wac.getServletHandler().addServlet(holder);
        //Add the mappings for it 
        _wac.getServletHandler().addServletMapping(mapping);
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
