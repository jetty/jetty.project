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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.TypeUtil;
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

    /**
     * Interface for service (via {@link java.util.ServiceLoader} to provide the {@link EnterpriseEditionVersion}.
     */
    public interface Service
    {
        /**
         * Get the version.
         *
         * @return the {@link EnterpriseEditionVersion} if able to be returned, or {@code null} if this
         * service is unable to provide the version.
         */
        EnterpriseEditionVersion getVersion();
    }

    /**
     * Get the Jakarta EE Version.
     *
     * <p>
     *     This version DOES NOT CACHE the result and will lookup the version every call.
     *     It is strongly recommended that you do not store this value in a static variable, as that
     *     can lead to improper/invalid caching of the value on OSGi.
     * </p>
     * @return the Jakarta EE Version.
     */
    public static EnterpriseEditionVersion getEnterpriseEditionVersion()
    {
        // Resolve lazily. A too-early lookup (eg: during OSGi boot bundle activation, before
        // SPIFly has registered the EnterpriseEditionVersion.Service providers) finds nothing
        // and must not be cached, otherwise the wrong default would be frozen forever.
        EnterpriseEditionVersion version = lookupEnterpriseEditionVersion();
        if (version != null)
            return version;
        // Default if no version discovered during lookup.
        return EnterpriseEditionVersion.EE11;
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

    /**
     * The classpath resource that, when present in an environment's {@link ClassLoader}, declares the
     * Jakarta {@link EnterpriseEditionVersion} of that environment via an {@code environment=<name>}
     * property (eg: {@code environment=ee11}). It is contributed by the per-environment modules
     * (eg: {@code jetty-ee11-servlet}).
     */
    public static final String ENVIRONMENT_PROPERTIES = "META-INF/org.eclipse.jetty/env.properties";

    private static EnterpriseEditionVersion lookupEnterpriseEditionVersion()
    {
        // (1) Standard and JPMS distributions declare the environment with the ENVIRONMENT_PROPERTIES
        //     marker placed in the per-environment ClassLoader. It MUST be read via the thread context
        //     ClassLoader: during deployment, configuration and request handling that is the
        //     per-environment ClassLoader. The ClassLoader that defined this class cannot be relied on,
        //     because under JPMS jetty-ee-common is a single shared module whose ClassLoader cannot see
        //     the per-environment marker (it lives in a child environment layer).
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        EnterpriseEditionVersion version = fromEnvironmentMarker(contextClassLoader);
        if (version == null)
        {
            ClassLoader localClassLoader = EnterpriseEditionVersion.class.getClassLoader();
            if (localClassLoader != contextClassLoader)
                version = fromEnvironmentMarker(localClassLoader);
        }
        if (version != null)
            return version;

        // (2) OSGi registers EnterpriseEditionVersion.Service providers (eg: EnterpriseEdition11Service)
        //     per environment bundle via SPIFly. Discover them via ServiceLoader against the context
        //     ClassLoader. serviceStream(...) skips (rather than aborts on) any provider that a foreign
        //     ClassLoader makes ServiceLoader reject (eg: "not a subtype" / "Provider not found").
        version = TypeUtil.serviceStream(ServiceLoader.load(Service.class, contextClassLoader))
            .map(Service::getVersion)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
        if (version != null)
            return version;

        // No environment discovered during lookup.
        if (LOG.isInfoEnabled())
            LOG.info("EnterpriseEditionVersion not found in environment and/or classloader, defaulting to EE11");
        return null;
    }

    /**
     * Detect the {@link EnterpriseEditionVersion} from the {@link #ENVIRONMENT_PROPERTIES} marker
     * visible to the given {@link ClassLoader}.
     *
     * @param classLoader the ClassLoader to inspect, may be {@code null}
     * @return the detected version, or {@code null} if no single, recognized marker was found
     */
    public static EnterpriseEditionVersion fromEnvironmentMarker(ClassLoader classLoader)
    {
        if (classLoader == null)
            return null;

        try
        {
            List<URL> hits = Collections.list(classLoader.getResources(ENVIRONMENT_PROPERTIES));
            if (hits.isEmpty())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("No {} resources found in {}", ENVIRONMENT_PROPERTIES, classLoader);
                return null;
            }
            if (hits.size() > 1)
            {
                // Multiple environments visible to a single ClassLoader is ambiguous: do not guess.
                if (LOG.isWarnEnabled())
                    LOG.warn("Multiple environments detected in the same classloader: {}",
                        hits.stream().map(URL::toString).collect(Collectors.joining(", ")));
                return null;
            }

            URL url = hits.getFirst();
            Properties props = new Properties();
            try (InputStream in = url.openStream())
            {
                props.load(in);
            }
            String name = props.getProperty("environment");
            EnterpriseEditionVersion version = fromEnvironmentName(name);
            if (version == null)
            {
                if (LOG.isWarnEnabled())
                    LOG.warn("Unrecognized Jetty environment [{}] in {}", name, url);
            }
            else if (LOG.isDebugEnabled())
            {
                LOG.debug("Found declared [environment={}] in {}", name, url);
            }
            return version;
        }
        catch (IOException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to read {} from {}", ENVIRONMENT_PROPERTIES, classLoader, e);
            return null;
        }
    }

    /**
     * @param environmentName the environment name (eg: {@code ee11}), case-insensitive
     * @return the matching {@link EnterpriseEditionVersion}, or {@code null} if {@code null} or unrecognized
     */
    public static EnterpriseEditionVersion fromEnvironmentName(String environmentName)
    {
        if (environmentName == null)
            return null;
        for (EnterpriseEditionVersion version : values())
        {
            if (version.environmentName.equalsIgnoreCase(environmentName))
                return version;
        }
        return null;
    }
}
