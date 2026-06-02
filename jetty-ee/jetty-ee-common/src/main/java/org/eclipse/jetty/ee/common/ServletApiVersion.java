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

public enum ServletApiVersion
{
    v4_0("4.0"),
    v5_0("5.0"),
    v6_0("6.0"),
    v6_1("6.1"),
    v6_2("6.2");

    public static final ServletApiVersion currentVersion = initServletApiVersion();

    private final String version;
    private final int major;
    private final int minor;

    ServletApiVersion(String version)
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

    public static ServletApiVersion from(String version)
    {
        ServletApiVersion servletApiVersion = Mapping.versions.get(version);
        if (servletApiVersion == null)
            throw new IllegalArgumentException("Unknown servlet API version:" + version);
        return servletApiVersion;
    }

    public static ServletApiVersion initServletApiVersion()
    {
        ClassLoader classLoader = ServletApiVersion.class.getClassLoader();
        try
        {
            Class<?> loadedClass = classLoader.loadClass("jakarta.servlet.ServletRequest");
            String specificationVersion = loadedClass.getPackage().getSpecificationVersion();
            if (specificationVersion == null)
            {
                specificationVersion = classLoader.getDefinedPackage("jakarta.servlet").getSpecificationVersion();
            }
            if (specificationVersion == null)
            {
                specificationVersion = loadedClass.getModule().getDescriptor().version()
                    .map(ModuleDescriptor.Version::toString)
                    .map(version -> version.substring(0, version.lastIndexOf('.')))
                    .orElse(null);
            }
            return ServletApiVersion.from(specificationVersion);
        }
        catch (ClassNotFoundException | NoClassDefFoundError e)
        {
            throw new IllegalStateException("Cannot detect servlet API version", e);
        }
    }

    public static ServletApiVersion getServletApiVersion()
    {
        return currentVersion;
    }

    private static class Mapping
    {
        private static final Map<String, ServletApiVersion> versions = new HashMap<>();
    }
}
