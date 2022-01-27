//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

import java.io.InputStream;
import java.time.Instant;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Jetty
{
    private static final Logger LOG = LoggerFactory.getLogger(Jetty.class);

    public static final String VERSION;
    public static final String POWERED_BY;
    public static final boolean STABLE;
    public static final String GIT_HASH;

    /**
     * a formatted build timestamp with pattern yyyy-MM-dd'T'HH:mm:ssXXX
     */
    public static final String BUILD_TIMESTAMP;
    private static final Properties __buildProperties = new Properties();

    static
    {
        try
        {
            try (InputStream inputStream = //
                     Jetty.class.getResourceAsStream("/org/eclipse/jetty/version/build.properties"))
            {
                __buildProperties.load(inputStream);
            }
        }
        catch (Exception e)
        {
            LOG.trace("IGNORED", e);
        }

        String gitHash = __buildProperties.getProperty("buildNumber", "unknown");
        if (gitHash.startsWith("${"))
            gitHash = "unknown";
        GIT_HASH = gitHash;
        System.setProperty("jetty.git.hash", GIT_HASH);
        BUILD_TIMESTAMP = formatTimestamp(__buildProperties.getProperty("timestamp", "unknown"));

        // using __buildProperties.getProperty("version") will contain version from the pom

        Package pkg = Jetty.class.getPackage();
        if (pkg != null &&
            "Eclipse Jetty Project".equals(pkg.getImplementationVendor()) &&
            pkg.getImplementationVersion() != null)
            VERSION = pkg.getImplementationVersion();
        else
            VERSION = System.getProperty("jetty.version", __buildProperties.getProperty("version", "10.0.z-SNAPSHOT"));

        POWERED_BY = "<a href=\"https://eclipse.org/jetty\">Powered by Jetty:// " + VERSION + "</a>";

        // Show warning when RC# or M# is in version string
        STABLE = !VERSION.matches("^.*\\.(RC|M)[0-9]+$");
    }

    private Jetty()
    {
    }

    private static String formatTimestamp(String timestamp)
    {
        try
        {
            long epochMillis = Long.parseLong(timestamp);
            return Instant.ofEpochMilli(epochMillis).toString();
        }
        catch (NumberFormatException e)
        {
            LOG.trace("IGNORED", e);
            return "unknown";
        }
    }
}
