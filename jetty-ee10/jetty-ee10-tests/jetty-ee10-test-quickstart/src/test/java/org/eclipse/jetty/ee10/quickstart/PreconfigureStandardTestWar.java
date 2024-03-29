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

package org.eclipse.jetty.ee10.quickstart;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.NanoTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PreconfigureStandardTestWar
 */
public class PreconfigureStandardTestWar
{
    private static final long __start = NanoTime.now();
    private static final Logger LOG = LoggerFactory.getLogger(Server.class);

    public static void main(String[] args) throws Exception
    {
        Path workdir = MavenPaths.targetTestDir(PreconfigureStandardTestWar.class.getSimpleName());
        FS.ensureDirExists(workdir);

        Path target = workdir.resolve("test-standard-preconfigured");
        FS.ensureEmpty(target);

        Path realmPropertiesDest = target.resolve("test-standard-realm.properties");
        Files.deleteIfExists(realmPropertiesDest);

        Path realmPropertiesSrc = MavenPaths.findTestResourceFile("realm.properties");
        Files.copy(realmPropertiesSrc, realmPropertiesDest);

        System.setProperty("jetty.home", target.toString());

        PreconfigureQuickStartWar.main(
            MavenPaths.targetDir().resolve("test-standard.war").toString(),
            target.toString(),
            MavenPaths.findTestResourceFile("test-spec.xml").toString());

        LOG.info("Preconfigured in {}ms", NanoTime.millisSince(__start));

        if (LOG.isDebugEnabled())
        {
            Path quickStartXml = target.resolve("WEB-INF/quickstart-web.xml");
            System.out.println(Files.readString(quickStartXml));
        }
    }
}
