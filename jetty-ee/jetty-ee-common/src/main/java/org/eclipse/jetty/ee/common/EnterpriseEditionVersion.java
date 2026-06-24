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

import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;

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

    private static EnterpriseEditionVersion lookupEnterpriseEditionVersion()
    {
        ServiceLoader<EnterpriseEditionVersion.Service> loader = ServiceLoader.load(Service.class);
        loader.stream()
            .forEach(serviceProvider ->
            {
                Service service = serviceProvider.get();
                LOG.info("Found Service: {}", service.getClass().getName());
            });

        List<Service> services = TypeUtil.serviceProviderStream(ServiceLoader.load(Service.class))
            .flatMap(p -> Stream.of(p.get()))
            .toList();
        for (Service service : services)
        {
            EnterpriseEditionVersion ver = service.getVersion();
            if (LOG.isDebugEnabled())
            {
                LOG.debug("{}.getVersion() gives {}", service.getClass().getName(), ver);
            }
            if (ver != null)
            {
                return ver;
            }
        }

        // No match found.
        if (LOG.isInfoEnabled())
        {
            LOG.info("EnterpriseEditionVersion not found in environment and/or classloader, defaulting to EE11");
        }
        return null;
    }
}
