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

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;

import org.eclipse.jetty.plus.servlet.ServletHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;

/**
 * Configuration
 *
 *
 */
public class AnnotationConfiguration extends org.eclipse.jetty.plus.webapp.Configuration
{
    public static final String JAR_RESOURCES = WebInfConfiguration.JAR_RESOURCES;
                                                      
    
    
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
        
        //Get the container jar uris
        
        ArrayList<URI> containerCandidateUris = findJars (context.getClassLoader().getParent(), true);
        
        //Pick out the uris from JAR_RESOURCES that match those uris to be scanned
        ArrayList<URI> containerUris = new ArrayList<URI>();
        List<Resource> jarResources = (List<Resource>)context.getAttribute(JAR_RESOURCES);
        for (Resource r : jarResources)
        {
            URI uri = r.getURI();
            if (containerCandidateUris.contains(uri))
            {
                containerUris.add(uri);
            }
               
        }
        
        finder.find (containerUris.toArray(new URI[containerUris.size()]),
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
        //Get the uris of jars on the webapp classloader
        ArrayList<URI> candidateUris = findJars(context.getClassLoader(), false);
        
        //Pick out the uris from JAR_RESOURCES that match those to be scanned
        ArrayList<URI> webInfUris = new ArrayList<URI>();
        List<Resource> jarResources = (List<Resource>)context.getAttribute(JAR_RESOURCES);
        for (Resource r : jarResources)
        {
            URI uri = r.getURI();
            if (candidateUris.contains(uri))
            {
                webInfUris.add(uri);
            }
        }
        
        //if no pattern for web-inf/lib is defined, then by default scan everything in it
       finder.find(webInfUris.toArray(new URI[webInfUris.size()]), 
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
    }

    

    public ArrayList<URI> findJars (ClassLoader loader, boolean visitParent)
    {
        ArrayList<URI> uris = new ArrayList<URI>();
       
        while (loader != null && (loader instanceof URLClassLoader))
        {
            URL[] urls = ((URLClassLoader)loader).getURLs();
            if (urls != null)
            {
                for (URL u : urls)
                {
                    try
                    {
                        uris.add(u.toURI());
                    }
                    catch (Exception e)
                    {
                        Log.warn(e);
                    }
                } 
            }
            if (visitParent)
                loader = loader.getParent();
            else
                loader = null;
        }
        return uris;
    }
}
