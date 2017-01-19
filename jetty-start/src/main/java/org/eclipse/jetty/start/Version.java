//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.start;

/**
 * Utility class for parsing and comparing version strings.
 * <p>
 * http://www.oracle.com/technetwork/java/javase/namechange-140185.html
 */
public class Version implements Comparable<Version>
{
    
    /**
     * Original String version
     */
    private String string = null;
    
    /**
     * Short String version
     */
    private String shortString = null;
    
    /**
     * The major version for java is always "1" (per
     * <a href="http://www.oracle.com/technetwork/java/javase/namechange-140185.html">legacy versioning history</a>)
     */
    private int legacyMajor = 0;
    /**
     * The true major version is the second value ("1.5" == "Java 5", "1.8" = "Java 8", etc..)
     */
    private int major = -1;
    /**
     * The revision of the version.
     * <p>
     * This value is always "0" (also per <a
     * href="http://www.oracle.com/technetwork/java/javase/namechange-140185.html">legacy versioning history</a>)
     */
    private int revision = -1;
    /**
     * The update (where bug fixes are placed)
     */
    private int update = -1;
    
    /**
     * Update strings may be zero padded!
     */
    private String updateString = null;
    
    /**
     * Extra versioning information present on the version string, but not relevant for version comparison reason.
     * (eg: with "1.8.0_45-internal", the suffix would be "-internal")
     */
    private String suffix = "";

    private static enum ParseState
    {
        LEGACY,
        MAJOR,
        REVISION,
        UPDATE;
    }

    public Version(String versionString)
    {
        parse(versionString);
    }

    @Override
    /**
     * Compares with other version. Does not take extension into account, as there is no reliable way to order them.
     * 
     * @param other the other version to compare this to 
     * @return -1 if this is older version that other, 0 if its same version, 1 if it's newer version than other
     */
    public int compareTo(Version other)
    {
        if (other == null)
        {
            throw new NullPointerException("other version is null");
        }
        if (this.legacyMajor < other.legacyMajor)
        {
            return -1;
        }
        if (this.legacyMajor > other.legacyMajor)
        {
            return 1;
        }
        if (this.major < other.major)
        {
            return -1;
        }
        if (this.major > other.major)
        {
            return 1;
        }
        if (this.revision < other.revision)
        {
            return -1;
        }
        if (this.revision > other.revision)
        {
            return 1;
        }
        if (this.update < other.update)
        {
            return -1;
        }
        if (this.update > other.update)
        {
            return 1;
        }
        return 0;
    }

    public int getLegacyMajor()
    {
        return legacyMajor;
    }

    public int getMajor()
    {
        return major;
    }

    public int getRevision()
    {
        return revision;
    }

    public int getUpdate()
    {
        return update;
    }

    public String getSuffix()
    {
        return suffix;
    }

    public boolean isNewerThan(Version other)
    {
        return compareTo(other) == 1;
    }

    public boolean isNewerThanOrEqualTo(Version other)
    {
        int comp = compareTo(other);
        return (comp == 0) || (comp == 1);
    }

    public boolean isOlderThan(Version other)
    {
        return compareTo(other) == -1;
    }

    public boolean isOlderThanOrEqualTo(Version other)
    {
        int comp = compareTo(other);
        return (comp == 0) || (comp == -1);
    }

    /**
     * Check whether this version is in range of versions specified
     * 
     * @param low
     *            the low part of the range
     * @param high
     *            the high part of the range
     * @return true if this version is within the provided range
     */
    public boolean isInRange(Version low, Version high)
    {
        return ((compareTo(low) >= 0) && (compareTo(high) <= 0));
    }

    /**
     * parses version string in the form legacy[.major[.revision[_update[-suffix]]]] into this instance.
     * 
     * @param versionStr
     *            the version string
     */
    private void parse(String versionStr)
    {
        string = versionStr;
        legacyMajor = 0;
        major = -1;
        revision = -1;
        update = -1;
        suffix = "";

        ParseState state = ParseState.LEGACY;
        int offset = 0;
        int len = versionStr.length();
        int val = 0;
        while (offset < len)
        {
            char c = versionStr.charAt(offset);
            if (c=='-')
                shortString=versionStr.substring(0,offset);
            boolean isSeparator = !Character.isLetterOrDigit(c);
            if (isSeparator)
            {
                val = 0;
            }
            else if (Character.isDigit(c))
            {
                val = (val * 10) + (c - '0');
            }
            else if (Character.isLetter(c))
            {
                suffix = versionStr.substring(offset);
                break;
            }

            switch (state)
            {
                case LEGACY:
                    if (isSeparator)
                        state = ParseState.MAJOR;
                    else
                        legacyMajor = val;
                    break;
                case MAJOR:
                    if (isSeparator)
                        state = ParseState.REVISION;
                    else
                        major = val;
                    break;
                case REVISION:
                    if (isSeparator)
                        state = ParseState.UPDATE;
                    else
                        revision = val;
                    break;
                case UPDATE:
                    if (!isSeparator)
                    {
                        update = val;
                    }
                    break;
            }

            offset++;
        }
        if (shortString==null)
            shortString=versionStr;
    }

    /**
     * @return string representation of this version
     */
    @Override
    public String toString()
    {
        return string;
    }
    
    /**
     * Return short string form (without suffix)
     * @return string the short version string form
     */
    public String toShortString()
    {
        return shortString;
    }
}
