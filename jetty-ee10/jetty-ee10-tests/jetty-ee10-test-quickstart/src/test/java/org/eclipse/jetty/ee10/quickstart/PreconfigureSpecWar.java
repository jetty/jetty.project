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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PreconfigureSpecWar
{
    private static final long __start = System.nanoTime();
    private static final Logger LOG = LoggerFactory.getLogger(Server.class);

    public static void main(String[] args) throws Exception
    {
        Path target = MavenTestingUtils.getTargetPath().resolve("test-spec-preconfigured");
        if (Files.exists(target))
        {
            IO.delete(target.toFile());
        }
        Files.createDirectories(target.resolve("WEB-INF"));

        Path realmPropertiesDest = MavenTestingUtils.getTargetPath().resolve("test-spec-realm.properties");
        Files.deleteIfExists(realmPropertiesDest);

        Path realmPropertiesSrc = MavenTestingUtils.getTestResourcePath("realm.properties");
        Files.copy(realmPropertiesSrc, realmPropertiesDest);
        System.setProperty("jetty.home", MavenTestingUtils.getTargetDir().getAbsolutePath());

        PreconfigureQuickStartWar.main(
            MavenTestingUtils.getTargetFile("test-spec.war").toString(),
            target.toString(),
            MavenTestingUtils.getTestResourceFile("test-spec.xml").toString());

        LOG.info("Preconfigured in {}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - __start));

        Path quickStartXml = target.resolve("WEB-INF/quickstart-web.xml");
        try (InputStream in = Files.newInputStream(quickStartXml))
        {
            IO.copy(in, System.out);
        }
    }
}
