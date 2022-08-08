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

package org.eclipse.jetty.http;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.Attributes;
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
     * If this string is found within the comment parsed with {@link #isHttpOnlyInComment(String)} the check will return true
     **/
    public static final String HTTP_ONLY_COMMENT = "__HTTP_ONLY__";
    /**
     * These strings are used by {@link #getSameSiteFromComment(String)} to check for a SameSite specifier in the comment
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
        NONE("None"),
        STRICT("Strict"),
        LAX("Lax");

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
    private final Map<String, String> _attributes;

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
        this(name, value, domain, path, maxAge, httpOnly, secure, comment, version, (SameSite)null);
    }

    public HttpCookie(String name, String value, String domain, String path, long maxAge, boolean httpOnly, boolean secure, String comment, int version, SameSite sameSite)
    {

        this(name, value, domain, path, maxAge, httpOnly, secure, comment, version, Collections.singletonMap("SameSite", sameSite == null ? null : sameSite.getAttributeValue()));
    }
    
    public HttpCookie(String name, String value, String domain, String path, long maxAge, boolean httpOnly, boolean secure, String comment, int version, Map<String, String> attributes)
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
        _attributes = (attributes == null ? Collections.emptyMap() : attributes);
    }
    
    public HttpCookie(String name, String value, int version, Map<String, String> attributes)
    {
        _name = name;
        _value = value; 
        _version = version;
        _attributes = (attributes == null ? Collections.emptyMap() : new TreeMap<>(attributes));
        
        //remove all of the well-known attributes, leaving only those pass-through ones
        _domain = _attributes.remove("Domain");
        _path = _attributes.remove("Path");

        String tmp = _attributes.remove("Max-Age");
        _maxAge = StringUtil.isBlank(tmp) ? -1L : Long.valueOf(tmp);
        _expiration = _maxAge < 0 ? -1 : System.nanoTime() + TimeUnit.SECONDS.toNanos(_maxAge);
        _httpOnly = Boolean.parseBoolean(_attributes.remove("HttpOnly"));
        _secure = Boolean.parseBoolean(_attributes.remove("Secure"));
        _comment = _attributes.remove("Comment");
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
        String val = _attributes.get("SameSite");
        if (val == null)
            return null;
        return SameSite.valueOf(val.toUpperCase(Locale.ENGLISH));
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

    public String toString()
    {
        return "%x@%s".formatted(hashCode(), asString());
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
        if (compliance == CookieCompliance.RFC6265)
            return getRFC6265SetCookie();
        if (compliance == CookieCompliance.RFC2965)
            return getRFC2965SetCookie();
        throw new IllegalStateException();
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
        
        String sameSite = _attributes.get("SameSite");
        if (sameSite != null)
        {
            buf.append("; SameSite=");
            buf.append(sameSite);
        }

        //Add all other attributes
        _attributes.entrySet().stream().filter(e -> !"SameSite".equals(e.getKey())).forEach(e -> 
        {
            buf.append("; " + e.getKey() + "=");
            buf.append(e.getValue());
        });

        return buf.toString();
    }

    public static boolean isHttpOnlyInComment(String comment)
    {
        return comment != null && comment.contains(HTTP_ONLY_COMMENT);
    }

    public static SameSite getSameSiteFromComment(String comment)
    {
        if (comment != null)
        {
            if (comment.contains(SAME_SITE_STRICT_COMMENT))
            {
                return SameSite.STRICT;
            }
            if (comment.contains(SAME_SITE_LAX_COMMENT))
            {
                return SameSite.LAX;
            }
            if (comment.contains(SAME_SITE_NONE_COMMENT))
            {
                return SameSite.NONE;
            }
        }

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
    
    /**
     * Check if the Set-Cookie header represented as a string is for the name, domain and path given.
     * 
     * @param setCookieHeader a Set-Cookie header
     * @param name the cookie name to check
     * @param domain the cookie domain to check
     * @param path the cookie path to check
     * @return true if all of the name, domain and path match the Set-Cookie header, false otherwise
     */
    public static boolean match(String setCookieHeader, String name, String domain, String path)
    {
        //Parse the bare minimum 
        List<java.net.HttpCookie> cookies = java.net.HttpCookie.parse(setCookieHeader);
        if (cookies.size() != 1)
            return false;
        
        java.net.HttpCookie cookie = cookies.get(0);
        return match(cookie.getName(), cookie.getDomain(), cookie.getPath(), name, domain, path);
    }
    
    /**
     * Check if the HttpCookie is for the given name, domain and path.
     * 
     * @param cookie the jetty HttpCookie to check
     * @param name the cookie name to check
     * @param domain the cookie domain to check
     * @param path the cookie path to check
     * @return true if all of the name, domain and path all match the HttpCookie, false otherwise
     */
    public static boolean match(HttpCookie cookie, String name, String domain, String path)
    {
        if (cookie == null)
            return false;
        return match(cookie.getName(), cookie.getDomain(), cookie.getPath(), name, domain, path);
    }
    
    /**
     * Check if all old parameters match the new parameters.
     * 
     * @param oldName
     * @param oldDomain
     * @param oldPath
     * @param newName
     * @param newDomain
     * @param newPath
     * @return true if old and new names match exactly and the old and new domains match case-insensitively and the paths match exactly
     */
    private static boolean match(String oldName, String oldDomain, String oldPath, String newName, String newDomain, String newPath)
    {
        if (oldName == null)
        {
            if (newName != null)
                return false;
        }
        else if (!oldName.equals(newName))
            return false;
        
        if (oldDomain == null)
        {
            if (newDomain != null)
                return false;
        }
        else if (!oldDomain.equalsIgnoreCase(newDomain))
            return false;

        if (oldPath== null)
        {
            if (newPath != null)
                return false;
        }
        else if (!oldPath.equals(newPath))
            return false;
        
        return true;
    }

    /**
     * @deprecated We should not need to do this now
     */
    @Deprecated
    public static String getCommentWithoutAttributes(String comment)
    {
        if (comment == null)
        {
            return null;
        }

        String strippedComment = comment.trim();

        strippedComment = StringUtil.strip(strippedComment, HTTP_ONLY_COMMENT);
        strippedComment = StringUtil.strip(strippedComment, SAME_SITE_NONE_COMMENT);
        strippedComment = StringUtil.strip(strippedComment, SAME_SITE_LAX_COMMENT);
        strippedComment = StringUtil.strip(strippedComment, SAME_SITE_STRICT_COMMENT);

        return strippedComment.length() == 0 ? null : strippedComment;
    }

    /**
     * @deprecated We should not need to do this now
     */
    @Deprecated
    public static String getCommentWithAttributes(String comment, boolean httpOnly, SameSite sameSite)
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
                case NONE -> builder.append(SAME_SITE_NONE_COMMENT);
                case STRICT -> builder.append(SAME_SITE_STRICT_COMMENT);
                case LAX -> builder.append(SAME_SITE_LAX_COMMENT);
                default -> throw new IllegalArgumentException(sameSite.toString());
            }
        }

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
    
    /**
     * Check that samesite is set on the cookie. If not, use a
     * context default value, if one has been set.
     *
     * @param cookie the cookie to check
     * @param attributes the context to check settings
     * @return either the original cookie, or a new one that has the samesit default set
     */
    public static HttpCookie checkSameSite(HttpCookie cookie, Attributes attributes)
    {
        if (cookie == null || cookie.getSameSite() != null)
            return cookie;

        //sameSite is not set, use the default configured for the context, if one exists
        SameSite contextDefault = HttpCookie.getSameSiteDefault(attributes);
        if (contextDefault == null)
            return cookie; //no default set

        return new HttpCookie(cookie.getName(),
            cookie.getValue(),
            cookie.getDomain(),
            cookie.getPath(),
            cookie.getMaxAge(),
            cookie.isHttpOnly(),
            cookie.isSecure(),
            cookie.getComment(),
            cookie.getVersion(),
            contextDefault);
    }
}
