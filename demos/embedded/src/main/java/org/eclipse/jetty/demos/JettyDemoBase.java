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

package org.eclipse.jetty.demos;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A utility test class to locate a Jetty Base for testing purposes by searching:
 * <ul>
 * <li>The <code>jetty.base</code> system property</li>
 * <li>The <code>JETTY_BASE</code> environment variable</li>
 * <li>Creating a {@code ${java.io.tmpDir}/jetty-demo-base/} to work with</li>
 * </ul>
 */
public class JettyDemoBase
{
    private static final Logger LOG = LoggerFactory.getLogger(JettyDemoBase.class);
    public static final Path JETTY_BASE;

    static
    {
        Path jettyBase = asDirectory(System.getProperty("jetty.base"));
        LOG.debug("JettyDemobase(prop(jetty.home)) = {}", jettyBase);
        if (jettyBase == null)
        {
            jettyBase = asDirectory(System.getenv().get("JETTY_BASE"));
            LOG.debug("JettyHome(env(JETTY_BASE)) = {}", jettyBase);
        }

        if (jettyBase == null || !Files.exists(jettyBase.resolve("start.d/demo.ini")))
        {
            // Create the demo-base in java.io.tmpdir
            ClassLoader origClassLoader = Thread.currentThread().getContextClassLoader();
            try
            {
                Path jettyHome = JettyHome.get();
                jettyBase = Files.createTempDirectory("jetty-base");

                Path startJar = jettyHome.resolve("start.jar");

                URLClassLoader urlClassLoader = new URLClassLoader(new URL[]{
                    startJar.toUri().toURL()
                });

                Thread.currentThread().setContextClassLoader(urlClassLoader);

                String pomRef = "META-INF/maven/org.eclipse.jetty/jetty-start/pom.properties";
                URL urlPom = urlClassLoader.findResource(pomRef);
                if (urlPom == null)
                    throw new IllegalStateException("Unable to find " + pomRef);

                String jettyVersion = null;

                try (InputStream input = urlPom.openStream())
                {
                    Properties pomProps = new Properties();
                    pomProps.load(input);
                    jettyVersion = pomProps.getProperty("version");
                }

                Class<?> mainClass = urlClassLoader.loadClass("org.eclipse.jetty.start.Main");
                Method mainMethod = mainClass.getMethod("main", String[].class);

                String[] args = new String[]{
                    "jetty.version=" + jettyVersion,
                    "jetty.home=" + jettyHome.toString(),
                    "jetty.base=" + jettyBase.toString(),
                    "--add-modules=logging-jetty,demo"
                };

                LOG.info("Creating DemoBase in {}", jettyBase);
                mainMethod.invoke(mainClass, new Object[]{args});

                Path logsDir = jettyBase.resolve("logs");
                if (!Files.exists(logsDir))
                    Files.createDirectory(logsDir);

                if (LOG.isDebugEnabled())
                    LOG.debug("JettyHome(working.resolve(...)) = {}", jettyBase);
            }
            catch (Throwable th)
            {
                LOG.warn("Unable to resolve Jetty Distribution location", th);
            }
            finally
            {
                Thread.currentThread().setContextClassLoader(origClassLoader);
            }
        }

        JETTY_BASE = jettyBase;
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
        if (JETTY_BASE == null)
            throw new RuntimeException("jetty-base not found");
        return JETTY_BASE;
    }

    public static Path resolve(String path)
    {
        return get().resolve(path);
    }

    public static void main(String... arg)
    {
        System.err.println("Jetty Base is " + JETTY_BASE);
    }
}
