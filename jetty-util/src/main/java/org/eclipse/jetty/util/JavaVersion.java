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

/**
 * Java Version Utility class.
 * <p>Parses java versions to extract a consistent set of version parts</p>
 */
public class JavaVersion
{
    // IMPORTANT NOTE: This class cannot use Logging, as this class is used by jetty-start

    /**
     * Context attribute that can be set to target a different version of the jvm than the current runtime.
     * Acceptable values should correspond to those returned by JavaVersion.getPlatform().
     */
    public static final String JAVA_TARGET_PLATFORM = "org.eclipse.jetty.javaTargetPlatform";

    public static final JavaVersion VERSION = parse(System.getProperty("java.version"));

    public static JavaVersion parse(String v)
    {
        // $VNUM is a dot-separated list of integers of arbitrary length
        String[] split = v.split("[^0-9]");
        int len = Math.min(split.length, 3);
        int[] version = new int[len];
        for (int i = 0; i < len; i++)
        {
            try
            {
                version[i] = Integer.parseInt(split[i]);
            }
            catch (Throwable e)
            {
                len = i - 1;
                break;
            }
        }

        return new JavaVersion(
            v,
            (version[0] >= 9 || len == 1) ? version[0] : version[1],
            version[0],
            len > 1 ? version[1] : 0,
            len > 2 ? version[2] : 0);
    }

    private final String version;
    private final int platform;
    private final int major;
    private final int minor;
    private final int micro;

    private JavaVersion(String version, int platform, int major, int minor, int micro)
    {
        this.version = version;
        this.platform = platform;
        this.major = major;
        this.minor = minor;
        this.micro = micro;
    }

    /**
     * @return the string from which this JavaVersion was created
     */
    public String getVersion()
    {
        return version;
    }

    /**
     * <p>Returns the Java Platform version, such as {@code 8} for JDK 1.8.0_92 and {@code 9} for JDK 9.2.4.</p>
     *
     * @return the Java Platform version
     */
    public int getPlatform()
    {
        return platform;
    }

    /**
     * <p>Returns the major number version, such as {@code 1} for JDK 1.8.0_92 and {@code 9} for JDK 9.2.4.</p>
     *
     * @return the major number version
     */
    public int getMajor()
    {
        return major;
    }

    /**
     * <p>Returns the minor number version, such as {@code 8} for JDK 1.8.0_92 and {@code 2} for JDK 9.2.4.</p>
     *
     * @return the minor number version
     */
    public int getMinor()
    {
        return minor;
    }

    /**
     * <p>Returns the micro number version (aka security number), such as {@code 0} for JDK 1.8.0_92 and {@code 4} for JDK 9.2.4.</p>
     *
     * @return the micro number version
     */
    public int getMicro()
    {
        return micro;
    }

    @Override
    public String toString()
    {
        return version;
    }
}
