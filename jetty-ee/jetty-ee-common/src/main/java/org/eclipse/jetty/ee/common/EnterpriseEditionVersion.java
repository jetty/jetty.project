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

package org.eclipse.jetty.ee.common;

import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum EnterpriseEditionVersion
{
    /**
     * EE10 is in use.
     */
    EE10(10, "ee10"),
    /**
     * EE11 is in use.
     */
    EE11(11, "ee11"),
    /**
     * EE12 is in use.
     */
    EE12(12, "ee12");

    private static final Logger LOG = LoggerFactory.getLogger(EnterpriseEditionVersion.class);

    public static final EnterpriseEditionVersion currentVersion = initEnterpriseEditionVersion();

    public static EnterpriseEditionVersion getEnterpriseEditionVersion()
    {
        return currentVersion;
    }

    private final int version;
    private final String environmentName;

    EnterpriseEditionVersion(int version, String environmentName)
    {
        this.version = version;
        this.environmentName = environmentName;
    }

    public int version()
    {
        return version;
    }

    public String environmentName()
    {
        return this.environmentName;
    }

    private static EnterpriseEditionVersion initEnterpriseEditionVersion()
    {
        try
        {
            ClassLoader cl = EnterpriseEditionVersion.class.getClassLoader();
            String resourceName = "META-INF/org.eclipse.jetty/env.properties";
            List<URL> hits = Collections.list(cl.getResources(resourceName));

            if (LOG.isDebugEnabled())
            {
                LOG.debug("Looking for {}: found {}", resourceName,
                    hits.stream().map(URL::toString).collect(Collectors.joining(", ")));
            }

            // Not in classloader (eg: when using jetty-ee-common jars directly)
            if (hits.isEmpty())
            {
                LOG.info("Defaulting to EE11 environment");
                // Default environment
                return EE11;
            }

            if (hits.size() > 1)
            {
                throw new RuntimeException("Multiple environments detected in the same classloader: " +
                    hits.stream().map(URL::toString).collect(Collectors.joining(", ")));
            }

            Properties props = new Properties();
            URL url = hits.getFirst();
            try (InputStream in = url.openStream())
            {
                props.load(in);
                String env = props.getProperty("environment");

                if (LOG.isDebugEnabled())
                {
                    LOG.debug("Found declared [environment={}] in {}", env, url);
                }

                return switch(env)
                {
                    case "ee10" -> EE10;
                    case "ee11" -> EE11;
                    case "ee12" -> EE12;
                    default -> throw new RuntimeException("Unrecognized Jetty environment [" + env + "]");
                };
            }
        }
        catch (Throwable e)
        {
            throw new RuntimeException(e);
        }
    }
}
