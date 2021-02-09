//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.quickstart;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.annotations.AnnotationDecorator;
import org.eclipse.jetty.annotations.ServletContainerInitializersStarter;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.StandardDescriptorProcessor;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;

/**
 * QuickStartConfiguration
 * <p>
 * Re-inflate a deployable webapp from a saved effective-web.xml
 * which combines all pre-parsed web xml descriptors and annotations.
 */
public class QuickStartConfiguration extends WebInfConfiguration
{
    private static final Logger LOG = Log.getLogger(QuickStartConfiguration.class);

    /**
     * @see org.eclipse.jetty.webapp.AbstractConfiguration#preConfigure(org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    public void preConfigure(WebAppContext context) throws Exception
    {
        //check that webapp is suitable for quick start - it is not a packed war
        String war = context.getWar();
        if (war == null || war.length() <= 0)
            throw new IllegalStateException("No location for webapp");

        //Make a temp directory for the webapp if one is not already set
        resolveTempDirectory(context);

        Resource webApp = context.newResource(war);

        // Accept aliases for WAR files
        if (webApp.isAlias())
        {
            LOG.debug(webApp + " anti-aliased to " + webApp.getAlias());
            webApp = context.newResource(webApp.getAlias());
        }

        // Is the WAR usable directly?
        if (!webApp.exists() || !webApp.isDirectory() || webApp.toString().startsWith("jar:"))
            throw new IllegalStateException("Webapp does not exist or is not unpacked");

        context.setBaseResource(webApp);

        LOG.debug("webapp={}", webApp);

        //look for quickstart-web.xml in WEB-INF of webapp
        Resource quickStartWebXml = getQuickStartWebXml(context);
        LOG.debug("quickStartWebXml={}", quickStartWebXml);

        context.getMetaData().setWebXml(quickStartWebXml);
    }

    /**
     * Get the quickstart-web.xml file as a Resource.
     *
     * @param context the web app context
     * @return the Resource for the quickstart-web.xml
     * @throws Exception if unable to find the quickstart xml
     */
    public Resource getQuickStartWebXml(WebAppContext context) throws Exception
    {
        Resource webInf = context.getWebInf();
        if (webInf == null || !webInf.exists())
            throw new IllegalStateException("No WEB-INF");
        LOG.debug("webinf={}", webInf);

        Resource quickStartWebXml = webInf.addPath("quickstart-web.xml");
        if (!quickStartWebXml.exists())
            throw new IllegalStateException("No WEB-INF/quickstart-web.xml");
        return quickStartWebXml;
    }

    /**
     * @see org.eclipse.jetty.webapp.AbstractConfiguration#configure(org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    public void configure(WebAppContext context) throws Exception
    {
        LOG.debug("configure {}", this);
        if (context.isStarted())
        {
            LOG.warn("Cannot configure webapp after it is started");
            return;
        }

        //Temporary:  set up the classpath here. This should be handled by the QuickStartDescriptorProcessor
        Resource webInf = context.getWebInf();

        if (webInf != null && webInf.isDirectory() && context.getClassLoader() instanceof WebAppClassLoader)
        {
            // Look for classes directory
            Resource classes = webInf.addPath("classes/");
            if (classes.exists())
                ((WebAppClassLoader)context.getClassLoader()).addClassPath(classes);

            // Look for jars
            Resource lib = webInf.addPath("lib/");
            if (lib.exists() || lib.isDirectory())
                ((WebAppClassLoader)context.getClassLoader()).addJars(lib);
        }

        //add the processor to handle normal web.xml content
        context.getMetaData().addDescriptorProcessor(new StandardDescriptorProcessor());

        //add a processor to handle extended web.xml format
        context.getMetaData().addDescriptorProcessor(new QuickStartDescriptorProcessor());

        //add a decorator that will find introspectable annotations
        context.getObjectFactory().addDecorator(new AnnotationDecorator(context)); //this must be the last Decorator because they are run in reverse order!

        //add a context bean that will run ServletContainerInitializers as the context starts
        ServletContainerInitializersStarter starter = (ServletContainerInitializersStarter)context.getAttribute(AnnotationConfiguration.CONTAINER_INITIALIZER_STARTER);
        if (starter != null)
            throw new IllegalStateException("ServletContainerInitializersStarter already exists");
        starter = new ServletContainerInitializersStarter(context);
        context.setAttribute(AnnotationConfiguration.CONTAINER_INITIALIZER_STARTER, starter);
        context.addBean(starter, true);

        LOG.debug("configured {}", this);
    }
}
