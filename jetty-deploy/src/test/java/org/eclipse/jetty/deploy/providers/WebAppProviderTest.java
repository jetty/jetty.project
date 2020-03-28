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

package org.eclipse.jetty.deploy.providers;

import java.io.File;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Disabled("See issue #1200")
@ExtendWith(WorkDirExtension.class)
public class WebAppProviderTest
{
    public WorkDir testdir;
    private static XmlConfiguredJetty jetty;
    private boolean symlinkSupported = false;

    @BeforeEach
    public void setupEnvironment() throws Exception
    {
        jetty = new XmlConfiguredJetty(testdir.getEmptyPathDir());
        jetty.addConfiguration("jetty.xml");
        jetty.addConfiguration("jetty-http.xml");
        jetty.addConfiguration("jetty-deploy-wars.xml");

        // Setup initial context
        jetty.copyWebapp("foo-webapp-1.war", "foo.war");

        // Make symlink
        Path pathWar3 = MavenTestingUtils.getTestResourcePathFile("webapps/foo-webapp-3.war");
        Path pathBar = jetty.getJettyDir("webapps/bar.war").toPath();
        try
        {
            Files.createSymbolicLink(pathBar, pathWar3);
            symlinkSupported = true;
        }
        catch (UnsupportedOperationException | FileSystemException e)
        {
            // if unable to create symlink, no point testing that feature
            // this is the path that Microsoft Windows takes.
            symlinkSupported = false;
        }

        // Should not throw an Exception
        jetty.load();

        // Start it
        jetty.start();
    }

    @AfterEach
    public void teardownEnvironment() throws Exception
    {
        // Stop jetty.
        jetty.stop();
    }

    @Test
    public void testStartupContext()
    {
        // Check Server for Handlers
        jetty.assertWebAppContextsExists("/bar", "/foo");

        File workDir = jetty.getJettyDir("workish");

        // Test for regressions
        assertDirNotExists("root of work directory", workDir, "webinf");
        assertDirNotExists("root of work directory", workDir, "jsp");

        // Test for correct behaviour
        assertTrue(hasJettyGeneratedPath(workDir, "foo.war"), "Should have generated directory in work directory: " + workDir);
    }

    @Test
    public void testStartupSymlinkContext()
    {
        assumeTrue(symlinkSupported);

        // Check for path
        File barLink = jetty.getJettyDir("webapps/bar.war");
        assertTrue(barLink.exists(), "bar.war link exists: " + barLink.toString());
        assertTrue(barLink.isFile(), "bar.war link isFile: " + barLink.toString());

        // Check Server for expected Handlers
        jetty.assertWebAppContextsExists("/bar", "/foo");

        // Test for expected work/temp directory behaviour
        File workDir = jetty.getJettyDir("workish");
        assertTrue(hasJettyGeneratedPath(workDir, "bar.war"), "Should have generated directory in work directory: " + workDir);
    }

    private static boolean hasJettyGeneratedPath(File basedir, String expectedWarFilename)
    {
        File[] paths = basedir.listFiles();
        if (paths != null)
        {
            for (File path : paths)
            {
                if (path.exists() && path.isDirectory() && path.getName().startsWith("jetty-") && path.getName().contains(expectedWarFilename))
                {
                    System.err.println("Found expected generated directory: " + path);
                    return true;
                }
            }
            System.err.println("did not find " + expectedWarFilename + " in " + Arrays.asList(paths));
        }
        return false;
    }

    public static void assertDirNotExists(String msg, File workDir, String subdir)
    {
        File dir = new File(workDir, subdir);
        assertFalse(dir.exists(), "Should not have " + subdir + " in " + msg + " - " + workDir);
    }
}
