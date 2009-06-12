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
import java.util.List;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlProcessor;
import org.eclipse.jetty.webapp.WebXmlProcessor.Descriptor;

/**
 * Configuration
 *
 *
 */
public class AnnotationConfiguration implements Configuration
{
    public static final String CONTAINER_JAR_RESOURCES = WebInfConfiguration.CONTAINER_JAR_RESOURCES;
    public static final String WEB_INF_JAR_RESOURCES = WebInfConfiguration.WEB_INF_JAR_RESOURCES;
                                                      
    
    
    public void preConfigure(final WebAppContext context) throws Exception
    {
    }
    
    
    
    
    public void configure(WebAppContext context) throws Exception
    {
        Boolean metadataComplete = (Boolean)context.getAttribute("metadata-complete");
        if (metadataComplete != null && metadataComplete.booleanValue())
        {
            if (Log.isDebugEnabled()) Log.debug("Not processing annotations for context "+context);
            return;
        }
        
        if (Log.isDebugEnabled()) Log.debug("parsing annotations");
        AnnotationParser parser = new AnnotationParser();
        parser.registerAnnotationHandler("javax.servlet.annotation.WebServlet", new WebServletAnnotationHandler(context));
        parser.registerAnnotationHandler("javax.servlet.annotation.WebFilter", new WebFilterAnnotationHandler(context));
        parser.registerAnnotationHandler("javax.servlet.annotation.WebListener", new WebListenerAnnotationHandler(context));
        parser.registerAnnotationHandler("javax.servlet.annotation.MultipartConfig", new MultipartConfigAnnotationHandler (context));
        parser.registerAnnotationHandler("javax.annotation.Resource", new ResourceAnnotationHandler (context));
        parser.registerAnnotationHandler("javax.annotation.Resources", new ResourcesAnnotationHandler (context));
        parser.registerAnnotationHandler("javax.annotation.PostConstruct", new PostConstructAnnotationHandler(context));
        parser.registerAnnotationHandler("javax.annotation.PreDestroy", new PreDestroyAnnotationHandler(context));
        parser.registerAnnotationHandler("javax.annotation.security.RunAs", new RunAsAnnotationHandler(context));
        
        parseContainerPath(context, parser);
        parseWebInfLib (context, parser);
        parseWebInfClasses(context, parser);
    }



    public void deconfigure(WebAppContext context) throws Exception
    {
    }




    public void postConfigure(WebAppContext context) throws Exception
    {
    }




    public void parseContainerPath (final WebAppContext context, final AnnotationParser parser)
    throws Exception
    {
        //if no pattern for the container path is defined, then by default scan NOTHING
        Log.debug("Scanning container jars");
           
        //Convert from Resource to URI
        ArrayList<URI> containerUris = new ArrayList<URI>();
        List<Resource> jarResources = (List<Resource>)context.getAttribute(CONTAINER_JAR_RESOURCES);
        for (Resource r : jarResources)
        {
            URI uri = r.getURI();
                containerUris.add(uri);          
        }
        
        parser.parse (containerUris.toArray(new URI[containerUris.size()]),
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
    
    
    public void parseWebInfLib (final WebAppContext context, final AnnotationParser parser)
    throws Exception
    {  
        WebXmlProcessor webXmlProcessor = (WebXmlProcessor)context.getAttribute(WebXmlProcessor.WEB_PROCESSOR); 
        if (webXmlProcessor == null)
           throw new IllegalStateException ("No processor for web xml");
        
        List<Descriptor> frags = webXmlProcessor.getFragments();
        
        //Get the web-inf lib jars who have a web-fragment.xml that is not metadata-complete (or is not set)
        ArrayList<URI> webInfUris = new ArrayList<URI>();
        List<Resource> jarResources = (List<Resource>)context.getAttribute(WEB_INF_JAR_RESOURCES);
        
        for (Resource r : jarResources)
        {          
            URI uri  = r.getURI();
            Descriptor d = null;
            for (Descriptor frag: frags)
            {
                Resource fragResource = frag.getResource(); //eg jar:file:///a/b/c/foo.jar!/META-INF/web-fragment.xml
                if (Resource.isContainedIn(fragResource,r))
                {
                    d = frag;
                    break;
                }
            }

            //if there was no web-fragment.xml for the jar, or there was one 
            //and its metadata is NOT complete, we want to exame it for annotations
            if (d == null || (d != null && !d.isMetaDataComplete()))
                webInfUris.add(uri);
        }
 
       parser.parse(webInfUris.toArray(new URI[webInfUris.size()]), 
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
     
    public void parseWebInfClasses (final WebAppContext context, final AnnotationParser parser)
    throws Exception
    {
        Log.debug("Scanning classes in WEB-INF/classes");
        parser.parse(context.getWebInf().addPath("classes/"), 
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

}
