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

package org.eclipse.jetty.ee10.quickstart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jetty.ee10.annotations.AnnotationDecorator;
import org.eclipse.jetty.ee10.webapp.AbstractConfiguration;
import org.eclipse.jetty.ee10.webapp.Configuration;
import org.eclipse.jetty.ee10.webapp.StandardDescriptorProcessor;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.ee10.webapp.WebDescriptor;
import org.eclipse.jetty.ee10.webapp.WebInfConfiguration;
import org.eclipse.jetty.ee10.webapp.WebXmlConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
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
        __replacedConfigurations.add(org.eclipse.jetty.ee10.webapp.WebXmlConfiguration.class);
        __replacedConfigurations.add(org.eclipse.jetty.ee10.webapp.MetaInfConfiguration.class);
        __replacedConfigurations.add(org.eclipse.jetty.ee10.webapp.FragmentConfiguration.class);
        __replacedConfigurations.add(org.eclipse.jetty.ee10.annotations.AnnotationConfiguration.class);
    }

    private ResourceFactory.Closeable _resourceFactory;

    /**
     * Configure the server for the quickstart mode.
     * <p>In practise this means calling <code>server.setDryRun(true)</code> for GENERATE mode</p>
     *
     * @param server The server to configure
     * @param mode The quickstart mode
     * @see Server#setDryRun(boolean)
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
        super(new Builder()
            .enabledByDefault(true)
            .addDependencies(WebInfConfiguration.class)
            .addDependents(WebXmlConfiguration.class));
    }

    private static Mode getModeForContext(WebAppContext context)
    {
        Object o = context.getAttribute(MODE);
        if (o instanceof Mode m)
            return m;
        if (o instanceof String s)
            return Mode.valueOf(s);
        else
            return DEFAULT_MODE;
    }

    @Override
    public void preConfigure(WebAppContext context) throws Exception
    {
        _resourceFactory = ResourceFactory.closeable();

        //check that webapp is suitable for quick start - it is not a packed war
        String war = context.getWar();
        if (StringUtil.isBlank(war) || !context.getBaseResource().isDirectory())
            throw new IllegalStateException("Invalid Quickstart location");

        //look for quickstart-web.xml in WEB-INF of webapp
        Path quickStartWebXml = getQuickStartWebXml(context);

        //Get the mode
        Mode mode = getModeForContext(context);

        boolean quickStartWebXmlExists = quickStartWebXml != null && Files.isRegularFile(quickStartWebXml);

        if (LOG.isDebugEnabled())
            LOG.debug("mode={} quickStartWebXml={} isReadableFile={} for {}",
                mode,
                quickStartWebXml,
                quickStartWebXmlExists,
                context);

        switch (mode)
        {
            case GENERATE:
            {
                if (quickStartWebXmlExists)
                    LOG.info("Regenerating {} for {}", quickStartWebXml, context);
                else
                    LOG.info("Generating {} for {}", quickStartWebXml, context);

                super.preConfigure(context);
                //generate the quickstart file then abort
                QuickStartGeneratorConfiguration generator = new QuickStartGeneratorConfiguration(true);
                configure(generator, context);
                context.addConfiguration(generator);
                break;
            }
            case AUTO:
            {
                if (quickStartWebXmlExists)
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
                if (quickStartWebXmlExists)
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

        Path quickStartWebXml = getQuickStartWebXml(context);
        if (quickStartWebXml == null)
            throw new IllegalStateException("Unable to generate quickstart for context: " + context);
        generator.setQuickStartWebXml(quickStartWebXml);
    }

    @Override
    public void configure(WebAppContext context) throws Exception
    {
        Path quickStartWebXml = getQuickStartWebXml(context);
        // Don't run configure() if quickstart-web.xml does not exist
        if (quickStartWebXml == null || !Files.isRegularFile(quickStartWebXml))
        {
            super.configure(context);
        }
        else
        {
            //add the processor to handle normal web.xml content
            context.getMetaData().addDescriptorProcessor(new StandardDescriptorProcessor());

            //add a processor to handle extended web.xml format
            QuickStartDescriptorProcessor quickStartDescriptorProcessor = new QuickStartDescriptorProcessor();
            context.getMetaData().addDescriptorProcessor(quickStartDescriptorProcessor);

            context.setAttribute(QuickStartDescriptorProcessor.class.getName(), quickStartDescriptorProcessor);

            //add a decorator that will find introspectable annotations
            context.getObjectFactory().addDecorator(new AnnotationDecorator(context)); //this must be the last Decorator because they are run in reverse order!

            if (LOG.isDebugEnabled())
                LOG.debug("configured {}", this);
        }
    }

    @Override
    public void postConfigure(WebAppContext context) throws Exception
    {
        super.postConfigure(context);
    }

    @Override
    public void deconfigure(WebAppContext context) throws Exception
    {
        super.deconfigure(context);
        QuickStartDescriptorProcessor quickStartDescriptorProcessor = (QuickStartDescriptorProcessor)context.getAttribute(QuickStartDescriptorProcessor.class.getName());
        IO.close(quickStartDescriptorProcessor);
        IO.close(_resourceFactory);
        _resourceFactory = null;
    }

    protected void quickStart(WebAppContext context)
        throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.info("Quickstarting {}", context);
        context.setConfigurations(context.getConfigurations().stream()
            .filter(c -> !__replacedConfigurations.contains(c.replaces()))
            .filter(c -> !__replacedConfigurations.contains(c.getClass()))
            .toArray(Configuration[]::new));
        Path quickStartWebXml = getQuickStartWebXml(context);
        if (!Files.isRegularFile(quickStartWebXml))
            throw new IllegalStateException("Quickstart doesn't exist: " + quickStartWebXml);
        Resource quickStartWebResource = context.getResourceFactory().newResource(quickStartWebXml);
        context.getMetaData().setWebDescriptor(new WebDescriptor(quickStartWebResource));
        context.getContext().getServletContext().setEffectiveMajorVersion(context.getMetaData().getWebDescriptor().getMajorVersion());
        context.getContext().getServletContext().setEffectiveMinorVersion(context.getMetaData().getWebDescriptor().getMinorVersion());
    }

    /**
     * Get the quickstart-web.xml Path from the webapp (from attributes if present, or built from the context's {@link WebAppContext#getWebInf()}).
     *
     * @param context the web app context
     * @return the Path for the quickstart-web.xml
     * @throws IOException if unable to build the quickstart xml
     */
    public static Path getQuickStartWebXml(WebAppContext context) throws IOException
    {
        Object attr = context.getAttribute(QUICKSTART_WEB_XML);
        if (attr instanceof Path)
            return (Path)attr;

        Path webInfDir = getWebInfPath(context);
        Path qstartPath = webInfDir.resolve("quickstart-web.xml");

        if (attr != null && StringUtil.isNotBlank(attr.toString()))
        {
            Resource resource;
            String attrValue = attr.toString();
            try
            {
                // Try a relative resolution
                resource = context.getResourceFactory().newResource(webInfDir.resolve(attrValue));
            }
            catch (Throwable th)
            {
                // try as a resource
                resource = context.getResourceFactory().newResource(attrValue);
            }
            if (resource != null)
            {
                Path attrPath = resource.getPath();
                if (attrPath != null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Using quickstart attribute {} value of {}", attr, attrValue);
                    qstartPath = attrPath;
                }
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Using quickstart location: {}", qstartPath);
        context.setAttribute(QUICKSTART_WEB_XML, qstartPath);
        return qstartPath;
    }

    private static Path getWebInfPath(WebAppContext context) throws IOException
    {
        Path webInfDir = null;
        Resource webInf = context.getWebInf();
        if (webInf != null)
        {
            webInfDir = webInf.getPath();
        }

        if (webInfDir == null)
        {
            Path baseResourcePath = findFirstWritablePath(context);
            webInfDir = baseResourcePath.resolve("WEB-INF");
            // Only create directory if in GENERATE mode
            if (getModeForContext(context) == Mode.GENERATE)
            {
                if (!Files.exists(webInfDir))
                    Files.createDirectories(webInfDir);
            }
        }
        return webInfDir;
    }

    private static Path findFirstWritablePath(WebAppContext context) throws IOException
    {
        for (Resource resource : context.getBaseResource())
        {
            Path path = resource.getPath();
            if (path == null || !Files.isDirectory(path) || !Files.isWritable(path))
                continue; // skip
            return path;
        }
        throw new IOException("Unable to find writable path in Base Resources");
    }
}
