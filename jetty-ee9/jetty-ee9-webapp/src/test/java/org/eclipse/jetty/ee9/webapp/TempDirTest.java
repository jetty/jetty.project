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

package org.eclipse.jetty.ee9.webapp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.servlet.ServletContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.PathMatchers;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class TempDirTest
{
    public static void tearDown()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    /**
     * Test ServletContext.TEMPDIR as valid directory with types File, String and Path.
     */
    @ParameterizedTest
    @ValueSource(strings = {"File", "String", "Path"})
    public void attributeWithValidDirectory(String type, WorkDir workDir) throws Exception
    {
        Path jettyBase = workDir.getEmptyPathDir();
        WebAppContext webAppContext = new WebAppContext();
        Path tmpDir = jettyBase.resolve("temp");
        FS.ensureDirExists(tmpDir);
        switch (type)
        {
            case "File" -> webAppContext.setAttribute(ServletContext.TEMPDIR, tmpDir.toFile());
            case "String" -> webAppContext.setAttribute(ServletContext.TEMPDIR, tmpDir.toString());
            case "Path" -> webAppContext.setAttribute(ServletContext.TEMPDIR, tmpDir);
            default -> throw new IllegalStateException();
        }

        // Test we have correct value as the webapp temp directory.
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        webInfConfiguration.resolveTempDirectory(webAppContext);
        Path webappTempDir = webAppContext.getTempDirectory().toPath();
        assertEquals(tmpDir, webappTempDir);
    }

    /**
     * ServletContext.TEMPDIR as File to a non existent directory.
     */
    @ParameterizedTest
    @ValueSource(strings = {"File", "String", "Path"})
    public void attributeWithNonExistentDirectory(String type, WorkDir workDir) throws Exception
    {
        Path jettyBase = workDir.getEmptyPathDir();
        Server server = new Server();
        WebAppContext webAppContext = new WebAppContext();
        server.setHandler(webAppContext);
        Path tmpDir = jettyBase.resolve("foo_did_not_exist");
        assertFalse(Files.exists(tmpDir));

        switch (type)
        {
            case "File" -> webAppContext.setAttribute(ServletContext.TEMPDIR, tmpDir.toFile());
            case "String" -> webAppContext.setAttribute(ServletContext.TEMPDIR, tmpDir.toString());
            case "Path" -> webAppContext.setAttribute(ServletContext.TEMPDIR, tmpDir);
            default -> throw new IllegalStateException();
        }

        // Test we have correct value as the webapp temp directory.
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        webInfConfiguration.resolveTempDirectory(webAppContext);
        Path webappTmpDir = webAppContext.getTempDirectory().toPath();
        assertThat(webappTmpDir, is(tmpDir));
    }

    /**
     * Test Server.setTempDirectory as valid directory
     */
    @Test
    public void serverTempDirAttributeWithValidDirectory(WorkDir workDir) throws Exception
    {
        Path jettyBase = workDir.getEmptyPathDir();
        WebAppContext webAppContext = new WebAppContext();
        Server server = new Server();
        webAppContext.setServer(server);
        Path tmpDir = jettyBase.resolve("temp_test");
        FS.ensureDirExists(tmpDir);
        server.setTempDirectory(tmpDir.toFile());

        // Test we have correct value as the webapp temp directory.
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        webInfConfiguration.resolveTempDirectory(webAppContext);
        File tempDirectory = webAppContext.getTempDirectory();
        assertTrue(tempDirectory.exists());
        assertThat(tempDirectory.getParentFile().toPath(), is(tmpDir));
    }

    /**
     * <code>${jetty.base}</code> directory exists and has a subdirectory called work
     * so webappContent#tempDirectory is created under <code>java.io.tmpdir</code>
     */
    @Test
    public void jettyBaseWorkExists(WorkDir workDirExt) throws Exception
    {
        Path jettyBase = workDirExt.getEmptyPathDir();
        Path workDir = jettyBase.resolve("work");
        FS.ensureDirExists(workDir);
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        Server server = new Server();
        server.setTempDirectory(workDir.toFile());
        WebAppContext webAppContext = new WebAppContext();
        server.setHandler(webAppContext);
        webInfConfiguration.resolveTempDirectory(webAppContext);
        assertThat(webAppContext.getTempDirectory().getParentFile().toPath(), PathMatchers.isSame(workDir));
    }
}
