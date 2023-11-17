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

package org.eclipse.jetty.http;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpCookie
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpCookie.class);
    
    private static final String __COOKIE_DELIM = "\",;\\ \t";
    private static final String __01Jan1970_COOKIE = DateGenerator.formatCookieDate(0).trim();

    /**
     * String used in the {@code Comment} attribute of {@link java.net.HttpCookie},
     * parsed with {@link #isHttpOnlyInComment(String)}, to support the {@code HttpOnly} attribute.
     **/
    public static final String HTTP_ONLY_COMMENT = "__HTTP_ONLY__";
    /**
     * String used in the {@code Comment} attribute of {@link java.net.HttpCookie},
     * parsed with {@link #isPartitionedInComment(String)}, to support the {@code Partitioned} attribute.
     **/
    public static final String PARTITIONED_COMMENT = "__PARTITIONED__";
    /**
     * The strings used in the {@code Comment} attribute of {@link java.net.HttpCookie},
     * parsed with {@link #getSameSiteFromComment(String)}, to support the {@code SameSite} attribute.
     **/
    private static final String SAME_SITE_COMMENT = "__SAME_SITE_";
    public static final String SAME_SITE_NONE_COMMENT = SAME_SITE_COMMENT + "NONE__";
    public static final String SAME_SITE_LAX_COMMENT = SAME_SITE_COMMENT + "LAX__";
    public static final String SAME_SITE_STRICT_COMMENT = SAME_SITE_COMMENT + "STRICT__";

    /**
     * Name of context attribute with default SameSite cookie value
     */
    public static final String SAME_SITE_DEFAULT_ATTRIBUTE = "org.eclipse.jetty.cookie.sameSiteDefault";

    public enum SameSite
    {
        NONE("None"), STRICT("Strict"), LAX("Lax");

        private final String attributeValue;

        SameSite(String attributeValue)
        {
            this.attributeValue = attributeValue;
        }

        public String getAttributeValue()
        {
            return this.attributeValue;
        }
    }

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
    private final SameSite _sameSite;
    private final boolean _partitioned;

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
        this(name, value, domain, path, maxAge, httpOnly, secure, comment, version, null);
    }

    public HttpCookie(String name, String value, String domain, String path, long maxAge, boolean httpOnly, boolean secure, String comment, int version, SameSite sameSite)
    {
        this(name, value, domain, path, maxAge, httpOnly, secure, comment, version, sameSite, false);
    }

    public HttpCookie(String name, String value, String domain, String path, long maxAge, boolean httpOnly, boolean secure, String comment, int version, SameSite sameSite, boolean partitioned)
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
        _expiration = maxAge < 0 ? -1 : NanoTime.now() + TimeUnit.SECONDS.toNanos(maxAge);
        _sameSite = sameSite;
        _partitioned = partitioned;
    }

    public HttpCookie(String setCookie)
    {
        List<java.net.HttpCookie> cookies = java.net.HttpCookie.parse(setCookie);
        if (cookies.size() != 1)
            throw new IllegalStateException();

        java.net.HttpCookie cookie = cookies.get(0);

        _name = cookie.getName();
        _value = cookie.getValue();
        _domain = cookie.getDomain();
        _path = cookie.getPath();
        _maxAge = cookie.getMaxAge();
        _httpOnly = cookie.isHttpOnly();
        _secure = cookie.getSecure();
        _comment = cookie.getComment();
        _version = cookie.getVersion();
        _expiration = _maxAge < 0 ? -1 : NanoTime.now() + TimeUnit.SECONDS.toNanos(_maxAge);
        // Support for SameSite values has not yet been added to java.net.HttpCookie.
        _sameSite = getSameSiteFromComment(cookie.getComment());
        // Support for Partitioned has not yet been added to java.net.HttpCookie.
        _partitioned = isPartitionedInComment(cookie.getComment());
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
     * @return the cookie SameSite enum attribute
     */
    public SameSite getSameSite()
    {
        return _sameSite;
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
        return _expiration != -1 && NanoTime.isBefore(_expiration, timeNanos);
    }

    /**
     * @return whether this cookie is partitioned
     */
    public boolean isPartitioned()
    {
        return _partitioned;
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

    private static void quoteOnlyOrAppend(StringBuilder buf, String s, boolean quote)
    {
        if (quote)
            QuotedStringTokenizer.quoteOnly(buf, s);
        else
            buf.append(s);
    }

    /**
     * Does a cookie value need to be quoted?
     *
     * @param s value string
     * @return true if quoted;
     * @throws IllegalArgumentException If there a control characters in the string
     */
    private static boolean isQuoteNeededForCookie(String s)
    {
        if (s == null || s.length() == 0)
            return true;

        if (QuotedStringTokenizer.isQuoted(s))
            return false;

        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (__COOKIE_DELIM.indexOf(c) >= 0)
                return true;

            if (c < 0x20 || c >= 0x7f)
                throw new IllegalArgumentException("Illegal character in cookie value");
        }

        return false;
    }

    public String getSetCookie(CookieCompliance compliance)
    {
        if (compliance == null || CookieCompliance.RFC6265_LEGACY.compliesWith(compliance))
            return getRFC6265SetCookie();
        return getRFC2965SetCookie();
    }

    public String getRFC2965SetCookie()
    {
        // Check arguments
        if (_name == null || _name.length() == 0)
            throw new IllegalArgumentException("Bad cookie name");

        // Format value and params
        StringBuilder buf = new StringBuilder();

        // Name is checked for legality by servlet spec, but can also be passed directly so check again for quoting
        boolean quoteName = isQuoteNeededForCookie(_name);
        quoteOnlyOrAppend(buf, _name, quoteName);

        buf.append('=');

        // Append the value
        boolean quoteValue = isQuoteNeededForCookie(_value);
        quoteOnlyOrAppend(buf, _value, quoteValue);

        // Look for domain and path fields and check if they need to be quoted
        boolean hasDomain = _domain != null && _domain.length() > 0;
        boolean quoteDomain = hasDomain && isQuoteNeededForCookie(_domain);
        boolean hasPath = _path != null && _path.length() > 0;
        boolean quotePath = hasPath && isQuoteNeededForCookie(_path);

        // Upgrade the version if we have a comment or we need to quote value/path/domain or if they were already quoted
        int version = _version;
        if (version == 0 && (_comment != null || quoteName || quoteValue || quoteDomain || quotePath ||
            QuotedStringTokenizer.isQuoted(_name) || QuotedStringTokenizer.isQuoted(_value) ||
            QuotedStringTokenizer.isQuoted(_path) || QuotedStringTokenizer.isQuoted(_domain)))
            version = 1;

        // Append version
        if (version == 1)
            buf.append(";Version=1");
        else if (version > 1)
            buf.append(";Version=").append(version);

        // Append path
        if (hasPath)
        {
            buf.append(";Path=");
            quoteOnlyOrAppend(buf, _path, quotePath);
        }

        // Append domain
        if (hasDomain)
        {
            buf.append(";Domain=");
            quoteOnlyOrAppend(buf, _domain, quoteDomain);
        }

        // Handle max-age and/or expires
        if (_maxAge >= 0)
        {
            // Always use expires
            // This is required as some browser (M$ this means you!) don't handle max-age even with v1 cookies
            buf.append(";Expires=");
            if (_maxAge == 0)
                buf.append(__01Jan1970_COOKIE);
            else
                DateGenerator.formatCookieDate(buf, System.currentTimeMillis() + 1000L * _maxAge);

            // for v1 cookies, also send max-age
            if (version >= 1)
            {
                buf.append(";Max-Age=");
                buf.append(_maxAge);
            }
        }

        // add the other fields
        if (_secure)
            buf.append(";Secure");
        if (_httpOnly)
            buf.append(";HttpOnly");
        if (_comment != null)
        {
            buf.append(";Comment=");
            quoteOnlyOrAppend(buf, _comment, isQuoteNeededForCookie(_comment));
        }
        return buf.toString();
    }

    public String getRFC6265SetCookie()
    {
        // Check arguments
        if (_name == null || _name.length() == 0)
            throw new IllegalArgumentException("Bad cookie name");

        // Name is checked for legality by servlet spec, but can also be passed directly so check again for quoting
        // Per RFC6265, Cookie.name follows RFC2616 Section 2.2 token rules
        Syntax.requireValidRFC2616Token(_name, "RFC6265 Cookie name");
        // Ensure that Per RFC6265, Cookie.value follows syntax rules
        Syntax.requireValidRFC6265CookieValue(_value);

        // Format value and params
        StringBuilder buf = new StringBuilder();
        buf.append(_name).append('=').append(_value == null ? "" : _value);

        // Append path
        if (_path != null && _path.length() > 0)
            buf.append("; Path=").append(_path);

        // Append domain
        if (_domain != null && _domain.length() > 0)
            buf.append("; Domain=").append(_domain);

        // Handle max-age and/or expires
        if (_maxAge >= 0)
        {
            // Always use expires
            // This is required as some browser (M$ this means you!) don't handle max-age even with v1 cookies
            buf.append("; Expires=");
            if (_maxAge == 0)
                buf.append(__01Jan1970_COOKIE);
            else
                DateGenerator.formatCookieDate(buf, System.currentTimeMillis() + 1000L * _maxAge);

            buf.append("; Max-Age=");
            buf.append(_maxAge);
        }

        // add the other fields
        if (_secure)
            buf.append("; Secure");
        if (_httpOnly)
            buf.append("; HttpOnly");
        if (_sameSite != null)
        {
            buf.append("; SameSite=");
            buf.append(_sameSite.getAttributeValue());
        }
        if (isPartitioned())
            buf.append("; Partitioned");

        return buf.toString();
    }

    public static boolean isHttpOnlyInComment(String comment)
    {
        return comment != null && comment.contains(HTTP_ONLY_COMMENT);
    }

    public static boolean isPartitionedInComment(String comment)
    {
        return comment != null && comment.contains(PARTITIONED_COMMENT);
    }

    public static SameSite getSameSiteFromComment(String comment)
    {
        if (comment == null)
            return null;

        if (comment.contains(SAME_SITE_STRICT_COMMENT))
            return SameSite.STRICT;
        if (comment.contains(SAME_SITE_LAX_COMMENT))
            return SameSite.LAX;
        if (comment.contains(SAME_SITE_NONE_COMMENT))
            return SameSite.NONE;

        return null;
    }

    /**
     * Get the default value for SameSite cookie attribute, if one
     * has been set for the given context.
     * 
     * @param contextAttributes the context to check for default SameSite value
     * @return the default SameSite value or null if one does not exist
     * @throws IllegalStateException if the default value is not a permitted value
     */
    public static SameSite getSameSiteDefault(Attributes contextAttributes)
    {
        if (contextAttributes == null)
            return null;
        Object o = contextAttributes.getAttribute(SAME_SITE_DEFAULT_ATTRIBUTE);
        if (o == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("No default value for SameSite");
            return null;
        }

        if (o instanceof SameSite)
            return (SameSite)o;

        try
        {
            SameSite samesite = Enum.valueOf(SameSite.class, o.toString().trim().toUpperCase(Locale.ENGLISH));
            contextAttributes.setAttribute(SAME_SITE_DEFAULT_ATTRIBUTE, samesite);
            return samesite;
        }
        catch (Exception e)
        {
            LOG.warn("Bad default value {} for SameSite", o);
            throw new IllegalStateException(e);
        }
    }

    public static String getCommentWithoutAttributes(String comment)
    {
        if (comment == null)
            return null;

        String strippedComment = comment.trim();

        strippedComment = StringUtil.strip(strippedComment, HTTP_ONLY_COMMENT);
        strippedComment = StringUtil.strip(strippedComment, PARTITIONED_COMMENT);
        strippedComment = StringUtil.strip(strippedComment, SAME_SITE_NONE_COMMENT);
        strippedComment = StringUtil.strip(strippedComment, SAME_SITE_LAX_COMMENT);
        strippedComment = StringUtil.strip(strippedComment, SAME_SITE_STRICT_COMMENT);

        return strippedComment.isEmpty() ? null : strippedComment;
    }

    public static String getCommentWithAttributes(String comment, boolean httpOnly, SameSite sameSite)
    {
        return getCommentWithAttributes(comment, httpOnly, sameSite, false);
    }

    public static String getCommentWithAttributes(String comment, boolean httpOnly, SameSite sameSite, boolean partitioned)
    {
        if (comment == null && sameSite == null)
            return null;

        StringBuilder builder = new StringBuilder();
        if (StringUtil.isNotBlank(comment))
        {
            comment = getCommentWithoutAttributes(comment);
            if (StringUtil.isNotBlank(comment))
                builder.append(comment);
        }
        if (httpOnly)
            builder.append(HTTP_ONLY_COMMENT);

        if (sameSite != null)
        {
            switch (sameSite)
            {
                case NONE:
                    builder.append(SAME_SITE_NONE_COMMENT);
                    break;
                case STRICT:
                    builder.append(SAME_SITE_STRICT_COMMENT);
                    break;
                case LAX:
                    builder.append(SAME_SITE_LAX_COMMENT);
                    break;
                default:
                    throw new IllegalArgumentException(sameSite.toString());
            }
        }

        if (partitioned)
            builder.append(PARTITIONED_COMMENT);

        if (builder.length() == 0)
            return null;
        return builder.toString();
    }

    public static class SetCookieHttpField extends HttpField
    {
        final HttpCookie _cookie;

        public SetCookieHttpField(HttpCookie cookie, CookieCompliance compliance)
        {
            super(HttpHeader.SET_COOKIE, cookie.getSetCookie(compliance));
            this._cookie = cookie;
        }

        public HttpCookie getHttpCookie()
        {
            return _cookie;
        }
    }
}
