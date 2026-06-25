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

public enum ServletApiVersion
{
    V2_5("2.5"),
    V3_0("3.0"),
    V3_1("3.1"),
    V4_0("4.0"),
    V5_0("5.0"),
    V6_0("6.0"),
    V6_1("6.1"),
    V6_2("6.2");

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

    public String getWebXmlAttributes()
    {
        return switch(this)
        {
            case V2_5 -> """
                  xmlns="http://java.sun.com/xml/ns/javaee"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xsi:schemaLocation="http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_2_5.xsd"
                  version="2.5"
                """;
            case V3_0 -> """
                  xmlns="http://java.sun.com/xml/ns/javaee"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xsi:schemaLocation="http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd"
                  version="3.0"
                """;
            case V3_1 -> """
                  xmlns="http://xmlns.jcp.org/xml/ns/javaee"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xsi:schemaLocation="http://xmlns.jcp.org/xml/ns/javaee http://xmlns.jcp.org/xml/ns/javaee/web-app_3_1.xsd"
                  version="3.1"
                """;
            case V4_0 -> """
                  xmlns="http://xmlns.jcp.org/xml/ns/javaee"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xsi:schemaLocation="http://xmlns.jcp.org/xml/ns/javaee http://xmlns.jcp.org/xml/ns/javaee/web-app_4_0.xsd"
                  version="4.0"
                """;
            case V5_0 -> """
                  xmlns="https://jakarta.ee/xml/ns/jakartaee"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/web-app_5_0.xsd"
                  version="5.0"
                """;
            case V6_0 -> """
                  xmlns="https://jakarta.ee/xml/ns/jakartaee"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd"
                  version="6.0"
                """;
            case V6_1 -> """
                  xmlns="https://jakarta.ee/xml/ns/jakartaee"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/web-app_6_1.xsd"
                  version="6.1"
                """;
            case V6_2 -> """
                  xmlns="https://jakarta.ee/xml/ns/jakartaee"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/web-app_6_2.xsd"
                  version="6.2"
                """;
        };
    }

    public static ServletApiVersion from(String version)
    {
        ServletApiVersion servletApiVersion = Mapping.versions.get(version);
        if (servletApiVersion == null)
            throw new IllegalArgumentException("Unknown servlet API version:" + version);
        return servletApiVersion;
    }

    /**
     * Get the Servlet API Version.
     *
     * <p>
     *     This version DOES NOT CACHE the result and will lookup the version every call.
     *     It is strongly recommended that you do not store this value in a static variable, as that
     *     can lead to improper/invalid caching of the value on OSGi.
     * </p>
     * @return the Servlet API version.
     */
    public static ServletApiVersion getServletApiVersion()
    {
        EnterpriseEditionVersion version1 = EnterpriseEditionVersion.getEnterpriseEditionVersion();
        return switch (version1)
        {
            case EE10 -> V6_0;
            case EE11 -> V6_1;
            case EE12 -> V6_2;
        };
    }

    private static class Mapping
    {
        private static final Map<String, ServletApiVersion> versions = new HashMap<>();
    }
}
