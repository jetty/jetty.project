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
import java.nio.file.Path;

import jakarta.servlet.ServletContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.PathMatchers;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.io.FileMatchers.anExistingDirectory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(WorkDirExtension.class)
public class TempDirTest
{

    private Server _server;

    @AfterEach
    public void afterEach() throws Exception
    {
        if (_server != null)
            _server.stop();
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
        _server = new Server();
        WebAppContext webAppContext = new WebAppContext();
        _server.setHandler(webAppContext);
        Path tmpDir = jettyBase.resolve("foo_did_not_exist");
        assertThat(tmpDir.toFile(), not(anExistingDirectory()));

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
        _server = new Server();
        webAppContext.setServer(_server);
        Path tmpDir = jettyBase.resolve("temp_test");
        FS.ensureDirExists(tmpDir);
        _server.setTempDirectory(tmpDir.toFile());

        // Test we have correct value as the webapp temp directory.
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        webInfConfiguration.resolveTempDirectory(webAppContext);
        File tempDirectory = webAppContext.getTempDirectory();
        assertThat(tempDirectory, anExistingDirectory());
        assertThat(tempDirectory.getParentFile().toPath(), is(tmpDir));
        assertThat(webAppContext.getAttribute(ServletContext.TEMPDIR), is(webAppContext.getTempDirectory()));
    }

    /**
     * <code>${jetty.base}</code> directory exists and has a subdirectory called work
     */
    @Test
    public void jettyBaseWorkExists(WorkDir workDirExt) throws Exception
    {
        Path jettyBase = workDirExt.getEmptyPathDir();
        Path workDir = jettyBase.resolve("work");
        FS.ensureDirExists(workDir);
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        _server = new Server();
        _server.setTempDirectory(workDir.toFile());
        WebAppContext webAppContext = new WebAppContext();
        _server.setHandler(webAppContext);
        webInfConfiguration.resolveTempDirectory(webAppContext);
        assertThat(webAppContext.getTempDirectory().getParentFile().toPath(), PathMatchers.isSame(workDir));
        assertThat(webAppContext.getAttribute(ServletContext.TEMPDIR), is(webAppContext.getTempDirectory()));
    }

    @Test
    public void testTempDirDeleted(WorkDir workDir) throws Exception
    {
        // Create war on the fly
        Path testWebappDir = MavenTestingUtils.getProjectDirPath("src/test/webapp");

        //Let jetty create the tmp dir on the fly
        _server = new Server();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath("/");
        webAppContext.setWar(testWebappDir.toFile().getAbsolutePath());
        _server.setHandler(webAppContext);
        _server.start();
        File tempDirectory = webAppContext.getTempDirectory();
        assertThat(webAppContext.getAttribute(ServletContext.TEMPDIR), is(webAppContext.getTempDirectory()));
        _server.stop();
        assertNull(webAppContext.getTempDirectory());
        assertThat(tempDirectory, not(anExistingDirectory()));
    }

    @Test
    public void testExplicitTempDir(WorkDir workDir) throws Exception
    {
        Path myTempDir = workDir.getEmptyPathDir().resolve("my-temp-dir");
        FS.ensureDirExists(myTempDir);

        // Create war on the fly
        Path testWebappDir = MavenTestingUtils.getProjectDirPath("src/test/webapp");

        //Tell jetty what the temp dir is for the webapp
        _server = new Server();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath("/");
        webAppContext.setWar(testWebappDir.toFile().getAbsolutePath());
        webAppContext.setTempDirectory(myTempDir.toFile());
        _server.setHandler(webAppContext);
        _server.start();
        File tempDirectory = webAppContext.getTempDirectory();
        assertThat(webAppContext.getAttribute(ServletContext.TEMPDIR), is(tempDirectory));
        assertThat(tempDirectory.toPath(), is(myTempDir));
    }

    @Test
    public void testFreshTempDir(WorkDir workDir) throws Exception
    {
        // Create war on the fly
        Path testWebappDir = MavenTestingUtils.getProjectDirPath("src/test/webapp");

        //Test that if jetty is creating a tmp dir for the webapp, it is different on
        //restart
        _server = new Server();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath("/");
        webAppContext.setWar(testWebappDir.toFile().getAbsolutePath());
        _server.setHandler(webAppContext);
        _server.start();
        File tempDirectory = webAppContext.getTempDirectory();
        webAppContext.stop();
        assertNull(webAppContext.getTempDirectory());
        webAppContext.start();
        assertThat(tempDirectory.toPath(), not(PathMatchers.isSame(webAppContext.getTempDirectory().toPath())));
    }

    @Test
    public void testSameTempDir(WorkDir workDir) throws Exception
    {
        // Create war on the fly
        Path testWebappDir = MavenTestingUtils.getProjectDirPath("src/test/webapp");

        //Test that if we explicitly configure the temp dir, it is the same after restart
        _server = new Server();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath("/");
        Path configuredTmpDir = workDir.getPath().resolve("tmp");
        webAppContext.setTempDirectory(configuredTmpDir.toFile());
        webAppContext.setWar(testWebappDir.toFile().getAbsolutePath());
        _server.setHandler(webAppContext);
        _server.start();
        File tempDirectory = webAppContext.getTempDirectory();
        assertThat(tempDirectory.toPath(), PathMatchers.isSame(configuredTmpDir));
        webAppContext.stop();
        assertNotNull(webAppContext.getTempDirectory());
        webAppContext.start();
        assertThat(tempDirectory.toPath(), PathMatchers.isSame(webAppContext.getTempDirectory().toPath()));
    }
}
