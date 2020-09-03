//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.embedded;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A utility test class to locate a Jetty Distribution for testing purposes by searching:
 * <ul>
 * <li>The <code>jetty.home</code> system property</li>
 * <li>The <code>JETTY_HOME</code> environment variable</li>
 * <li>The working directory hierarchy with subdirectory <code>jetty-distribution/target/distribution</code></li>
 * </ul>
 */
public class JettyDistribution
{
    private static final Logger LOG = LoggerFactory.getLogger(JettyDistribution.class);
    public static final Path DISTRIBUTION;

    static
    {
        Path jettyHome = asDirectory(System.getProperty("jetty.home"));
        LOG.debug("JettyDistribution(prop(jetty.home)) = {}", jettyHome);
        if (jettyHome == null)
        {
            jettyHome = asDirectory(System.getenv().get("JETTY_HOME"));
            LOG.debug("JettyDistribution(env(JETTY_HOME)) = {}", jettyHome);
        }

        Path distro = null;

        if (jettyHome != null)
        {
            Path parent = jettyHome.getParent();
            if (hasDemoBase(parent))
            {
                distro = parent;
            }
        }

        if (distro == null)
        {
            try
            {
                Path working = Paths.get(System.getProperty("user.dir"));
                Path dir = null;
                LOG.debug("JettyDistribution(prop(user.dir)) = {}", working);
                while (dir == null && working != null)
                {
                    dir = asDirectory(working.resolve("jetty-distribution/target/distribution").toString());
                    if (dir != null && hasDemoBase(dir))
                    {
                        distro = dir;
                    }
                    // try one parent up
                    working = working.getParent();
                }
                if (LOG.isDebugEnabled())
                    LOG.debug("JettyDistribution(working.resolve(...)) = {}", distro);
            }
            catch (Throwable th)
            {
                LOG.warn("Unable to resolve Jetty Distribution location", th);
            }
        }

        if (distro == null)
        {
            LOG.info("JettyDistribution() FAILURE: NOT FOUND");
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("JettyDistribution() FOUND = {}", distro);
        }
        DISTRIBUTION = distro;
    }

    private static boolean hasDemoBase(Path path)
    {
        Path demoBase = path.resolve("demo-base");
        return Files.exists(demoBase) && Files.isDirectory(demoBase);
    }

    private static Path asDirectory(String path)
    {
        try
        {
            if (path == null)
            {
                return null;
            }

            if (StringUtil.isBlank(path))
            {
                LOG.debug("asDirectory {} is blank", path);
                return null;
            }

            Path dir = Paths.get(path);
            if (!Files.exists(dir))
            {
                LOG.debug("asDirectory {} does not exist", path);
                return null;
            }

            if (!Files.isDirectory(dir))
            {
                LOG.debug("asDirectory {} is not a directory", path);
                return null;
            }

            LOG.debug("asDirectory {}", dir);
            return dir.toAbsolutePath();
        }
        catch (Exception e)
        {
            LOG.trace("IGNORED", e);
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
