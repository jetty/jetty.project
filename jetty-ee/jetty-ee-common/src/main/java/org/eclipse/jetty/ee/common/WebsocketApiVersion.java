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

import java.lang.module.ModuleDescriptor;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum WebsocketApiVersion
{
    v2_0("2.0"),
    v2_1("2.1"),
    v2_2("2.2");

    public static WebsocketApiVersion currentVersion = initWebsocketApiVersion();

    private static Logger LOG = LoggerFactory.getLogger(WebsocketApiVersion.class);
    private final String version;
    private final int major;
    private final int minor;

    WebsocketApiVersion(String version)
    {
        this.version = version;
        this.major = Integer.parseInt(version.split("\\.")[0]);
        this.minor = Integer.parseInt(version.split("\\.")[1]);
        Mapping.versions.put(version, this);
    }

    public String version()
    {
        return version;
    }

    public int getMajorVersion()
    {
        return major;
    }

    public int getMinorVersion()
    {
        return minor;
    }

    public static WebsocketApiVersion from(String version)
    {
        WebsocketApiVersion servletApiVersion = Mapping.versions.get(version);
        if (servletApiVersion == null)
            throw new IllegalArgumentException("Unknown servlet API version:" + version);
        return servletApiVersion;
    }

    public static WebsocketApiVersion initWebsocketApiVersion()
    {
        ClassLoader classLoader = WebsocketApiVersion.class.getClassLoader();
        try
        {
            Class<?> loadedClass = classLoader.loadClass("jakarta.websocket.Session");
            String specificationVersion = loadedClass.getPackage().getSpecificationVersion();
            if (specificationVersion == null)
            {
                LOG.info("getDefinedPackage");
                specificationVersion = classLoader.getDefinedPackage("jakarta.websocket").getSpecificationVersion();
            }
            if (specificationVersion == null)
            {
                LOG.info("getModule");
                specificationVersion = loadedClass.getModule().getDescriptor().version()
                    .map(ModuleDescriptor.Version::toString)
                    .map(version -> version.substring(0, version.lastIndexOf('.')))
                    .orElse(null);
                LOG.info("Version:" + specificationVersion);
            }
            return WebsocketApiVersion.from(specificationVersion);
        }
        catch (ClassNotFoundException e)
        {
            throw new IllegalStateException("Cannot detect websocket API version", e);
        }
    }

    public static WebsocketApiVersion getWebsocketApiVersion() {
        return currentVersion;
    }

    private static class Mapping
    {
        private static final Map<String, WebsocketApiVersion> versions = new HashMap<>();
    }
}
