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

package org.eclipse.jetty.ee10.maven.plugin;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 
 *
 */
public class TestQuickStartGenerator
{
    public WorkDir workDir;

    @Test
    public void testGenerator() throws Exception
    {
        Path tmpDir = workDir.getEmptyPathDir();

        MavenWebAppContext webApp = new MavenWebAppContext();
        webApp.setContextPath("/shouldbeoverridden");
        Path rootDir = MavenTestingUtils.getTargetPath("test-classes/root");
        assertTrue(Files.exists(rootDir));
        assertTrue(Files.isDirectory(rootDir));
        webApp.setBaseResource(ResourceFactory.root().newResource(rootDir));

        Path quickstartFile = tmpDir.resolve("quickstart-web.xml");
        QuickStartGenerator generator = new QuickStartGenerator(quickstartFile, webApp);
        generator.setContextXml(MavenTestingUtils.getTargetFile("test-classes/embedder-context.xml").getAbsolutePath());
        generator.setServer(new Server());

        Path propsFile = tmpDir.resolve("webapp.props");
        Files.createFile(propsFile);
        generator.setWebAppProps(propsFile);
        generator.generate();
        assertTrue(Files.exists(propsFile));
        assertThat(Files.size(propsFile), greaterThan(0L));
        assertTrue(Files.exists(quickstartFile));
        assertThat(Files.size(quickstartFile), greaterThan(0L));
    }
}
