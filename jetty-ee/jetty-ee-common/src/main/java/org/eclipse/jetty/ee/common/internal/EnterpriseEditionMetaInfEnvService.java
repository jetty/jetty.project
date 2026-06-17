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

package org.eclipse.jetty.ee.common.internal;

import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import org.eclipse.jetty.ee.common.EnterpriseEditionVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EnterpriseEditionMetaInfEnvService implements EnterpriseEditionVersion.Service
{
    private static final Logger LOG = LoggerFactory.getLogger(EnterpriseEditionMetaInfEnvService.class);

    @Override
    public EnterpriseEditionVersion getVersion()
    {
        String resourceName = "META-INF/org.eclipse.jetty/env.properties";
        try
        {
            ClassLoader cl = EnterpriseEditionVersion.class.getClassLoader();
            List<URL> hits = Collections.list(cl.getResources(resourceName));

            if (LOG.isDebugEnabled())
            {
                LOG.debug("Looking for {}: found {}", resourceName,
                    hits.stream().map(URL::toString).collect(Collectors.joining(", ")));
            }

            // Not in classloader (eg: when using jetty-ee-common jars directly)
            if (hits.isEmpty())
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("No {} resources found", resourceName);
                }
                return null;
            }

            if (hits.size() > 1)
            {
                if (LOG.isWarnEnabled())
                {
                    LOG.warn("Multiple environments detected in the same classloader: {}",
                        hits.stream().map(URL::toString).collect(Collectors.joining(", ")));
                }
                return null;
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
                    case "ee10" -> EnterpriseEditionVersion.EE10;
                    case "ee11" -> EnterpriseEditionVersion.EE11;
                    case "ee12" -> EnterpriseEditionVersion.EE12;
                    default ->
                    {
                        if (LOG.isWarnEnabled())
                        {
                            LOG.warn("Unrecognized Jetty environment [{}]", env);
                        }
                        yield null;
                    }
                };
            }
        }
        catch (Throwable e)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("Unable to process resources of [{}]", resourceName, e);
            }
            return null;
        }
    }
}
