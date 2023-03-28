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

package org.eclipse.jetty.maven.plugin;

import java.io.File;

import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MavenWebInfConfiguration
 *
 * WebInfConfiguration to take account of overlaid wars expressed as project dependencies and
 * potential configured via the maven-war-plugin.
 */
public class MavenWebInfConfiguration extends WebInfConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(MavenWebInfConfiguration.class);

    public MavenWebInfConfiguration()
    {
        hide("org.apache.maven.",
            "org.codehaus.plexus.",
            "javax.enterprise.",
            "javax.decorator.");
    }

    @Override
    public Class<? extends Configuration> replaces()
    {
        return WebInfConfiguration.class;
    }

    @Override
    public void configure(WebAppContext context) throws Exception
    {
        MavenWebAppContext jwac = (MavenWebAppContext)context;

        //put the classes dir and all dependencies into the classpath
        if (jwac.getClassPathFiles() != null && context.getClassLoader() instanceof WebAppClassLoader)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Setting up classpath ...");
            WebAppClassLoader loader = (WebAppClassLoader)context.getClassLoader();
            for (File classpath : jwac.getClassPathFiles())
            {
                loader.addClassPath(classpath.getCanonicalPath());
            }
        }

        super.configure(context);
    }
}
