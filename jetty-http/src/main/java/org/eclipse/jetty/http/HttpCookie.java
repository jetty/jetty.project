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

package org.eclipse.jetty.http;

import java.util.concurrent.TimeUnit;

public class HttpCookie
{
    private final String _name;
    private final String _value;
    private final String _comment;
    private final String _domain;
    private final long _maxAge;
    private final String _path;
    private final boolean _secure;
    private final int _version;
    private final boolean _httpOnly;
    private final long _expiration;

    public HttpCookie(String name, String value)
    {
        this(name, value, -1);
    }

    public HttpCookie(String name, String value, String domain, String path)
    {
        this(name, value, domain, path, -1, false, false);
    }

    public HttpCookie(String name, String value, long maxAge)
    {
        this(name, value, null, null, maxAge, false, false);
    }

    public HttpCookie(String name, String value, String domain, String path, long maxAge, boolean httpOnly, boolean secure)
    {
        this(name, value, domain, path, maxAge, httpOnly, secure, null, 0);
    }

    public HttpCookie(String name, String value, String domain, String path, long maxAge, boolean httpOnly, boolean secure, String comment, int version)
    {
        _name = name;
        _value = value;
        _domain = domain;
        _path = path;
        _maxAge = maxAge;
        _httpOnly = httpOnly;
        _secure = secure;
        _comment = comment;
        _version = version;
        _expiration = maxAge < 0 ? -1 : System.nanoTime() + TimeUnit.SECONDS.toNanos(maxAge);
    }

    /**
     * @return the cookie name
     */
    public String getName()
    {
        return _name;
    }

    /**
     * @return the cookie value
     */
    public String getValue()
    {
        return _value;
    }

    /**
     * @return the cookie comment
     */
    public String getComment()
    {
        return _comment;
    }

    /**
     * @return the cookie domain
     */
    public String getDomain()
    {
        return _domain;
    }

    /**
     * @return the cookie max age in seconds
     */
    public long getMaxAge()
    {
        return _maxAge;
    }

    /**
     * @return the cookie path
     */
    public String getPath()
    {
        return _path;
    }

    /**
     * @return whether the cookie is valid for secure domains
     */
    public boolean isSecure()
    {
        return _secure;
    }

    /**
     * @return the cookie version
     */
    public int getVersion()
    {
        return _version;
    }

    /**
     * @return whether the cookie is valid for the http protocol only
     */
    public boolean isHttpOnly()
    {
        return _httpOnly;
    }

    /**
     * @param timeNanos the time to check for cookie expiration, in nanoseconds
     * @return whether the cookie is expired by the given time
     */
    public boolean isExpired(long timeNanos)
    {
        return _expiration >= 0 && timeNanos >= _expiration;
    }

    /**
     * @return a string representation of this cookie
     */
    public String asString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append(getName()).append("=").append(getValue());
        if (getDomain() != null)
            builder.append(";$Domain=").append(getDomain());
        if (getPath() != null)
            builder.append(";$Path=").append(getPath());
        return builder.toString();
    }
}
