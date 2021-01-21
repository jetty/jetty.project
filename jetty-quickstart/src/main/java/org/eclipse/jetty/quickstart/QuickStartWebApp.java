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

import java.io.FileOutputStream;
import java.util.Locale;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * QuickStartWar
 */
public class QuickStartWebApp extends WebAppContext
{
    private static final Logger LOG = Log.getLogger(QuickStartWebApp.class);

    public static final String[] __configurationClasses =
        {
            org.eclipse.jetty.quickstart.QuickStartConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.plus.webapp.EnvConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.plus.webapp.PlusConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.webapp.JettyWebXmlConfiguration.class.getCanonicalName()
        };

    private boolean _preconfigure = false;
    private boolean _autoPreconfigure = false;
    private boolean _startWebapp = false;
    private PreconfigureDescriptorProcessor _preconfigProcessor;
    private String _originAttribute;
    private boolean _generateOrigin;

    public static final String[] __preconfigurationClasses =
        {
            org.eclipse.jetty.webapp.WebInfConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.webapp.WebXmlConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.webapp.MetaInfConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.webapp.FragmentConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.plus.webapp.EnvConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.plus.webapp.PlusConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.annotations.AnnotationConfiguration.class.getCanonicalName()
        };

    public QuickStartWebApp()
    {
        super();
        setConfigurationClasses(__preconfigurationClasses);
    }

    public boolean isPreconfigure()
    {
        return _preconfigure;
    }

    /**
     * Preconfigure webapp
     *
     * @param preconfigure If true, then starting the webapp will generate
     * the WEB-INF/quickstart-web.xml rather than start the webapp.
     */
    public void setPreconfigure(boolean preconfigure)
    {
        _preconfigure = preconfigure;
    }

    public boolean isAutoPreconfigure()
    {
        return _autoPreconfigure;
    }

    public void setAutoPreconfigure(boolean autoPrecompile)
    {
        _autoPreconfigure = autoPrecompile;
    }

    public void setOriginAttribute(String name)
    {
        _originAttribute = name;
    }

    /**
     * @return the originAttribute
     */
    public String getOriginAttribute()
    {
        return _originAttribute;
    }

    /**
     * @return the generateOrigin
     */
    public boolean getGenerateOrigin()
    {
        return _generateOrigin;
    }

    /**
     * @param generateOrigin the generateOrigin to set
     */
    public void setGenerateOrigin(boolean generateOrigin)
    {
        _generateOrigin = generateOrigin;
    }
    
    /**
     * Never call any listeners unless we are fully
     * starting the webapp.
     */
    @Override
    public void contextInitialized() throws Exception
    {
        if (_startWebapp)
            super.contextInitialized();
    }
    
    /**
     * Never call any listeners unless we are fully
     * starting the webapp.
     */
    @Override
    public void contextDestroyed() throws Exception
    {
        if (_startWebapp)
            super.contextDestroyed();
    }
    
    @Override
    protected void startWebapp() throws Exception
    {
        if (isPreconfigure())
            generateQuickstartWebXml(_preconfigProcessor.getXML());

        if (_startWebapp)
            super.startWebapp();
    }

    @Override
    protected void stopWebapp() throws Exception
    {
        if (!_startWebapp)
            return;

        super.stopWebapp();
    }

    @Override
    protected void doStart() throws Exception
    {
        // unpack and Adjust paths.
        Resource war = null;
        Resource dir = null;

        Resource base = getBaseResource();
        if (base == null)
            base = Resource.newResource(getWar());

        if (base.isDirectory())
            dir = base;
        else if (base.toString().toLowerCase(Locale.ENGLISH).endsWith(".war"))
        {
            war = base;
            String w = war.toString();
            dir = Resource.newResource(w.substring(0, w.length() - 4));

            if (!dir.exists())
            {
                LOG.info("Quickstart Extract " + war + " to " + dir);
                dir.getFile().mkdirs();
                JarResource.newJarResource(war).copyTo(dir.getFile());
            }

            setWar(null);
            setBaseResource(dir);
        }
        else
            throw new IllegalArgumentException();

        Resource qswebxml = dir.addPath("/WEB-INF/quickstart-web.xml");

        if (isPreconfigure())
        {
            _preconfigProcessor = new PreconfigureDescriptorProcessor();
            getMetaData().addDescriptorProcessor(_preconfigProcessor);
            _startWebapp = false;
        }
        else if (qswebxml.exists())
        {
            setConfigurationClasses(__configurationClasses);
            _startWebapp = true;
        }
        else if (_autoPreconfigure)
        {
            LOG.info("Quickstart preconfigure: {}(war={},dir={})", this, war, dir);

            _preconfigProcessor = new PreconfigureDescriptorProcessor();
            getMetaData().addDescriptorProcessor(_preconfigProcessor);
            setPreconfigure(true);
            _startWebapp = true;
        }
        else
            _startWebapp = true;

        super.doStart();
    }

    public void generateQuickstartWebXml(String extraXML) throws Exception
    {
        Resource descriptor = getWebInf().addPath(QuickStartDescriptorGenerator.DEFAULT_QUICKSTART_DESCRIPTOR_NAME);
        if (!descriptor.exists())
            descriptor.getFile().createNewFile();
        QuickStartDescriptorGenerator generator = new QuickStartDescriptorGenerator(this, extraXML, _originAttribute, _generateOrigin);
        try (FileOutputStream fos = new FileOutputStream(descriptor.getFile()))
        {
            generator.generateQuickStartWebXml(fos);
        }
    }
}
