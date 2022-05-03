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

package org.eclipse.jetty.ee9.maven.plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.jetty.ee9.webapp.Configuration;
import org.eclipse.jetty.ee9.webapp.MetaInfConfiguration;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MavenWebInfConfiguration
 *
 * WebInfConfiguration to take account of overlaid wars expressed as project dependencies and
 * potential configured via the maven-war-plugin.
 */
public class MavenMetaInfConfiguration extends MetaInfConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(MavenMetaInfConfiguration.class);

    protected static int COUNTER = 0;

    @Override
    public Class<? extends Configuration> replaces()
    {
        return MetaInfConfiguration.class;
    }

    /**
     * Get the jars to examine from the files from which we have
     * synthesized the classpath. Note that the classpath is not
     * set at this point, so we cannot get them from the classpath.
     *
     * @param context the web app context
     * @return the list of jars found
     */
    @Override
    protected List<Resource> findJars(WebAppContext context)
        throws Exception
    {
        List<Resource> list = new ArrayList<>();
        MavenWebAppContext jwac = (MavenWebAppContext)context;
        List<File> files = jwac.getWebInfLib();
        if (files != null)
        {
            files.forEach(file ->
            {
                if (file.getName().toLowerCase(Locale.ENGLISH).endsWith(".jar") || file.isDirectory())
                {
                    try
                    {
                        LOG.debug(" add  resource to resources to examine {}", file);
                        list.add(Resource.newResource(file.toURI()));
                    }
                    catch (Exception e)
                    {
                        LOG.warn("Bad url ", e);
                    }
                }
            });
        }

        List<Resource> superList = super.findJars(context);
        if (superList != null)
            list.addAll(superList);
        return list;
    }

    /**
     * Add in the classes dirs from test/classes and target/classes
     */
    @Override
    protected List<Resource> findClassDirs(WebAppContext context) throws Exception
    {
        List<Resource> list = new ArrayList<>();

        MavenWebAppContext jwac = (MavenWebAppContext)context;
        List<File> files = jwac.getWebInfClasses();
        if (files != null)
        {
            files.forEach(file ->
            {
                if (file.exists() && file.isDirectory())
                {
                    try
                    {
                        list.add(Resource.newResource(file.toURI()));
                    }
                    catch (Exception e)
                    {
                        LOG.warn("Bad url ", e);
                    }
                }
            });
        }

        List<Resource> classesDirs = super.findClassDirs(context);
        if (classesDirs != null)
            list.addAll(classesDirs);
        return list;
    }
}
