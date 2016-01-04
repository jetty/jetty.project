//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.webapp;

import java.io.IOException;
import java.net.MalformedURLException;

import org.eclipse.jetty.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

/* ------------------------------------------------------------------------------- */
/**
 * Configure by parsing default web.xml and web.xml
 * 
 */
public class WebXmlConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = Log.getLogger(WebXmlConfiguration.class);

    
    /* ------------------------------------------------------------------------------- */
    /**
     * 
     */
    @Override
    public void preConfigure (WebAppContext context) throws Exception
    {
        //parse webdefault.xml
        String defaultsDescriptor = context.getDefaultsDescriptor();
        if (defaultsDescriptor != null && defaultsDescriptor.length() > 0)
        {
            Resource dftResource = Resource.newSystemResource(defaultsDescriptor);
            if (dftResource == null) 
                dftResource = context.newResource(defaultsDescriptor);
            context.getMetaData().setDefaults (dftResource);
        }
        
        //parse, but don't process web.xml
        Resource webxml = findWebXml(context);
        if (webxml != null) 
        {      
            context.getMetaData().setWebXml(webxml);
            context.getServletContext().setEffectiveMajorVersion(context.getMetaData().getWebXml().getMajorVersion());
            context.getServletContext().setEffectiveMinorVersion(context.getMetaData().getWebXml().getMinorVersion());
        }
        
        //parse but don't process override-web.xml
        for (String overrideDescriptor : context.getOverrideDescriptors())
        {
            if (overrideDescriptor != null && overrideDescriptor.length() > 0)
            {
                Resource orideResource = Resource.newSystemResource(overrideDescriptor);
                if (orideResource == null) 
                    orideResource = context.newResource(overrideDescriptor);
                context.getMetaData().addOverride(orideResource);
            }
        }
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * Process web-default.xml, web.xml, override-web.xml
     * 
     */
    @Override
    public void configure (WebAppContext context) throws Exception
    {
        // cannot configure if the context is already started
        if (context.isStarted())
        {
            LOG.debug("Cannot configure webapp after it is started");
            return;
        }

        context.getMetaData().addDescriptorProcessor(new StandardDescriptorProcessor());
    }
    
    /* ------------------------------------------------------------------------------- */
    protected Resource findWebXml(WebAppContext context) throws IOException, MalformedURLException
    {
        String descriptor = context.getDescriptor();
        if (descriptor != null)
        {
            Resource web = context.newResource(descriptor);
            if (web.exists() && !web.isDirectory()) return web;
        }

        Resource web_inf = context.getWebInf();
        if (web_inf != null && web_inf.isDirectory())
        {
            // do web.xml file
            Resource web = web_inf.addPath("web.xml");
            if (web.exists()) return web;
            if (LOG.isDebugEnabled())
                LOG.debug("No WEB-INF/web.xml in " + context.getWar() + ". Serving files and default/dynamic servlets only");
        }
        return null;
    }


    /* ------------------------------------------------------------------------------- */
    @Override
    public void deconfigure (WebAppContext context) throws Exception
    {      
        context.setWelcomeFiles(null);

        if (context.getErrorHandler() instanceof ErrorPageErrorHandler)
            ((ErrorPageErrorHandler) 
                    context.getErrorHandler()).setErrorPages(null);

        // TODO remove classpaths from classloader
    }
}
