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

package org.eclipse.jetty.ee9.quickstart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jetty.ee9.annotations.AnnotationDecorator;
import org.eclipse.jetty.ee9.webapp.AbstractConfiguration;
import org.eclipse.jetty.ee9.webapp.Configuration;
import org.eclipse.jetty.ee9.webapp.StandardDescriptorProcessor;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.ee9.webapp.WebDescriptor;
import org.eclipse.jetty.ee9.webapp.WebInfConfiguration;
import org.eclipse.jetty.ee9.webapp.WebXmlConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
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
        __replacedConfigurations.add(org.eclipse.jetty.ee9.webapp.WebXmlConfiguration.class);
        __replacedConfigurations.add(org.eclipse.jetty.ee9.webapp.MetaInfConfiguration.class);
        __replacedConfigurations.add(org.eclipse.jetty.ee9.webapp.FragmentConfiguration.class);
        __replacedConfigurations.add(org.eclipse.jetty.ee9.annotations.AnnotationConfiguration.class);
    }

    private ResourceFactory.Closeable _resourceFactory;

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
    private QuickStartDescriptorProcessor _quickStartDescriptorProcessor;

    public QuickStartConfiguration()
    {
        super(true);
        addDependencies(WebInfConfiguration.class);
        addDependents(WebXmlConfiguration.class);
    }

    @Override
    public void preConfigure(WebAppContext context) throws Exception
    {
        _resourceFactory = ResourceFactory.closeable();

        //check that webapp is suitable for quick start - it is not a packed war
        String war = context.getWar();
        if (war == null || war.length() <= 0 || !context.getBaseResource().isDirectory())
            throw new IllegalStateException("Bad Quickstart location");

        //look for quickstart-web.xml in WEB-INF of webapp
        Path quickStartWebXml = getQuickStartWebXml(context);
        if (LOG.isDebugEnabled())
            LOG.debug("quickStartWebXml={}", quickStartWebXml);

        //Get the mode
        Object o = context.getAttribute(MODE);
        _mode = (o instanceof Mode m) 
            ? m 
            : (o instanceof String s) ? Mode.valueOf(s) : _mode;

        _quickStart = false;

        switch (_mode)
        {
            case GENERATE:
            {
                if (Files.exists(quickStartWebXml))
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
                if (Files.exists(quickStartWebXml))
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
                if (Files.exists(quickStartWebXml))
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

        Path quickStartWebXml = getQuickStartWebXml(context);
        generator.setQuickStartWebXml(quickStartWebXml);
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
            _quickStartDescriptorProcessor = new QuickStartDescriptorProcessor();
            context.getMetaData().addDescriptorProcessor(_quickStartDescriptorProcessor);

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
        if (_quickStartDescriptorProcessor != null)
        {
            _quickStartDescriptorProcessor.close();
            _quickStartDescriptorProcessor = null;
        }
        IO.close(_resourceFactory);
        _resourceFactory = null;
    }

    protected void quickStart(WebAppContext context)
        throws Exception
    {
        LOG.info("Quickstarting {}", context);
        _quickStart = true;
        context.setConfigurations(context.getConfigurations().stream()
            .filter(c -> !__replacedConfigurations.contains(c.replaces()))
            .filter(c -> !__replacedConfigurations.contains(c.getClass()))
            .toArray(Configuration[]::new));
        Path quickStartWebXml = getQuickStartWebXml(context);
        if (!Files.exists(quickStartWebXml))
            throw new IllegalStateException("Quickstart doesn't exist: " + quickStartWebXml);
        Resource quickStartWebResource = context.getResourceFactory().newResource(quickStartWebXml);
        context.getMetaData().setWebDescriptor(new WebDescriptor(quickStartWebResource));
        context.getServletContext().setEffectiveMajorVersion(context.getMetaData().getWebDescriptor().getMajorVersion());
        context.getServletContext().setEffectiveMinorVersion(context.getMetaData().getWebDescriptor().getMinorVersion());
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
        Path qstartFile = webInfDir.resolve("quickstart-web.xml");

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
                    qstartFile = attrPath;
                }
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Using quickstart location: {}", qstartFile);
        context.setAttribute(QUICKSTART_WEB_XML, qstartFile);
        return qstartFile;
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
            Path baseResourcePath = context.getBaseResource().getPath();
            webInfDir = baseResourcePath.resolve("WEB-INF");
            if (!Files.exists(webInfDir))
                Files.createDirectories(webInfDir);
        }
        return webInfDir;
    }
}
