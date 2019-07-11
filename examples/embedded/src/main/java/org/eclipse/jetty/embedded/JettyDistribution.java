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

package org.eclipse.jetty.embedded;

import java.io.File;
import java.nio.file.Path;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * A utility test class to locate a Jetty Distribution for testing purposes by searching:
 * <ul>
 * <li>The <code>jetty.home</code> system property</li>
 * <li>The <code>JETTY_HOME</code> environment variable</li>
 * <li>The working directory hierarchy with subdirectory <code>jetty-distribution/target/home</code></li>
 * </ul>
 */
public class JettyDistribution
{
    private static final Logger LOG = Log.getLogger(JettyDistribution.class);
    public static final Path DISTRIBUTION;

    static
    {
        Path distro = asJettyDistribution(System.getProperty("jetty.home"));
        if (distro == null)
            distro = asJettyDistribution(System.getenv().get("JETTY_HOME"));

        if (distro == null)
        {
            try
            {
                Path working = new File(".").getAbsoluteFile().getCanonicalFile().toPath();
                while (distro == null && working != null)
                {
                    distro = asJettyDistribution(working.resolve("jetty-distribution/target/distribution").toString());
                    working = working.getParent();
                }
            }
            catch (Throwable th)
            {
                LOG.warn(th);
            }
        }
        DISTRIBUTION = distro;
    }

    private static Path asJettyDistribution(String test)
    {
        try
        {
            if (StringUtil.isBlank(test))
            {
                LOG.info("asJettyDistribution {} is blank", test);
                return null;
            }

            File dir = new File(test);
            if (!dir.exists() || !dir.isDirectory())
            {
                LOG.info("asJettyDistribution {} is not a directory", test);
                return null;
            }

            File demoBase = new File(dir, "demo-base");
            if (!demoBase.exists() || !demoBase.isDirectory())
            {
                LOG.info("asJettyDistribution {} has no demo-base", test);
                return null;
            }

            LOG.info("asJettyDistribution {}", dir);
            return dir.getAbsoluteFile().getCanonicalFile().toPath();
        }
        catch (Exception e)
        {
            LOG.ignore(e);
        }
        return null;
    }

    public static Path resolve(String path)
    {
        return DISTRIBUTION.resolve(path);
    }

    public static void main(String... arg)
    {
        System.err.println("Jetty Distribution is " + DISTRIBUTION);
    }
}
