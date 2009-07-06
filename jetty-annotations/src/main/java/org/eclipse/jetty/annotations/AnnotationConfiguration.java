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

import org.eclipse.jetty.util.Loader;
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
public class AnnotationConfiguration extends AbstractConfiguration
{
                                                      
    
    
    public void preConfigure(final WebAppContext context) throws Exception
    {
    }
    
    
    
    
    public void configure(WebAppContext context) throws Exception
    {
       Boolean b = (Boolean)context.getAttribute(METADATA_COMPLETE);
       boolean metadataComplete = (b != null && b.booleanValue());
       Integer i = (Integer)context.getAttribute(WEBXML_VERSION);
       int webxmlVersion = (i == null? 0 : i.intValue());
      
        if (metadataComplete)
        {
            //Never scan any jars or classes for annotations if metadata is complete
            if (Log.isDebugEnabled()) Log.debug("Metadata-complete==true,  not processing annotations for context "+context);
            return;
        }
        else 
        {
            //Only scan jars and classes if metadata is not complete and the web app is version 3.0, or
            //a 2.5 version webapp that has specifically asked to discover annotations
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

            if (webxmlVersion >= 30 || context.isConfigurationDiscovered())
            {
                System.err.println("SCANNING ALL ANNOTATIONS: webxmlVersion="+webxmlVersion+" configurationDiscovered="+context.isConfigurationDiscovered());
                parseContainerPath(context, parser);
                parseWebInfLib (context, parser);
                parseWebInfClasses(context, parser);
            } 
            else
            {
                System.err.println("SCANNING ONLY WEB.XML ANNOTATIONS");
                parse25Classes(context, parser);
            }
        }    
    }



    public void deconfigure(WebAppContext context) throws Exception
    {
    }




    public void postConfigure(WebAppContext context) throws Exception
    {
    }
}
