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

package org.eclipse.jetty.maven.plugin;

import java.io.File;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 
 *
 */
public class TestQuickStartGenerator
{
    @Test
    public void testGenerator() throws Exception
    {
        MavenWebAppContext webApp = new MavenWebAppContext();
        webApp.setContextPath("/shouldbeoverridden");
        webApp.setBaseResource(Resource.newResource(MavenTestingUtils.getTestResourceDir("root")));
        File quickstartFile = new File(MavenTestingUtils.getTargetTestingDir(), "quickstart-web.xml");
        QuickStartGenerator generator = new QuickStartGenerator(quickstartFile, webApp);
        generator.setContextXml(MavenTestingUtils.getTestResourceFile("embedder-context.xml").getAbsolutePath());
        generator.setServer(new Server());
        MavenTestingUtils.getTargetTestingDir().mkdirs();
        File propsFile = new File(MavenTestingUtils.getTargetTestingDir(), "webapp.props");
        propsFile.createNewFile();
        generator.setWebAppPropsFile(propsFile);
        generator.generate();
        assertTrue(propsFile.exists());
        assertTrue(propsFile.length() > 0);
        assertTrue(quickstartFile.exists());
        assertTrue(quickstartFile.length() > 0);
    }
}
