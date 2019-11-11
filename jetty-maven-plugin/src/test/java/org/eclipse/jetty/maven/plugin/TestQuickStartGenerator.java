//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
