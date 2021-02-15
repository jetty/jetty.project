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

package org.eclipse.jetty.quickstart;

import java.io.File;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

public class PreconfigureSpecWar
{
    private static final long __start = System.nanoTime();
    private static final Logger LOG = Log.getLogger(Server.class);

    public static void main(String[] args) throws Exception
    {
        String target = "target/test-spec-preconfigured";
        File file = new File(target);
        if (file.exists())
            IO.delete(file);

        File realmPropertiesDest = new File("target/test-spec-realm.properties");
        if (realmPropertiesDest.exists())
            IO.delete(realmPropertiesDest);

        Resource realmPropertiesSrc = Resource.newResource("src/test/resources/realm.properties");
        realmPropertiesSrc.copyTo(realmPropertiesDest);
        System.setProperty("jetty.home", "target");

        PreconfigureQuickStartWar.main("target/test-spec.war", target, "src/test/resources/test-spec.xml");

        LOG.info("Preconfigured in {}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - __start));

        // IO.copy(new FileInputStream("target/test-spec-preconfigured/WEB-INF/quickstart-web.xml"),System.out);
    }
}
