//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.nio.file.Paths;
import javax.servlet.ServletContext;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ServletContextTmpAttributeTest
{

    /**
     * ServletContext.TEMPDIR has <code>null</code> value
     * but webappContent#tempDirectory is created under <code>java.io.tmpdir</code>
     */
    @Test
    public void attributeWithNullValue() throws Exception
    {
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setAttribute(ServletContext.TEMPDIR, null);
        webInfConfiguration.resolveTempDirectory(webAppContext);
        File tmp = webAppContext.getTempDirectory();
        assertThat("webAppContext temp directory parent is java.io.tmpdir",
                    tmp.getParentFile(),
                    is(new File(System.getProperty("java.io.tmpdir"))));
    }

    /**
     * ServletContext.TEMPDIR as String to valid directory
     */
    @Test
    public void attributeWithStringValue() throws Exception
    {
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        WebAppContext webAppContext = new WebAppContext();
        Path tmp = Files.createTempDirectory("jetty_test");
        webAppContext.setAttribute(ServletContext.TEMPDIR, tmp.toString());
        webInfConfiguration.resolveTempDirectory(webAppContext);
        File temp = webAppContext.getTempDirectory();
        assertThat("webAppContext is the temp directory created",
                   temp.toPath(),
                   is(tmp));
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
     * ServletContext.TEMPDIR as File to valid directory
     */
    @Test
    public void attributeWithValidFileDirectoryValue() throws Exception
    {
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        WebAppContext webAppContext = new WebAppContext();
        Path tmp = Files.createTempDirectory("jetty_test");
        webAppContext.setAttribute(ServletContext.TEMPDIR, tmp.toFile());
        webInfConfiguration.resolveTempDirectory(webAppContext);
        File temp = webAppContext.getTempDirectory();
        assertThat("webAppContext is the temp directory created",
                   temp.toPath(),
                   is(tmp));
    }

    /**
     * ServletContext.TEMPDIR as Path to valid directory
     */
    @Test
    public void attributeWithValidPathDirectoryValue() throws Exception
    {
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        WebAppContext webAppContext = new WebAppContext();
        Path tmp = Files.createTempDirectory("jetty_test");
        webAppContext.setAttribute(ServletContext.TEMPDIR, tmp);
        // FIXME we should have an exception here
        webInfConfiguration.resolveTempDirectory(webAppContext);
    }

    /**
     * ServletContext.TEMPDIR has invalid <code>String</code> directory value
     * IllegalStateException
     */
    @Test
    public void attributeWithInvalidStringValue() throws Exception
    {
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setAttribute(ServletContext.TEMPDIR, "/French/Cheese/Rocks");
        assertThrows(IllegalStateException.class, () -> webInfConfiguration.resolveTempDirectory(webAppContext));
    }

    /**
     * ServletContext.TEMPDIR has invalid <code>String</code> directory value (wrong permission to write into it)
     * IllegalStateException
     */
    @Disabled("will fail if executed as root or super power user so Disabled it")
    public void attributeWithInvalidPermissionStringValue() throws Exception
    {
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setAttribute(ServletContext.TEMPDIR, "/var/foo_jetty");
        assertThrows(IllegalStateException.class, () -> webInfConfiguration.resolveTempDirectory(webAppContext));
    }

    /**
     * ServletContext.TEMPDIR as File to a File
     */
    @Test
    public void attributeWithValidFileWithFileValue() throws Exception
    {
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        WebAppContext webAppContext = new WebAppContext();
        Path tmp = Files.createTempFile("jetty_test", "file_tmp");
        webAppContext.setAttribute(ServletContext.TEMPDIR, tmp.toFile());
        webInfConfiguration.resolveTempDirectory(webAppContext);
        File temp = webAppContext.getTempDirectory();
        assertThat("webAppContext is the temp directory created",
                   temp.toPath(),
                   is(tmp));
    }

    /**
     * ServletContext.TEMPDIR as File to a non existent directory
     */
    @Test
    public void attributeWithValidFileasDirectoryNonExistentValue() throws Exception
    {
        WebInfConfiguration webInfConfiguration = new WebInfConfiguration();
        WebAppContext webAppContext = new WebAppContext();
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir"), "foo_test_tmp");
        Files.createDirectories(tmp);
        Files.deleteIfExists(tmp);
        webAppContext.setAttribute(ServletContext.TEMPDIR, tmp.toFile());
        webInfConfiguration.resolveTempDirectory(webAppContext);
        File temp = webAppContext.getTempDirectory();
        assertThat("webAppContext is the temp directory created",
                   temp.toPath(),
                   is(tmp));
    }
}
