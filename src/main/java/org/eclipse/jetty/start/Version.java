// ========================================================================
// Copyright (c) 2002-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.start;

/**
 * Utility class for parsing and comparing version strings.
 * JDK 1.1 compatible.
 * 
 */
 
public class Version {
 
    int _version = 0;
    int _revision = 0;
    int _subrevision = 0;
    String _suffix = "";
    
    public Version() {
    }
    
    public Version(String version_string) {
        parse(version_string);
    }
    
    /**
     * parses version string in the form version[.revision[.subrevision[extension]]]
     * into this instance.
     */
    public void parse(String version_string) {
        _version = 0;
        _revision = 0;
        _subrevision = 0;
        _suffix = "";
        int pos = 0;
        int startpos = 0;
        int endpos = version_string.length();
        while ( (pos < endpos) && Character.isDigit(version_string.charAt(pos))) {
            pos++;
        }
        _version = Integer.parseInt(version_string.substring(startpos,pos));
        if ((pos < endpos) && version_string.charAt(pos)=='.') {
            startpos = ++pos;
            while ( (pos < endpos) && Character.isDigit(version_string.charAt(pos))) {
                pos++;
            }
            _revision = Integer.parseInt(version_string.substring(startpos,pos));
        }
        if ((pos < endpos) && version_string.charAt(pos)=='.') {
            startpos = ++pos;
            while ( (pos < endpos) && Character.isDigit(version_string.charAt(pos))) {
                pos++;
            }
            _subrevision = Integer.parseInt(version_string.substring(startpos,pos));
        }
        if (pos < endpos) {
            _suffix = version_string.substring(pos);
        }
    }
    
    /**
     * @return string representation of this version
     */
    public String toString() {
        StringBuffer sb = new StringBuffer(10);
        sb.append(_version);
        sb.append('.');
        sb.append(_revision);
        sb.append('.');
        sb.append(_subrevision);
        sb.append(_suffix);
        return sb.toString();
    }
    
    // java.lang.Comparable is Java 1.2! Cannot use it
    /**
     * Compares with other version. Does not take extension into account,
     * as there is no reliable way to order them.
     * @return -1 if this is older version that other,
     *         0 if its same version,
     *         1 if it's newer version than other
     */
    public int compare(Version other) {
        if (other == null) throw new NullPointerException("other version is null");
        if (this._version < other._version) return -1;
        if (this._version > other._version) return 1;
        if (this._revision < other._revision) return -1;
        if (this._revision > other._revision) return 1;
        if (this._subrevision < other._subrevision) return -1;
        if (this._subrevision > other._subrevision) return 1;
        return 0;
    }
    
    /**
     * Check whether this verion is in range of versions specified
     */
    public boolean isInRange(Version low, Version high) {
        return (compare(low)>=0 && compare(high)<=0);
    }
}
