//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quickstart;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.annotations.AnnotationDecorator;
import org.eclipse.jetty.annotations.ServletContainerInitializersStarter;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.AbstractConfiguration;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.StandardDescriptorProcessor;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebDescriptor;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
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
    private static final Mode DEFAULT_MODE = Mode.AUTO;

    static
    {
        __replacedConfigurations.add(org.eclipse.jetty.webapp.WebXmlConfiguration.class);
        __replacedConfigurations.add(org.eclipse.jetty.webapp.MetaInfConfiguration.class);
        __replacedConfigurations.add(org.eclipse.jetty.webapp.FragmentConfiguration.class);
        __replacedConfigurations.add(org.eclipse.jetty.annotations.AnnotationConfiguration.class);
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
        AUTO,      // use quickstart depending on the existence of quickstart-web.xml
        QUICKSTART // Use quickstart-web.xml
    }

    public QuickStartConfiguration()
    {
        super(true);
        addDependencies(WebInfConfiguration.class);
        addDependents(WebXmlConfiguration.class);
    }

    private Mode getModeForContext(WebAppContext context)
    {
        Mode mode = (Mode)context.getAttribute(MODE);
        if (mode == null)
            return DEFAULT_MODE;
        return mode;
    }

    @Override
    public void preConfigure(WebAppContext context) throws Exception
    {
        // check that webapp is suitable for quick start - it is not a packed war
        String war = context.getWar();
        if (StringUtil.isBlank(war) || !context.getBaseResource().isDirectory())
            throw new IllegalStateException("Invalid Quickstart location");

        // look for quickstart-web.xml in WEB-INF of webapp
        Resource quickStartWebXml = getQuickStartWebXml(context);

        // Get the mode
        Mode mode = getModeForContext(context);

        if (LOG.isDebugEnabled())
            LOG.debug("mode={} quickStartWebXml={} exists={} for {}",
                mode,
                quickStartWebXml,
                quickStartWebXml.exists(),
                context);

        switch (mode)
        {
            case GENERATE:
            {
                if (quickStartWebXml.exists())
                    LOG.info("Regenerating {} for {}", quickStartWebXml, context);
                else
                    LOG.info("Generating {} for {}", quickStartWebXml, context);

                super.preConfigure(context);
                // generate the quickstart file then abort
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
                        LOG.debug("No quickstart-web.xml found, starting webapp {} normally", context);
                    super.preConfigure(context);
                }
                break;
            }
            case QUICKSTART:
            {
                if (quickStartWebXml.exists())
                {
                    quickStart(context);
                }
                else
                {
                    throw new IllegalStateException("No WEB-INF/quickstart-web.xml file for " + context);
                }
                break;
            }
            default:
                throw new IllegalStateException("Unhandled QuickStart.Mode: " + mode);
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
        Resource quickStartWebXml = getQuickStartWebXml(context);
        // Don't run configure() if quickstart-web.xml does not exist
        if (quickStartWebXml == null || !quickStartWebXml.exists())
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
        context.setConfigurations(context.getConfigurations().stream()
            .filter(c -> !__replacedConfigurations.contains(c.replaces()) && !__replacedConfigurations.contains(c.getClass()))
            .collect(Collectors.toList()).toArray(new Configuration[]{}));
        context.getMetaData().setWebDescriptor(new WebDescriptor((Resource)context.getAttribute(QUICKSTART_WEB_XML)));
        context.getServletContext().setEffectiveMajorVersion(context.getMetaData().getWebDescriptor().getMajorVersion());
        context.getServletContext().setEffectiveMinorVersion(context.getMetaData().getWebDescriptor().getMinorVersion());
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

        Resource qstart;
        Resource webInf = context.getWebInf();
        if (webInf == null || !webInf.exists())
        {
            switch (getModeForContext(context))
            {
                case GENERATE:
                    // This mode allow for generation of quickstart-web.xml
                    // So we should be safe to attempt to create the missing WEB-INF directory.
                    File tmp = new File(context.getBaseResource().getFile(), "WEB-INF");
                    if (!tmp.mkdirs())
                    {
                        throw new IllegalStateException("Unable to create directory " + tmp);
                    }
                    webInf = context.getWebInf();
                    break;
                case AUTO:
                case QUICKSTART:
                    // These modes do not allow for generation of quickstart-web.xml, so do not
                    // create the missing WEB-INF directory.

                    // Establish a Resource suitable for callers of this method (does not exist)
                    qstart = context.getBaseResource().addPath("WEB-INF/quickstart-web.xml");
                    context.setAttribute(QUICKSTART_WEB_XML, qstart);
                    return qstart;
            }
        }

        if (attr == null || StringUtil.isBlank(attr.toString()))
        {
            qstart = webInf.addPath("quickstart-web.xml");
        }
        else
        {
            try
            {
                // Try a relative resolution
                qstart = Resource.newResource(webInf.getFile().toPath().resolve(attr.toString()));
            }
            catch (Throwable th)
            {
                // try as a resource
                qstart = (Resource.newResource(attr.toString()));
            }
            context.setAttribute(QUICKSTART_WEB_XML, qstart);
        }
        context.setAttribute(QUICKSTART_WEB_XML, qstart);
        return qstart;
    }
}
