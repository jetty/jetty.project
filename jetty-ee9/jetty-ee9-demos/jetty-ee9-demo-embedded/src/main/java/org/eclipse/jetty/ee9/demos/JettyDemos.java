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

package org.eclipse.jetty.ee9.demos;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A utility test class to locate the Jetty Demo build contents.
 * <p>
 * Looking for content in the /demos/ directory.
 * </p>
 */
public class JettyDemos
{
    private static final Logger LOG = LoggerFactory.getLogger(JettyDemos.class);
    private static final Path JETTY_DEMOS_DIR;
    private static final String VERSION;

    static
    {
        Path demosDir = asDirectory(System.getProperty("jetty.demos"));
        LOG.debug("JettyDemos(prop(jetty.demos)) = {}", demosDir);
        if (demosDir == null)
        {
            demosDir = asDirectory(System.getenv().get("JETTY_DEMOS"));
            LOG.debug("JettyDemos(env(JETTY_DEMOS)) = {}", demosDir);
        }

        if (demosDir == null || !Files.exists(demosDir.resolve("pom.xml")))
        {
            try
            {
                Path working = Paths.get(System.getProperty("user.dir"));
                Path dir = null;
                LOG.debug("JettyDemos(prop(user.dir)) = {}", working);
                while (dir == null && working != null)
                {
                    dir = asDirectory(working.resolve("jetty-ee9-demos").toString());
                    if (dir != null && Files.exists(dir.resolve("pom.xml")))
                    {
                        demosDir = dir;
                    }
                    // try one parent up
                    working = working.getParent();
                }
                if (LOG.isDebugEnabled())
                    LOG.debug("JettyDemos(working.resolve(...)) = {}", demosDir);
            }
            catch (Throwable th)
            {
                LOG.warn("Unable to resolve Jetty Demos location", th);
            }
        }

        JETTY_DEMOS_DIR = demosDir;

        String version = "unknown";
        Path pomFile = demosDir.resolve("pom.xml");
        try (Stream<String> lineStream = Files.lines(pomFile))
        {
            String versionLine = lineStream
                .filter((line) -> line.contains("<version>"))
                .findFirst()
                .orElseThrow(() ->
                {
                    throw new RuntimeException("Unable to find <version> in " + pomFile);
                });

            version = versionLine.replaceAll("<[^>]*>", "").trim();
        }
        catch (IOException e)
        {
            LOG.warn("Unable to find <version> in " + pomFile, e);
        }

        VERSION = version;
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

    private static Path get()
    {
        if (JETTY_DEMOS_DIR == null)
            throw new RuntimeException("jetty /demos/ dir not found");
        return JETTY_DEMOS_DIR;
    }

    public static Path find(String path) throws FileNotFoundException
    {
        String expandedPath = path.replaceAll("@VER@", VERSION);
        Path result = get().resolve(expandedPath);
        if (!Files.exists(result))
        {
            throw new FileNotFoundException(result.toString());
        }
        return result;
    }

    public static void main(String... arg)
    {
        System.err.println("Jetty Demos Dir is " + JETTY_DEMOS_DIR);
    }
}
