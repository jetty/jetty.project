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

import org.eclipse.jetty.plus.annotation.InjectionCollection;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.plus.annotation.RunAsCollection;
import org.eclipse.jetty.plus.servlet.ServletHandler;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * Configuration
 *
 *
 */
public class AnnotationConfiguration extends org.eclipse.jetty.plus.webapp.Configuration
{
    public static final String __web_inf_pattern = "org.eclipse.jetty.server.webapp.WebInfIncludeAnnotationJarPattern";
    public static final String __container_pattern = "org.eclipse.jetty.server.webapp.ContainerIncludeAnnotationJarPattern";
                                                      
    
    
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

        //TODO change for servlet spec 3
        parseContainerPath (context, finder);
        parseWebInfLib (context, finder);
        parseWebInfClasses (context, finder);

        AnnotationProcessor processor = new AnnotationProcessor(context, finder);
        processor.process();
        
        List servlets = processor.getServlets();
        List filters = processor.getFilters();
        List servletMappings = processor.getServletMappings();
        List filterMappings = processor.getFilterMappings();
        List listeners = processor.getListeners();
        
        ServletHandler servletHandler = (ServletHandler)context.getServletHandler();
        servletHandler.setFilters((FilterHolder[])LazyList.toArray(filters,FilterHolder.class));
        servletHandler.setFilterMappings((FilterMapping[])LazyList.toArray(filterMappings,FilterMapping.class));
        servletHandler.setServlets((ServletHolder[])LazyList.toArray(servlets,ServletHolder.class));
        servletHandler.setServletMappings((ServletMapping[])LazyList.toArray(servletMappings,ServletMapping.class));
        context.setEventListeners((EventListener[])LazyList.toArray(listeners,EventListener.class));
    }
    
    public void parseContainerPath (final WebAppContext context, final AnnotationFinder finder)
    throws Exception
    {
        //if no pattern for the container path is defined, then by default scan NOTHING
        Log.debug("Scanning container jars");
        parseAnnotationsFromJars(context, finder, context.getClassLoader().getParent(), context.getInitParameter(__container_pattern),true,  false, 
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
    }
    
    
    public void parseWebInfLib (final WebAppContext context, final AnnotationFinder finder)
    throws Exception
    {
        Log.debug("Scanning WEB-INF/lib jars");
        //if no pattern for web-inf/lib is defined, then by default scan everything in it
        parseAnnotationsFromJars (context, finder, context.getClassLoader(), context.getInitParameter(__web_inf_pattern), false, true,
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
                
    }
     
    public void parseWebInfClasses (final WebAppContext context, final AnnotationFinder finder)
    throws Exception
    {
        Log.debug("Scanning classes in WEB-INF/classes");
        parseAnnotationsFromDir (context, finder, context.getWebInf().addPath("classes/"), 
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
    }
    
    

    public void parseAnnotationsFromJars (final WebAppContext context, final AnnotationFinder finder, final ClassLoader classloader, final String pattern, boolean visitParents, boolean isNullInclusive, ClassNameResolver resolver)
    throws Exception
    {
        finder.find (classloader, visitParents, pattern, isNullInclusive,resolver);
    }
    
    public void parseAnnotationsFromDir (final WebAppContext context, final AnnotationFinder finder, final Resource dir, ClassNameResolver resolver)
    throws Exception
    {
        finder.find(dir, resolver);   
    }
}
