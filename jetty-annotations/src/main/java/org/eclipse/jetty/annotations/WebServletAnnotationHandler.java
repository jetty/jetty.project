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

import javax.servlet.annotation.WebInitParam;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.AnnotationNameValue;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * WebServletAnnotationHandler
 *
 * Process a @WebServlet annotation on a class.
 * 
 * We will create a ServletHolder based on the annotation
 * that may be overridden by the WebXmlConfiguration class
 * when it processes web.xml and the web-fragment.xml files.
 * 
 * TODO, ensure AnnotationConfiguration parses and calls this
 * handler in preConfigure() and the application of the Resource
 * injections and lifecycle callbacks in its configure() method
 * instead.
 */
public class WebServletAnnotationHandler implements AnnotationHandler
{
    protected WebAppContext _wac;
    
    public WebServletAnnotationHandler (WebAppContext wac)
    {
        _wac = wac;
    }
    public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotationName,
                            List<AnnotationNameValue> values)
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

        //Create ServletHolder for the class if one does not exist already
        boolean existed = false;
        ServletHolder[] holders = _wac.getServletHandler().getServlets();
        ServletHolder holder = null;
        for (ServletHolder h : holders)
        {
            if (h.getClassName().equals(clazz.getName()))
            {
                holder = h;
                existed = true;
                break;
            }
        }
        
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
        
        //If the ServletHolder did not already exist, then add it
        if (!existed)
            _wac.getServletHandler().addServlet(holder);
        //Add the mappings for it 
        _wac.getServletHandler().addServletMapping(mapping);
    }

    public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                            List<AnnotationNameValue> values)
    {
        Log.warn ("@WebServlet annotation not supported for fields");
    }

    public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                             List<AnnotationNameValue> values)
    {
        Log.warn ("@WebServlet annotation not supported for methods");
    }    
}
