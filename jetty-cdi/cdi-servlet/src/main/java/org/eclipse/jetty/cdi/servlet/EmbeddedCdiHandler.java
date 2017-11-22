//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.cdi.servlet;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.jboss.weld.environment.servlet.EnhancedListener;

/**
 * Handy {@link ServletContextHandler} implementation that hooks up
 * all of the various CDI related components and listeners from Weld.
 */
public class EmbeddedCdiHandler extends ServletContextHandler
{
    private static final Logger LOG = Log.getLogger(EmbeddedCdiHandler.class);
    
    private static final String[] REQUIRED_BEANS_XML_PATHS = new String[] { 
        "/WEB-INF/beans.xml", 
        "/META-INF/beans.xml", 
        "/WEB-INF/classes/META-INF/beans.xml" 
    };

    public EmbeddedCdiHandler()
    {
        super();
    }

    public EmbeddedCdiHandler(int options)
    {
        super(options);
    }

    @Override
    protected void doStart() throws Exception
    {
        // Required of CDI
        Resource baseResource = getBaseResource();
        if (baseResource == null)
        {
            throw new NullPointerException("baseResource must be set (to so it can find the beans.xml)");
        }
        
        boolean foundBeansXml = false;

        // Verify that beans.xml is present, otherwise weld will fail silently.
        for(String beansXmlPath: REQUIRED_BEANS_XML_PATHS) {
            Resource res = baseResource.addPath(beansXmlPath);
            if (res == null)
            {
                // not found, skip it
                continue;
            }
            
            if (res.exists())
            {
                foundBeansXml = true;
            }

            if (res.isDirectory())
            {
                throw new IOException("Directory conflicts with expected file: " + res.getURI().toASCIIString());
            }
        }
        
        if (!foundBeansXml)
        {
            StringBuilder err = new StringBuilder();
            err.append("Unable to find required beans.xml from the baseResource: ");
            err.append(baseResource.getURI().toASCIIString()).append(System.lineSeparator());
            err.append("Searched for: ");
            for (String beansXmlPath : REQUIRED_BEANS_XML_PATHS)
            {
                err.append(System.lineSeparator());
                err.append("  ").append(beansXmlPath);
            }
            LOG.warn("ERROR: {}",err.toString());
            throw new IOException(err.toString());
        }

        // Initialize Weld
        JettyWeldInitializer.initContext(this);

        // Wire up Weld (what's usually done via the ServletContainerInitializer)
        ServletContext ctx = getServletContext();
        
        // Fake the call to ServletContainerInitializer
        ClassLoader orig = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(ctx.getClassLoader());
            
            EnhancedListener weldListener = new EnhancedListener();
            Set<Class<?>> classes = Collections.emptySet();
            weldListener.onStartup(classes,ctx);
            
            // add the rest of the Weld Listeners
            ctx.addListener(weldListener);
            if ((weldListener instanceof HttpSessionActivationListener)
                    || (weldListener instanceof HttpSessionAttributeListener)
                    || (weldListener instanceof HttpSessionBindingListener)
                    || (weldListener instanceof HttpSessionListener)
                    || (weldListener instanceof HttpSessionIdListener))
                {
                 if (getSessionHandler() != null)
                     getSessionHandler().addEventListener(weldListener);
                }
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(orig);
        }

        // Let normal ServletContextHandler startup continue its merry way
        super.doStart();
    }
}
