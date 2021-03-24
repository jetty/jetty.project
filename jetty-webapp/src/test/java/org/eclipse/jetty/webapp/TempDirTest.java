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

package org.eclipse.jetty.webapp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.servlet.ServletContext;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
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

    public void setupServer()
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        File testWebAppDir = MavenTestingUtils.getProjectDir("src/test/webapp");
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
    public void attributeWithEmptyStringValue() throws Exception
    {
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setAttribute(ServletContext.TEMPDIR, "");
        assertThrows(IllegalStateException.class, () -> webInfConfiguration.resolveTempDirectory(webAppContext));
    }

    /**
     * ServletContext.TEMPDIR has value which is not a known type.
     * IllegalStateException
     */
    @Test
    public void attributeWithInvalidValue() throws Exception
    {
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setAttribute(ServletContext.TEMPDIR, new Object());
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
     * ServletContext.TEMPDIR has invalid <code>String</code> directory value (wrong permission to write into it)
     * IllegalStateException
     */
    @Disabled("Jenkins will run as root so we do have permission to write to this directory.")
    public void attributeWithInvalidPermissions() throws Exception
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
