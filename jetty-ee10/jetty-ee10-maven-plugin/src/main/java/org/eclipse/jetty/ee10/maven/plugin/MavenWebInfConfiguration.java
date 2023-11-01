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

package org.eclipse.jetty.ee10.maven.plugin;

import java.net.URI;

import org.eclipse.jetty.ee10.webapp.Configuration;
import org.eclipse.jetty.ee10.webapp.WebAppClassLoader;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.ee10.webapp.WebInfConfiguration;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MavenWebInfConfiguration
 * <p>
 * WebInfConfiguration to take account of overlaid wars expressed as project dependencies and
 * potential configured via the maven-war-plugin.
 */
public class MavenWebInfConfiguration extends WebInfConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(MavenWebInfConfiguration.class);

    public MavenWebInfConfiguration()
    {
        super(new Builder()
            .hide("org.apache.maven.",
                "org.codehaus.plexus.",
                "jakarta.enterprise.",
                "javax.decorator."));
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
        if (jwac.getClassPathUris() != null && context.getClassLoader() instanceof WebAppClassLoader loader)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Setting up classpath ...");
            for (URI uri : jwac.getClassPathUris())
            {
                // Not all Resource types supported by Jetty can be supported by WebAppClassLoader
                String scheme = uri.getScheme();
                if (scheme == null || scheme.equals("file"))
                {
                    // no scheme? or "file" scheme, assume it is just a path.
                    loader.addClassPath(uri.getPath());
                    continue;
                }

                if (scheme.equals("jar"))
                {
                    URI container = URIUtil.unwrapContainer(uri);
                    if (container.getScheme().equals("file"))
                    {
                        // Just add a reference to the
                        loader.addClassPath(container.getPath());
                        continue;
                    }
                }

                // Anything else is a warning
                LOG.warn("Skipping unsupported URI on ClassPath: {}", uri);
            }
        }

        super.configure(context);
    }
}
