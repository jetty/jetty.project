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

package org.eclipse.jetty.ee10.quickstart;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PreconfigureStandardTestWar
 */
public class PreconfigureStandardTestWar
{
    private static final long __start = System.nanoTime();
    private static final Logger LOG = LoggerFactory.getLogger(Server.class);

    public static void main(String[] args) throws Exception
    {
        Path outputDir = MavenTestingUtils.getTargetPath("test-standard-preconfigured");
        if (Files.exists(outputDir))
            IO.delete(outputDir);

        Path realmPropertiesDestPath = MavenTestingUtils.getTargetTestingPath("test-standard-realm.properties");
        if (Files.exists(realmPropertiesDestPath))
            IO.delete(realmPropertiesDestPath);

        Path realmPropertiesSrcPath = MavenTestingUtils.getTestResourcePathFile("realm.properties");
        Resource realmPropertiesSrc = Resource.newResource(realmPropertiesSrcPath);

        realmPropertiesSrc.copyTo(realmPropertiesDestPath);
        System.setProperty("jetty.home", "target");

        PreconfigureQuickStartWar.main("target/test-standard.war", outputDir.toString(), "src/test/resources/test.xml");

        LOG.info("Preconfigured in {}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - __start));

        // IO.copy(new FileInputStream("target/test-standard-preconfigured/WEB-INF/quickstart-web.xml"),System.out);
    }
}
