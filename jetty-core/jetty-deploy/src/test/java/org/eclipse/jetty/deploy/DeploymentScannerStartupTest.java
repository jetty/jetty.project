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

package org.eclipse.jetty.deploy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import org.eclipse.jetty.deploy.test.TestContextHandler;
import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests {@link DeploymentScanner} as it starts up for the first time.
 */
@ExtendWith(WorkDirExtension.class)
public class DeploymentScannerStartupTest extends AbstractCleanEnvironmentTest
{
    public WorkDir testdir;
    private XmlConfiguredJetty jetty;

    @BeforeEach
    public void prepare() throws Exception
    {
        Path p = testdir.getEmptyPathDir();
        jetty = new XmlConfiguredJetty(p);

        Path resourceBase = jetty.getJettyBasePath().resolve("resourceBase");
        FS.ensureDirExists(resourceBase);
        jetty.setProperty("test.bar.resourceBase", resourceBase.toUri().toASCIIString());

        Files.writeString(resourceBase.resolve("text.txt"), "This is the resourceBase text");

        jetty.addConfiguration(MavenPaths.findTestResourceFile("jetty.xml"));
        jetty.addConfiguration(MavenPaths.findTestResourceFile("jetty-http.xml"));
        jetty.addConfiguration(MavenPaths.projectBase().resolve("src/main/config/etc/jetty-deployer-standard.xml"));
        jetty.addConfiguration(MavenPaths.projectBase().resolve("src/main/config/etc/jetty-deployment-scanner.xml"));
        jetty.addConfiguration(MavenPaths.findTestResourceFile("jetty-test-deploy-custom.xml"));

        // Setup initial context
        jetty.copyWebapp("bar-test-context.xml", "bar.xml");

        Environment.ensure("test", TestContextHandler.class);

        // Should not throw an Exception
        jetty.load();
    }

    public void startJetty() throws Exception
    {
        // Start it
        jetty.start();
    }

    @AfterEach
    public void stopJetty() throws Exception
    {
        jetty.stop();
    }

    @Test
    public void testStartupContext() throws Exception
    {
        startJetty();

        // Check Server for Handlers
        jetty.assertContextHandlerExists("/bar");
    }

    @Test
    public void testStartupWithRelativeEnvironmentContext() throws Exception
    {
        Path jettyBase = jetty.getJettyBasePath();

        Path environments = jettyBase.resolve("environments");
        FS.ensureDirExists(environments);

        Files.copy(MavenPaths.findTestResourceFile("etc/test-context.xml"), environments.resolve("test-context.xml"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(MavenPaths.findTestResourceFile("etc/test-context-other.xml"), environments.resolve("test-context-other.xml"), StandardCopyOption.REPLACE_EXISTING);

        jetty.copyWebapp("bar-test-context.properties", "bar.properties");
        startJetty();

        //check test-context.xml was applied to the produced context
        ContextHandler context = jetty.getContextHandler("/bar");
        assertNotNull(context);
        assertThat(context.getAttribute("test-context-0"), equalTo("test-context-0"));
        assertThat(context, instanceOf(BarContextHandler.class));
        //check test-context-other.xml was applied to the produced context
        assertThat(context.getAttribute("other"), equalTo("othervalue"));
        //check test-context-other.xml was applied AFTER test-context.xml
        assertThat(context.getAttribute("somename"), equalTo("othervalue"));
    }

    @Test
    public void testStartupWithAbsoluteEnvironmentContext() throws Exception
    {
        Path jettyBase = jetty.getJettyBasePath();

        Path environments = jettyBase.resolve("environments");
        FS.ensureDirExists(environments);

        IO.copy(MavenPaths.findTestResourceFile("etc/test-context.xml"), environments.resolve("test-context.xml"));
        IO.copy(MavenPaths.findTestResourceFile("etc/test-context-other.xml"), environments.resolve("test-context-other.xml"));

        jetty.copyWebapp("bar-test-context.properties", "bar.properties");
        startJetty();

        // check test environment context xml was applied to the produced context
        ContextHandler context = jetty.getContextHandler("/bar");
        assertNotNull(context);
        assertThat(context.getAttribute("test-context-0"), equalTo("test-context-0"));
        assertThat(context, instanceOf(BarContextHandler.class));
        //check test-context-other.xml was applied to the produced context
        assertThat(context.getAttribute("other"), equalTo("othervalue"));
        //check test-context-other.xml was applied AFTER test-context.xml
        assertThat(context.getAttribute("somename"), equalTo("othervalue"));
    }

    @Test
    public void testNonEnvironmentPropertyFileNotApplied() throws Exception
    {
        Path jettyBase = jetty.getJettyBasePath();

        // Create some property files in ${jetty.base}/environments/ directory.
        Path environments = jettyBase.resolve("environments");
        FS.ensureDirExists(environments);

        Files.writeString(environments.resolve("non-env.properties"), "data.path=some/file/that/should/be/ignored.txt");
        Files.writeString(environments.resolve("ee8.properties"), "data.path=some/file/that/should/be/ignored.txt");
        Files.writeString(environments.resolve("ee9.properties"), "data.path=some/file/that/should/be/ignored.txt");
        Files.writeString(environments.resolve("ee10.properties"), "data.path=some/file/that/should/be/ignored.txt");
        Files.writeString(environments.resolve("not-core.properties"), "data.path=some/file/that/should/be/ignored.txt");

        jetty.copyWebapp("bar-test-context.properties", "bar.properties");
        startJetty();

        // test that the context was deployed as expected and that the non-applicable properties files were ignored
        ContextHandler context = jetty.getContextHandler("/bar");
        assertNotNull(context);
        assertThat(context, instanceOf(BarContextHandler.class));
    }

    /**
     * Test that properties of the same name will be overridden, in the order of the name of the .properties file
     */
    @Test
    public void testPropertyOverriding() throws Exception
    {
        Path jettyBase = jetty.getJettyBasePath();

        Path environments = jettyBase.resolve("environments");
        FS.ensureDirExists(environments);

        Path aPath = environments.resolve("test-a.xml");
        writeXmlDisplayName(aPath, "A WebApp");
        Path bPath = environments.resolve("test-b.xml");
        writeXmlDisplayName(bPath, "B WebApp");
        Path cPath = environments.resolve("test-c.xml");
        writeXmlDisplayName(cPath, "C WebApp");
        Path dPath = environments.resolve("test-d.xml");
        writeXmlDisplayName(dPath, "D WebApp");

        jetty.copyWebapp("bar-test-context.properties", "bar.properties");
        startJetty();

        ContextHandler context = jetty.getContextHandler("/bar");
        assertNotNull(context);
        assertEquals("D WebApp", context.getDisplayName());
    }

    /**
     * Test that properties defined in an environment-specific properties file
     * are used for substitution.
     */
    @Test
    public void testPropertySubstitution() throws Exception
    {
        Path jettyBase = jetty.getJettyBasePath();

        Path environments = jettyBase.resolve("environments");
        FS.ensureDirExists(environments);

        Files.writeString(environments.resolve("test.properties"),
            """
                test.displayName=DisplayName Set By Property
                """);

        Files.copy(MavenPaths.findTestResourceFile("etc/test-context-sub.xml"),
            environments.resolve("test-context-sub.xml"),
            StandardCopyOption.REPLACE_EXISTING);

        jetty.copyWebapp("bar-test-context.properties", "bar.properties");
        startJetty();

        ContextHandler context = jetty.getContextHandler("/bar");
        assertNotNull(context);
        assertEquals("DisplayName Set By Property", context.getDisplayName());
    }

    private static void writeXmlDisplayName(Path filePath, String displayName) throws IOException
    {
        String content = """
            <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure.dtd">
            <Configure class="org.eclipse.jetty.server.handler.ContextHandler">
              <Set name="displayName">@NAME@</Set>
            </Configure>
            """.replace("@NAME@", displayName);

        Files.writeString(filePath, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
}
