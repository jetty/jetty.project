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

import java.util.HashMap;
import java.util.Map;

public enum WebsocketApiVersion
{
    V2_1("2.1"),
    V2_2("2.2"),
    V2_3("2.3");

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

    /**
     * Get the WebSocket API Version.
     *
     * <p>
     *     This version DOES NOT CACHE the result and will lookup the version every call.
     *     It is strongly recommended that you do not store this value in a static variable, as that
     *     can lead to improper/invalid caching of the value on OSGi.
     * </p>
     * @return the WebSocket API version.
     */
    public static WebsocketApiVersion getWebsocketApiVersion()
    {
        EnterpriseEditionVersion version1 = EnterpriseEditionVersion.getEnterpriseEditionVersion();
        return switch (version1)
        {
            case EE10 -> V2_1;
            case EE11 -> V2_2;
            case EE12 -> V2_3;
        };
    }

    private static class Mapping
    {
        private static final Map<String, WebsocketApiVersion> versions = new HashMap<>();
    }
}
