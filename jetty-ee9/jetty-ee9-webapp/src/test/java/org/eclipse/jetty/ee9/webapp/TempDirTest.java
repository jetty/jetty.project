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

package org.eclipse.jetty.ee9.webapp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.servlet.ServletContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TempDirTest
{
    private Server server;
    private WebAppContext webapp;
    private File jettyBase;

    @BeforeEach
    public void before()
    {
        jettyBase = MavenTestingUtils.getTargetTestingDir(TempDirTest.class.getSimpleName());
        FS.ensureEmpty(jettyBase);
    }

    public void setupServer()
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        File testWebAppDir = MavenTestingUtils.getTargetPath("test-classes/webapp").toFile();
        webapp = new WebAppContext();
        webapp.setContextPath("/");
        webapp.setWar(testWebAppDir.getAbsolutePath());
        server.setHandler(webapp);
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        if (server != null)
            server.stop();
    }

    /**
     * ServletContext.TEMPDIR has <code>null</code> value
     * so webappContent#tempDirectory is created under <code>java.io.tmpdir</code>
     */
    @Test
    public void attributeWithNullValue() throws Exception
    {
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setAttribute(ServletContext.TEMPDIR, null);
        webInfConfiguration.resolveTempDirectory(webAppContext);
        assertThat(webAppContext.getTempDirectory().getParent(), is(System.getProperty("java.io.tmpdir")));
    }

    /**
     * ServletContext.TEMPDIR has <code>""</code> value
     * IllegalStateException
     */
    @Test
    public void attributeWithEmptyStringValue()
    {
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setAttribute(ServletContext.TEMPDIR, "");
        assertThrows(IllegalStateException.class, () -> webInfConfiguration.resolveTempDirectory(webAppContext));
    }

    /**
     * Test ServletContext.TEMPDIR as valid directory with types File, String and Path.
     */
    @ParameterizedTest
    @ValueSource(strings = {"File", "String", "Path"})
    public void attributeWithValidDirectory(String type) throws Exception
    {
        WebAppContext webAppContext = new WebAppContext();
        Path tmpDir = Files.createTempDirectory("jetty_test");
        switch (type)
        {
            case "File":
                webAppContext.setAttribute(ServletContext.TEMPDIR, tmpDir.toFile());
                break;
            case "String":
                webAppContext.setAttribute(ServletContext.TEMPDIR, tmpDir.toString());
                break;
            case "Path":
                webAppContext.setAttribute(ServletContext.TEMPDIR, tmpDir);
                break;
            default:
                throw new IllegalStateException();
        }

        // Test we have correct value as the webapp temp directory.
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        webInfConfiguration.resolveTempDirectory(webAppContext);
        assertThat(webAppContext.getTempDirectory().toPath(), is(tmpDir));
    }

    /**
     * ServletContext.TEMPDIR as File to a non existent directory.
     */
    @ParameterizedTest
    @ValueSource(strings = {"File", "String", "Path"})
    public void attributeWithNonExistentDirectory(String type) throws Exception
    {
        WebAppContext webAppContext = new WebAppContext();
        Path tmpDir = Files.createTempDirectory("jetty_test").resolve("foo_test_tmp");
        Files.deleteIfExists(tmpDir);
        assertFalse(Files.exists(tmpDir));
        switch (type)
        {
            case "File":
                webAppContext.setAttribute(ServletContext.TEMPDIR, tmpDir.toFile());
                break;
            case "String":
                webAppContext.setAttribute(ServletContext.TEMPDIR, tmpDir.toString());
                break;
            case "Path":
                webAppContext.setAttribute(ServletContext.TEMPDIR, tmpDir);
                break;
            default:
                throw new IllegalStateException();
        }

        // Test we have correct value as the webapp temp directory.
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        webInfConfiguration.resolveTempDirectory(webAppContext);
        Path webappTmpDir = webAppContext.getTempDirectory().toPath();
        assertThat(webappTmpDir, is(tmpDir));
        assertTrue(Files.exists(webappTmpDir));
    }

    /**
     * WebAppContext.BASETEMPDIR has <code>null</code> value
     * so webappContent#tempDirectory is created under <code>java.io.tmpdir</code>
     */
    @Test
    public void baseTempDirAttributeWithNullValue() throws Exception
    {
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setAttribute(WebAppContext.BASETEMPDIR, null);
        webInfConfiguration.resolveTempDirectory(webAppContext);
        assertThat(webAppContext.getTempDirectory().getParent(), is(System.getProperty("java.io.tmpdir")));
    }

    /**
     * WebAppContext.BASETEMPDIR has <code>""</code> value
     * IllegalStateException
     */
    @Test
    public void baseTempDirAttributeWithEmptyStringValue()
    {
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setAttribute(WebAppContext.BASETEMPDIR, "");
        assertThrows(IllegalStateException.class, () -> webInfConfiguration.resolveTempDirectory(webAppContext));
    }

    /**
     * Test WebAppContext.BASETEMPDIR as valid directory with types File, String and Path.
     */
    @ParameterizedTest
    @ValueSource(strings = {"File", "String", "Path"})
    public void baseTempDirAttributeWithValidDirectory(String type) throws Exception
    {
        WebAppContext webAppContext = new WebAppContext();
        Path tmpDir = Files.createTempDirectory("jetty_test");
        switch (type)
        {
            case "File":
                webAppContext.setAttribute(WebAppContext.BASETEMPDIR, tmpDir.toFile());
                break;
            case "String":
                webAppContext.setAttribute(WebAppContext.BASETEMPDIR, tmpDir.toString());
                break;
            case "Path":
                webAppContext.setAttribute(WebAppContext.BASETEMPDIR, tmpDir);
                break;
            default:
                throw new IllegalStateException();
        }

        // Test we have correct value as the webapp temp directory.
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        webInfConfiguration.resolveTempDirectory(webAppContext);
        File tempDirectory = webAppContext.getTempDirectory();
        assertTrue(tempDirectory.exists());
        assertThat(tempDirectory.getParentFile().toPath(), is(tmpDir));
    }

    /**
     * WebAppContext.BASETEMPDIR as File to a non existent directory.
     */
    @ParameterizedTest
    @ValueSource(strings = {"File", "String", "Path"})
    public void baseTempDirAttributeWithNonExistentDirectory(String type) throws Exception
    {
        WebAppContext webAppContext = new WebAppContext();
        Path tmpDir = Files.createTempDirectory("jetty_test").resolve("foo_test_tmp");
        Files.deleteIfExists(tmpDir);
        assertFalse(Files.exists(tmpDir));
        switch (type)
        {
            case "File":
                webAppContext.setAttribute(WebAppContext.BASETEMPDIR, tmpDir.toFile());
                break;
            case "String":
                webAppContext.setAttribute(WebAppContext.BASETEMPDIR, tmpDir.toString());
                break;
            case "Path":
                webAppContext.setAttribute(WebAppContext.BASETEMPDIR, tmpDir);
                break;
            default:
                throw new IllegalStateException();
        }

        // The base temp directory must exist for it to be used, if it does not exist or is not writable it will throw ISE.
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        assertThrows(IllegalStateException.class, () -> webInfConfiguration.resolveTempDirectory(webAppContext));
        assertFalse(Files.exists(tmpDir));
    }

    /**
     * <code>${jetty.base}</code> exists but has no work subdirectory called work
     * so webappContent#tempDirectory is created under <code>java.io.tmpdir</code>
     */
    @Test
    public void jettyBaseWorkDoesNotExist() throws Exception
    {
        Path workDir = jettyBase.toPath().resolve("work");
        FS.ensureDirExists(jettyBase);
        FS.ensureDeleted(workDir);
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        WebAppContext webAppContext = new WebAppContext();
        System.setProperty("jetty.base", jettyBase.getAbsolutePath());
        webInfConfiguration.resolveTempDirectory(webAppContext);
        assertThat(webAppContext.getTempDirectory().getParent(), is(System.getProperty("java.io.tmpdir")));
    }

    /**
     * <code>${jetty.base}</code> directory exists and has a subdirectory called work
     * so webappContent#tempDirectory is created under <code>java.io.tmpdir</code>
     */
    @Test
    public void jettyBaseWorkExists() throws Exception
    {
        Path workDir = jettyBase.toPath().resolve("work");
        FS.ensureDirExists(jettyBase);
        FS.ensureDirExists(workDir);
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        WebAppContext webAppContext = new WebAppContext();
        System.setProperty("jetty.base", jettyBase.getAbsolutePath());
        webInfConfiguration.resolveTempDirectory(webAppContext);
        assertThat(webAppContext.getTempDirectory().getParent(), is(workDir.toString()));
    }

    /**
     * ServletContext.TEMPDIR has invalid <code>String</code> directory value (wrong permission to write into it)
     *
     * Note that if run in the CI environment, the test will fail, because it runs as root,
     * so we _will_ have permission to write to this directory.
     */
    @DisabledIfSystemProperty(named = "env", matches = "ci")
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Test/Temp directory is always writable")
    @Test
    public void attributeWithInvalidPermissions()
    {
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setAttribute(ServletContext.TEMPDIR, "/var/foo_jetty");
        assertThrows(IllegalStateException.class, () -> webInfConfiguration.resolveTempDirectory(webAppContext));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testDefaultTempDirectory(boolean persistTempDir) throws Exception
    {
        setupServer();
        webapp.setPersistTempDirectory(persistTempDir);

        // Temp Directory Initially isn't set until started.
        File tempDirectory = webapp.getTempDirectory();
        assertNull(tempDirectory);

        // Once server is started the WebApp temp directory exists and is valid directory.
        server.start();
        tempDirectory = webapp.getTempDirectory();
        assertNotNull(tempDirectory);
        assertTrue(tempDirectory.exists());
        assertTrue(tempDirectory.isDirectory());

        // Once server is stopped the WebApp temp should be deleted if persistTempDir is false.
        server.stop();
        tempDirectory = webapp.getTempDirectory();
        assertThat(tempDirectory != null && tempDirectory.exists(), is(persistTempDir));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testPreDefinedTempDirectory(boolean persistTempDir) throws Exception
    {
        setupServer();
        webapp.setPersistTempDirectory(persistTempDir);

        // The temp directory is defined but has not been created.
        File webappTempDir = MavenTestingUtils.getTargetTestingPath("webappTempDir").toFile();
        IO.delete(webappTempDir);
        webapp.setTempDirectory(webappTempDir);
        assertThat(webapp.getTempDirectory(), is(webappTempDir));
        assertFalse(webappTempDir.exists());

        // Once server is started the WebApp temp directory exists and is valid directory.
        server.start();
        File tempDirectory = webapp.getTempDirectory();
        assertNotNull(tempDirectory);
        assertTrue(tempDirectory.exists());
        assertTrue(tempDirectory.isDirectory());

        // Once server is stopped the WebApp temp should be deleted if persistTempDir is false.
        server.stop();
        tempDirectory = webapp.getTempDirectory();
        assertThat(tempDirectory != null && tempDirectory.exists(), is(persistTempDir));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testPreExistingTempDirectory(boolean persistTempDir) throws Exception
    {
        setupServer();
        webapp.setPersistTempDirectory(persistTempDir);

        // The temp directory is defined and has already been created.
        File webappTempDir = MavenTestingUtils.getTargetTestingPath("webappTempDir").toFile();
        IO.delete(webappTempDir);
        if (!webappTempDir.exists())
            assertTrue(webappTempDir.mkdir());
        webapp.setTempDirectory(webappTempDir);
        assertThat(webapp.getTempDirectory(), is(webappTempDir));
        assertTrue(webappTempDir.exists());

        // Once server is started the WebApp temp directory exists and is valid directory.
        server.start();
        File tempDirectory = webapp.getTempDirectory();
        assertNotNull(tempDirectory);
        assertTrue(tempDirectory.exists());
        assertTrue(tempDirectory.isDirectory());

        // Once server is stopped the WebApp temp should be deleted if persistTempDir is false.
        server.stop();
        tempDirectory = webapp.getTempDirectory();
        assertThat(tempDirectory != null && tempDirectory.exists(), is(persistTempDir));
    }
}
