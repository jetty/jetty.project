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

package org.eclipse.jetty.embedded;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * A utility test class to locate a Jetty Distribution for testing purposes by searching:
 * <ul>
 * <li>The <code>jetty.home</code> system property</li>
 * <li>The <code>JETTY_HOME</code> environment variable</li>
 * <li>The working directory hierarchy with subdirectory <code>jetty-distribution/target/home</code></li>
 * </ul>
 */
public class JettyDistribution
{
    private static final Logger LOG = Log.getLogger(JettyDistribution.class);
    public static final Path DISTRIBUTION;

    static
    {
        Path distro = asJettyDistribution(System.getProperty("jetty.home"));
        LOG.debug("JettyDistribution(prop(jetty.home)) = " + distro);
        if (distro == null)
        {
            distro = asJettyDistribution(System.getenv().get("JETTY_HOME"));
            LOG.debug("JettyDistribution(env(JETTY_HOME)) = " + distro);
        }

        if (distro == null)
        {
            try
            {
                Path working = Paths.get(System.getProperty("user.dir"));
                LOG.debug("JettyDistribution(prop(user.dir)) = " + working);
                while (distro == null && working != null)
                {
                    distro = asJettyDistribution(working.resolve("jetty-distribution/target/distribution").toString());
                    working = working.getParent();
                }
                LOG.debug("JettyDistribution(working.resolve(...)) = " + distro);
            }
            catch (Throwable cause)
            {
                LOG.warn(cause);
            }
        }

        if (distro == null)
        {
            LOG.info("JettyDistribution() FAILURE: NOT FOUND");
        }
        else
        {
            LOG.debug("JettyDistribution() FOUND = " + distro);
        }
        DISTRIBUTION = distro;
    }

    private static Path asJettyDistribution(String jettyHome)
    {
        try
        {
            if (jettyHome == null)
            {
                return null;
            }

            if (StringUtil.isBlank(jettyHome))
            {
                LOG.debug("asJettyDistribution {} is blank", jettyHome);
                return null;
            }

            Path dir = Paths.get(jettyHome);
            if (!Files.exists(dir))
            {
                LOG.debug("asJettyDistribution {} does not exist", jettyHome);
                return null;
            }

            if (!Files.isDirectory(dir))
            {
                LOG.debug("asJettyDistribution {} is not a directory", jettyHome);
                return null;
            }

            Path demoBase = dir.resolve("demo-base");
            if (!Files.exists(demoBase) || !Files.isDirectory(demoBase))
            {
                LOG.debug("asJettyDistribution {} has no demo-base", jettyHome);
                return null;
            }

            LOG.debug("asJettyDistribution {}", dir);
            return dir.toAbsolutePath();
        }
        catch (Exception e)
        {
            LOG.ignore(e);
        }
        return null;
    }

    public static Path get()
    {
        if (DISTRIBUTION == null)
            throw new RuntimeException("jetty-distribution not found");
        return DISTRIBUTION;
    }

    public static Path resolve(String path)
    {
        return get().resolve(path);
    }

    public static void main(String... arg)
    {
        System.err.println("Jetty Distribution is " + DISTRIBUTION);
    }
}
