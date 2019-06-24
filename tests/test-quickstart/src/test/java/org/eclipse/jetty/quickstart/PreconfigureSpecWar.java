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

package org.eclipse.jetty.quickstart;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class PreconfigureSpecWar
{
    private static final long __start = System.nanoTime();
    private static final Logger LOG = Log.getLogger(Server.class);

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
