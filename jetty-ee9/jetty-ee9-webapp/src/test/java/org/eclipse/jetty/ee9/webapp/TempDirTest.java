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
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.ServletContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.PathMatchers;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        Server server = new Server();
        server.setTempDirectory(workDir.toFile());
        WebAppContext webAppContext = new WebAppContext();
        server.setHandler(webAppContext);
        webInfConfiguration.resolveTempDirectory(webAppContext);
        assertThat(webAppContext.getTempDirectory().getParentFile().toPath(), PathMatchers.isSame(workDir));
        assertThat(webAppContext.getAttribute(ServletContext.TEMPDIR), is(webAppContext.getTempDirectory()));
    }

    @Test
    public void testTempDirDeleted(WorkDir workDir) throws Exception
    {
        // Create war on the fly
        Path testWebappDir = MavenTestingUtils.getProjectDirPath("src/test/webapp");
        Path warFile = workDir.getEmptyPathDir().resolve("test.war");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI uri = URI.create("jar:" + warFile.toUri().toASCIIString());
        // Use ZipFS so that we can create paths that are just "/"
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            Path root = zipfs.getPath("/");
            IO.copyDir(testWebappDir, root);
        }

        //Let jetty create the tmp dir on the fly
        Server server = new Server();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath("/");
        webAppContext.setWarResource(webAppContext.getResourceFactory().newResource(warFile));
        server.setHandler(webAppContext);
        server.start();
        File tempDirectory = webAppContext.getTempDirectory();
        assertThat(webAppContext.getAttribute(ServletContext.TEMPDIR), is(webAppContext.getTempDirectory()));
        server.stop();
        assertNull(webAppContext.getTempDirectory());
        assertThat("Temp dir exists", !Files.exists(tempDirectory.toPath()));
    }

    @Test
    public void testExplicitTempDir(WorkDir workDir) throws Exception
    {
        Path jettyBase = workDir.getEmptyPathDir();
        Path myTempDir = jettyBase.resolve("my-temp-dir");
        FS.ensureDirExists(myTempDir);

        // Create war on the fly
        Path testWebappDir = MavenTestingUtils.getProjectDirPath("src/test/webapp");
        Path warFile = workDir.getEmptyPathDir().resolve("test.war");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI uri = URI.create("jar:" + warFile.toUri().toASCIIString());
        // Use ZipFS so that we can create paths that are just "/"
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            Path root = zipfs.getPath("/");
            IO.copyDir(testWebappDir, root);
        }

        //Tell jetty what the temp dir is for the webapp
        Server server = new Server();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath("/");
        webAppContext.setWarResource(webAppContext.getResourceFactory().newResource(warFile));
        webAppContext.setTempDirectory(myTempDir.toFile());
        server.setHandler(webAppContext);
        server.start();
        File tempDirectory = webAppContext.getTempDirectory();
        assertThat(webAppContext.getAttribute(ServletContext.TEMPDIR), is(tempDirectory));
        assertThat(tempDirectory.toPath(), is(myTempDir));
        server.stop();
    }


    @Test
    public void testFreshTempDir(WorkDir workDir) throws Exception
    {
        // Create war on the fly
        Path testWebappDir = MavenTestingUtils.getProjectDirPath("src/test/webapp");
        Path warFile = workDir.getEmptyPathDir().resolve("test.war");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI uri = URI.create("jar:" + warFile.toUri().toASCIIString());
        // Use ZipFS so that we can create paths that are just "/"
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            Path root = zipfs.getPath("/");
            IO.copyDir(testWebappDir, root);
        }

        //Test that if jetty is creating a tmp dir for the webapp, it is different on
        //restart
        Server server = new Server();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath("/");
        webAppContext.setWarResource(webAppContext.getResourceFactory().newResource(warFile));
        server.setHandler(webAppContext);
        server.start();
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
        Path warFile = workDir.getEmptyPathDir().resolve("test.war");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI uri = URI.create("jar:" + warFile.toUri().toASCIIString());
        // Use ZipFS so that we can create paths that are just "/"
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            Path root = zipfs.getPath("/");
            IO.copyDir(testWebappDir, root);
        }

        //Test that if we explicitly configure the temp dir, it is the same after restart
        Server server = new Server();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath("/");
        Path configuredTmpDir = workDir.getPath().resolve("tmp");
        webAppContext.setTempDirectory(configuredTmpDir.toFile());
        webAppContext.setWarResource(webAppContext.getResourceFactory().newResource(warFile));
        server.setHandler(webAppContext);
        server.start();
        File tempDirectory = webAppContext.getTempDirectory();
        assertThat(tempDirectory.toPath(), PathMatchers.isSame(configuredTmpDir));
        webAppContext.stop();
        assertNotNull(webAppContext.getTempDirectory());
        webAppContext.start();
        assertThat(tempDirectory.toPath(), PathMatchers.isSame(webAppContext.getTempDirectory().toPath()));
    }
}
