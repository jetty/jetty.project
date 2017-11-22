//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java Version Utility class.
 * <p>Parses java versions to extract a consistent set of version parts</p>
 */
public class JavaVersion
{
    
    /**
     * Context attribute that can be set to target a different version of the jvm than the current runtime.
     * Acceptable values should correspond to those returned by JavaVersion.getPlatform().
     */
    public static final String JAVA_TARGET_PLATFORM = "org.eclipse.jetty.javaTargetPlatform";
    
    // Copy of version in jetty-start

    private static final Pattern PRE_JDK9 = Pattern.compile("1\\.(\\d)(\\.(\\d+)(_(\\d+))?)?(-.+)?");
    // Regexp from JEP 223 (http://openjdk.java.net/jeps/223).
    private static final Pattern JDK9 = Pattern.compile("(\\d+)(\\.(\\d+))?(\\.(\\d+))?((-.+)?(\\+(\\d+)?(-.+)?)?)");

    public static final JavaVersion VERSION = parse(System.getProperty("java.version"));

    public static JavaVersion parse(String version)
    {
        if (version.startsWith("1."))
            return parsePreJDK9(version);
        return parseJDK9(version);
    }

    private static JavaVersion parsePreJDK9(String version)
    {
        Matcher matcher = PRE_JDK9.matcher(version);
        if (!matcher.matches())
            throw new IllegalArgumentException("Invalid Java version " + version);
        int major = 1;
        int minor = Integer.parseInt(matcher.group(1));
        String microGroup = matcher.group(3);
        int micro = microGroup == null || microGroup.isEmpty() ? 0 : Integer.parseInt(microGroup);
        String updateGroup = matcher.group(5);
        int update = updateGroup == null || updateGroup.isEmpty() ? 0 : Integer.parseInt(updateGroup);
        String suffix = matcher.group(6);
        return new JavaVersion(version, minor, major, minor, micro, update, suffix);
    }

    private static JavaVersion parseJDK9(String version)
    {
        Matcher matcher = JDK9.matcher(version);
        if (!matcher.matches())
            throw new IllegalArgumentException("Invalid Java version " + version);
        int major = Integer.parseInt(matcher.group(1));
        String minorGroup = matcher.group(3);
        int minor = minorGroup == null || minorGroup.isEmpty() ? 0 : Integer.parseInt(minorGroup);
        String microGroup = matcher.group(5);
        int micro = microGroup == null || microGroup.isEmpty() ? 0 : Integer.parseInt(microGroup);
        String suffix = matcher.group(6);
        return new JavaVersion(version, major, major, minor, micro, 0, suffix);
    }

    private final String version;
    private final int platform;
    private final int major;
    private final int minor;
    private final int micro;
    private final int update;
    private final String suffix;

    private JavaVersion(String version, int platform, int major, int minor, int micro, int update, String suffix)
    {
        this.version = version;
        this.platform = platform;
        this.major = major;
        this.minor = minor;
        this.micro = micro;
        this.update = update;
        this.suffix = suffix;
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
     * <p>Returns the micro number version, such as {@code 0} for JDK 1.8.0_92 and {@code 4} for JDK 9.2.4.</p>
     *
     * @return the micro number version
     */
    public int getMicro()
    {
        return micro;
    }

    /**
     * <p>Returns the update number version, such as {@code 92} for JDK 1.8.0_92 and {@code 0} for JDK 9.2.4.</p>
     *
     * @return the update number version
     */
    public int getUpdate()
    {
        return update;
    }

    /**
     * <p>Returns the remaining string after the version numbers, such as {@code -internal} for
     * JDK 1.8.0_92-internal and {@code -ea} for JDK 9-ea, or {@code +13} for JDK 9.2.4+13.</p>
     *
     * @return the remaining string after the version numbers
     */
    public String getSuffix()
    {
        return suffix;
    }

    public String toString()
    {
        return version;
    }
}
