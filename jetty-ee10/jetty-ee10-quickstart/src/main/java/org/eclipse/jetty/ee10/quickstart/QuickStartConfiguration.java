//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.quickstart;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee10.annotations.AnnotationDecorator;
import org.eclipse.jetty.ee10.annotations.ServletContainerInitializersStarter;
import org.eclipse.jetty.ee10.webapp.AbstractConfiguration;
import org.eclipse.jetty.ee10.webapp.Configuration;
import org.eclipse.jetty.ee10.webapp.StandardDescriptorProcessor;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.ee10.webapp.WebDescriptor;
import org.eclipse.jetty.ee10.webapp.WebInfConfiguration;
import org.eclipse.jetty.ee10.webapp.WebXmlConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QuickStartConfiguration
 * <p>
 * Prepare for quickstart generation, or usage.
 */
public class QuickStartConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(QuickStartConfiguration.class);

    public static final Set<Class<? extends Configuration>> __replacedConfigurations = new HashSet<>();
    public static final String ORIGIN_ATTRIBUTE = "org.eclipse.jetty.quickstart.origin";
    public static final String QUICKSTART_WEB_XML = "org.eclipse.jetty.quickstart.xml";
    public static final String MODE = "org.eclipse.jetty.quickstart.mode";

    static
    {
        __replacedConfigurations.add(org.eclipse.jetty.ee10.webapp.WebXmlConfiguration.class);
        __replacedConfigurations.add(org.eclipse.jetty.ee10.webapp.MetaInfConfiguration.class);
        __replacedConfigurations.add(org.eclipse.jetty.ee10.webapp.FragmentConfiguration.class);
        __replacedConfigurations.add(org.eclipse.jetty.ee10.annotations.AnnotationConfiguration.class);
    }

    /** Configure the server for the quickstart mode.
     * <p>In practise this means calling <code>server.setDryRun(true)</code> for GENERATE mode</p>
     * @see Server#setDryRun(boolean)
     * @param server The server to configure
     * @param mode The quickstart mode
     */
    public static void configureMode(Server server, String mode)
    {
        if (mode != null && Mode.valueOf(mode) == Mode.GENERATE)
            server.setDryRun(true);
    }

    public enum Mode
    {
        GENERATE,  // Generate quickstart-web.xml and then stop
        AUTO,      // use or generate depending on the existance of quickstart-web.xml
        QUICKSTART // Use quickstart-web.xml
    }

    private Mode _mode = Mode.AUTO;
    private boolean _quickStart;

    public QuickStartConfiguration()
    {
        super(true);
        addDependencies(WebInfConfiguration.class);
        addDependents(WebXmlConfiguration.class);
    }

    @Override
    public void preConfigure(WebAppContext context) throws Exception
    {
        //check that webapp is suitable for quick start - it is not a packed war
        String war = context.getWar();
        if (war == null || war.length() <= 0 || !context.getResourceBase().isDirectory())
            throw new IllegalStateException("Bad Quickstart location");

        //look for quickstart-web.xml in WEB-INF of webapp
        Resource quickStartWebXml = getQuickStartWebXml(context);
        LOG.debug("quickStartWebXml={} exists={}", quickStartWebXml, quickStartWebXml.exists());

        //Get the mode
        Mode mode = (Mode)context.getAttribute(MODE);
        if (mode != null)
            _mode = mode;
        
        _quickStart = false;
        
        switch (_mode)
        {
            case GENERATE:
            {
                if (quickStartWebXml.exists())
                    LOG.info("Regenerating {}", quickStartWebXml);
                else
                    LOG.info("Generating {}", quickStartWebXml);
                
                super.preConfigure(context);
                //generate the quickstart file then abort
                QuickStartGeneratorConfiguration generator = new QuickStartGeneratorConfiguration(true);
                configure(generator, context);
                context.addConfiguration(generator);
                break;
            }
            case AUTO:
            {
                if (quickStartWebXml.exists())
                {
                    quickStart(context);
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("No quickstart xml file, starting webapp {} normally", context);
                    super.preConfigure(context);
                }
                break;
            }
            case QUICKSTART:
                if (quickStartWebXml.exists())
                    quickStart(context);
                else
                    throw new IllegalStateException("No " + quickStartWebXml);
                break;

            default:
                throw new IllegalStateException(_mode.toString());
        }
    }

    protected void configure(QuickStartGeneratorConfiguration generator, WebAppContext context) throws IOException
    {
        Object attr;
        attr = context.getAttribute(ORIGIN_ATTRIBUTE);
        if (attr != null)
            generator.setOriginAttribute(attr.toString());

        generator.setQuickStartWebXml((Resource)context.getAttribute(QUICKSTART_WEB_XML));
    }

    @Override
    public void configure(WebAppContext context) throws Exception
    {
        if (!_quickStart)
        {
            super.configure(context);
        }
        else
        {
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

    @Override
    public void postConfigure(WebAppContext context) throws Exception
    {
        super.postConfigure(context);
        ServletContainerInitializersStarter starter = (ServletContainerInitializersStarter)context.getAttribute(AnnotationConfiguration.CONTAINER_INITIALIZER_STARTER);
        if (starter != null)
        {
            context.removeBean(starter);
            context.removeAttribute(AnnotationConfiguration.CONTAINER_INITIALIZER_STARTER);
        }
    }

    protected void quickStart(WebAppContext context)
        throws Exception
    {
        LOG.info("Quickstarting {}", context);
        _quickStart = true;
        context.setConfigurations(context.getConfigurations().stream()
            .filter(c -> !__replacedConfigurations.contains(c.replaces()) && !__replacedConfigurations.contains(c.getClass()))
            .collect(Collectors.toList()).toArray(new Configuration[]{}));
        context.getMetaData().setWebDescriptor(new WebDescriptor((Resource)context.getAttribute(QUICKSTART_WEB_XML)));
        context.getContext().getServletContext().setEffectiveMajorVersion(context.getMetaData().getWebDescriptor().getMajorVersion());
        context.getContext().getServletContext().setEffectiveMinorVersion(context.getMetaData().getWebDescriptor().getMinorVersion());
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
        Object attr = context.getAttribute(QUICKSTART_WEB_XML);
        if (attr instanceof Resource)
            return (Resource)attr;

        Resource webInf = context.getWebInf();
        if (webInf == null || !webInf.exists())
        {
            Files.createDirectories(context.getResourceBase().getPath().resolve("WEB-INF"));
            webInf = context.getWebInf();
        }

        Resource qstart;
        if (attr == null || StringUtil.isBlank(attr.toString()))
        {
            qstart = webInf.resolve("quickstart-web.xml");
        }
        else
        {
            try
            {
                // Try a relative resolution
                qstart = Resource.newResource(webInf.getPath().resolve(attr.toString()));
            }
            catch (Throwable th)
            {
                // try as a resource
                qstart = (Resource.newResource(attr.toString()));
            }
            context.setAttribute(QUICKSTART_WEB_XML, qstart);
        }
        context.setAttribute(QUICKSTART_WEB_XML, qstart);
        return  qstart;
    }
}
