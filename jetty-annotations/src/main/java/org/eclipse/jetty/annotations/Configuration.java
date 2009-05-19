// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

import java.util.EventListener;
import java.util.List;

import org.eclipse.jetty.plus.servlet.ServletHandler;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * Configuration
 *
 *
 */
public class Configuration extends org.eclipse.jetty.plus.webapp.Configuration
{
    public static final String __web_inf_pattern = "org.eclipse.jetty.server.webapp.WebInfIncludeAnnotationJarPattern";
    public static final String __container_pattern = "org.eclipse.jetty.server.webapp.ContainerIncludeAnnotationJarPattern";
                                                      
    
    
    public Configuration () throws ClassNotFoundException
    {
        super();
    }

    /** 
     * @see org.eclipse.jetty.plus.webapp.AbstractConfiguration#parseAnnotations()
     */
    public void parseAnnotations(final WebAppContext context) throws Exception
    {
        /*
         * TODO Need to also take account of hidden classes on system classpath that should never
         * contribute annotations to a webapp (system and server classes):
         * 
         * --- when scanning system classpath:
         *   + system classes : should always be scanned (subject to pattern)
         *   + server classes : always ignored
         *   
         * --- when scanning webapp classpath:
         *   + system classes : always ignored
         *   + server classes : always scanned
         * 
         * 
         * If same class is found in both container and in context then need to use
         * webappcontext parentloaderpriority to work out which one contributes the
         * annotation.
         */
       
       
        AnnotationFinder finder = new AnnotationFinder();

        //if no pattern for the container path is defined, then by default scan NOTHING
        Log.debug("Scanning system jars");
        finder.find(context.getClassLoader().getParent(), true, context.getInitParameter(__container_pattern), false, 
                new ClassNameResolver ()
                {
                    public boolean isExcluded (String name)
                    {
                        if (context.isSystemClass(name)) return false;
                        if (context.isServerClass(name)) return true;
                        return false;
                    }

                    public boolean shouldOverride (String name)
                    { 
                        //looking at system classpath
                        if (context.isParentLoaderPriority())
                            return true;
                        return false;
                    }
                });

        Log.debug("Scanning WEB-INF/lib jars");
        //if no pattern for web-inf/lib is defined, then by default scan everything in it
        finder.find (context.getClassLoader(), false, context.getInitParameter(__web_inf_pattern), true,
                new ClassNameResolver()
                {
                    public boolean isExcluded (String name)
                    {    
                        if (context.isSystemClass(name)) return true;
                        if (context.isServerClass(name)) return false;
                        return false;
                    }

                    public boolean shouldOverride (String name)
                    {
                        //looking at webapp classpath, found already-parsed class of same name - did it come from system or duplicate in webapp?
                        if (context.isParentLoaderPriority())
                            return false;
                        return true;
                    }
                });       
        
        Log.debug("Scanning classes in WEB-INF/classes");
        finder.find(context.getWebInf().addPath("classes/"), 
                new ClassNameResolver()
                {
                    public boolean isExcluded (String name)
                    {
                        if (context.isSystemClass(name)) return true;
                        if (context.isServerClass(name)) return false;
                        return false;
                    }

                    public boolean shouldOverride (String name)
                    {
                        //looking at webapp classpath, found already-parsed class of same name - did it come from system or duplicate in webapp?
                        if (context.isParentLoaderPriority())
                            return false;
                        return true;
                    }
                });
        
        ServletHandler servletHandler = (ServletHandler)context.getServletHandler();
        List filters = LazyList.array2List(servletHandler.getFilters());
        List filterMappings = LazyList.array2List(servletHandler.getFilterMappings());
        List servlets = LazyList.array2List(servletHandler.getServlets());
        List servletMappings = LazyList.array2List(servletHandler.getServletMappings());
        List listeners = LazyList.array2List(context.getEventListeners());
        
        AnnotationProcessor processor = new AnnotationProcessor(context, finder, _runAsCollection, _injections, _callbacks, 
                servlets, filters,listeners, 
                servletMappings, filterMappings);
        processor.process();
        
        servlets = processor.getServlets();
        filters = processor.getFilters();
        servletMappings = processor.getServletMappings();
        filterMappings = processor.getFilterMappings();
        listeners = processor.getListeners();
        
        servletHandler.setFilters((FilterHolder[])LazyList.toArray(filters,FilterHolder.class));
        servletHandler.setFilterMappings((FilterMapping[])LazyList.toArray(filterMappings,FilterMapping.class));
        servletHandler.setServlets((ServletHolder[])LazyList.toArray(servlets,ServletHolder.class));
        servletHandler.setServletMappings((ServletMapping[])LazyList.toArray(servletMappings,ServletMapping.class));
        context.setEventListeners((EventListener[])LazyList.toArray(listeners,EventListener.class));
    }
}
