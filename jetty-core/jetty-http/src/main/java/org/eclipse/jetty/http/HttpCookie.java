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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jetty Management of RFC6265 HTTP Cookies (with fallback support for RFC2965)
 */
public interface HttpCookie
{
    Logger LOG = LoggerFactory.getLogger(HttpCookie.class);
    
    String __COOKIE_DELIM = "\",;\\ \t";
    String __01Jan1970_COOKIE = DateGenerator.formatCookieDate(0).trim();

    String COMMENT_ATTRIBUTE = "Comment";
    String DOMAIN_ATTRIBUTE = "Domain";
    String HTTP_ONLY_ATTRIBUTE = "HttpOnly";
    String MAX_AGE_ATTRIBUTE = "Max-Age";
    String PATH_ATTRIBUTE = "Path";
    String SAME_SITE_ATTRIBUTE = "SameSite";
    String SECURE_ATTRIBUTE = "Secure";
    Index<String> KNOWN_ATTRIBUTES = new Index.Builder<String>().caseSensitive(false)
        .with(COMMENT_ATTRIBUTE)
        .with(DOMAIN_ATTRIBUTE)
        .with(HTTP_ONLY_ATTRIBUTE)
        .with(MAX_AGE_ATTRIBUTE)
        .with(PATH_ATTRIBUTE)
        .with(SAME_SITE_ATTRIBUTE)
        .with(SECURE_ATTRIBUTE)
        .build();

    /**
     * Name of context attribute with default SameSite cookie value
     */
    String SAME_SITE_DEFAULT_ATTRIBUTE = "org.eclipse.jetty.cookie.sameSiteDefault";

    enum SameSite
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

        public static SameSite from(String sameSite)
        {
            if (sameSite == null)
                return null;
            return switch (StringUtil.asciiToLowerCase(sameSite))
            {
                case "lax" -> SameSite.LAX;
                case "strict" -> SameSite.STRICT;
                case "none" -> SameSite.NONE;
                default -> null;
            };
        }
    }

    /**
     * Create new HttpCookie from specific values.
     *
     * @param name the name of the cookie
     * @param value the value of the cookie
     */
    static HttpCookie from(String name, String value)
    {
        return new Immutable(name, value, 0, null);
    }

    /**
     * Create new HttpCookie from specific values and attributes.
     *
     * @param name the name of the cookie
     * @param value the value of the cookie
     * @param attributes the map of attributes to use with this cookie (this map is used for field values
     *   such as {@link #getDomain()}, {@link #getPath()}, {@link #getMaxAge()}, {@link #isHttpOnly()},
     *   {@link #isSecure()}, {@link #getComment()}.  These attributes are removed from the stored
     *   attributes returned from {@link #getAttributes()}.
     */
    static HttpCookie from(String name, String value, Map<String, String> attributes)
    {
        return new Immutable(name, value, 0, attributes);
    }

    /**
     * Create new HttpCookie from specific values and attributes.
     *
     * @param name the name of the cookie
     * @param value the value of the cookie
     * @param version the version of the cookie (only used in RFC2965 mode)
     * @param attributes the map of attributes to use with this cookie (this map is used for field values
     *   such as {@link #getDomain()}, {@link #getPath()}, {@link #getMaxAge()}, {@link #isHttpOnly()},
     *   {@link #isSecure()}, {@link #getComment()}.  These attributes are removed from the stored
     *   attributes returned from {@link #getAttributes()}.
     */
    static HttpCookie from(String name, String value, int version, Map<String, String> attributes)
    {
        if (attributes == null || attributes.isEmpty())
            return new Immutable(name, value, version, Collections.emptyMap());

        Map<String, String> attrs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        attrs.putAll(attributes);

        return new Immutable(name, value, version, attrs);
    }

    /**
     * @param cookie A cookie to base the new cookie on.
     * @param additionalAttributes Additional name value pairs of strings to use as additional attributes
     * @return A new cookie based on the passed cookie plus additional attributes.
     */
    static HttpCookie from(HttpCookie cookie, String... additionalAttributes)
    {
        if (additionalAttributes.length % 2 != 0)
            throw new IllegalArgumentException("additional attributes must have name and value");
        Map<String, String> attributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        attributes.putAll(Objects.requireNonNull(cookie).getAttributes());
        for (int i = 0; i < additionalAttributes.length; i += 2)
            attributes.put(additionalAttributes[i], additionalAttributes[i + 1]);
        return new Immutable(cookie.getName(), cookie.getValue(), cookie.getVersion(), attributes);
    }

    /**
     * @return the cookie name
     */
    String getName();

    /**
     * @return the cookie value
     */
    String getValue();

    /**
     * @return the cookie version
     */
    int getVersion();

    /**
     * @return the cookie comment.
     *         Equivalent to a `get` of {@link #COMMENT_ATTRIBUTE} on {@link #getAttributes()}.
     */
    default String getComment()
    {
        return getAttributes().get(COMMENT_ATTRIBUTE);
    }

    /**
     * @return the cookie domain.
     *         Equivalent to a `get` of {@link #DOMAIN_ATTRIBUTE} on {@link #getAttributes()}.
     */
    default String getDomain()
    {
        return getAttributes().get(DOMAIN_ATTRIBUTE);
    }

    /**
     * @return the cookie max age in seconds
     *         Equivalent to a `get` of {@link #MAX_AGE_ATTRIBUTE} on {@link #getAttributes()}.
     */
    default long getMaxAge()
    {
        String ma = getAttributes().get(MAX_AGE_ATTRIBUTE);
        return ma == null ? -1 : Long.parseLong(ma);
    }

    /**
     * @return the cookie path
     *         Equivalent to a `get` of {@link #PATH_ATTRIBUTE} on {@link #getAttributes()}.
     */
    default String getPath()
    {
        return getAttributes().get(PATH_ATTRIBUTE);
    }

    /**
     * @return whether the cookie is valid for secure domains
     *         Equivalent to a `get` of {@link #SECURE_ATTRIBUTE} on {@link #getAttributes()}.
     */
    default boolean isSecure()
    {
        return Boolean.parseBoolean(getAttributes().get(SECURE_ATTRIBUTE));
    }

    /**
     * @return the cookie {@code SameSite} attribute value
     *         Equivalent to a `get` of {@link #SAME_SITE_ATTRIBUTE} on {@link #getAttributes()}.
     */
    default SameSite getSameSite()
    {
        return SameSite.from(getAttributes().get(SAME_SITE_ATTRIBUTE));
    }

    /**
     * @return whether the cookie is valid for the http protocol only
     *         Equivalent to a `get` of {@link #HTTP_ONLY_ATTRIBUTE} on {@link #getAttributes()}.
     */
    default boolean isHttpOnly()
    {
        return Boolean.parseBoolean(getAttributes().get(HTTP_ONLY_ATTRIBUTE));
    }

    /**
     * @return the attributes associated with this cookie
     */
    Map<String, String> getAttributes();

    /**
     * @return a string representation of this cookie
     */
    default String asString()
    {
        return HttpCookie.asString(this);
    }

    /**
     * @return a string representation of this cookie
     */
    static String asString(HttpCookie httpCookie)
    {
        StringBuilder builder = new StringBuilder();
        builder.append(httpCookie.getName()).append("=").append(httpCookie.getValue());
        String domain = httpCookie.getDomain();
        if (domain != null)
            builder.append(";$Domain=").append(domain);
        String path = httpCookie.getPath();
        if (path != null)
            builder.append(";$Path=").append(path);
        return builder.toString();
    }

    static String toString(HttpCookie httpCookie)
    {
        return "%x@%s".formatted(httpCookie.hashCode(), asString(httpCookie));
    }

    /**
     * Immutable implementation of HttpCookie.
     */
    class Immutable implements HttpCookie
    {
        private final String _name;
        private final String _value;
        private final int _version;
        private final Map<String, String> _attributes;

        Immutable(String name, String value, String domain, String path, long maxAge, boolean httpOnly, boolean secure, String comment, int version, SameSite sameSite, Map<String, String> attributes)
        {
            _name = name;
            _value = value;
            _version = version;
            Map<String, String> attrs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (attributes != null)
                attrs.putAll(attributes);
            attrs.put(DOMAIN_ATTRIBUTE, domain);
            attrs.put(PATH_ATTRIBUTE, path);
            attrs.put(MAX_AGE_ATTRIBUTE, Long.toString(maxAge));
            attrs.put(HTTP_ONLY_ATTRIBUTE, Boolean.toString(httpOnly));
            attrs.put(SECURE_ATTRIBUTE, Boolean.toString(secure));
            attrs.put(COMMENT_ATTRIBUTE, comment);
            attrs.put(SAME_SITE_ATTRIBUTE, sameSite == null ? null : sameSite.getAttributeValue());
            _attributes = Collections.unmodifiableMap(attrs);
        }

        Immutable(String name, String value, int version, Map<String, String> attributes)
        {
            _name = name;
            _value = value;
            _version = version;
            _attributes = attributes == null ? Collections.emptyMap() : attributes;
        }

        /**
         * @return the cookie name
         */
        @Override
        public String getName()
        {
            return _name;
        }

        /**
         * @return the cookie value
         */
        @Override
        public String getValue()
        {
            return _value;
        }

        /**
         * @return the cookie version
         */
        @Override
        public int getVersion()
        {
            return _version;
        }

        /**
         * @return the cookie {@code SameSite} attribute value
         */
        @Override
        public SameSite getSameSite()
        {
            String val = _attributes.get(SAME_SITE_ATTRIBUTE);
            if (val == null)
                return null;
            return SameSite.valueOf(val.toUpperCase(Locale.ENGLISH));
        }

        /**
         * @return the attributes associated with this cookie
         */
        @Override
        public Map<String, String> getAttributes()
        {
            return _attributes;
        }

        @Override
        public String toString()
        {
            return "%x@%s".formatted(hashCode(), asString());
        }
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
     * @throws IllegalArgumentException If there is a String contains unexpected / illegal characters
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

    static String getSetCookie(HttpCookie httpCookie, CookieCompliance compliance)
    {
        if (compliance == CookieCompliance.RFC6265)
            return getRFC6265SetCookie(httpCookie);
        if (compliance == CookieCompliance.RFC2965)
            return getRFC2965SetCookie(httpCookie);
        throw new IllegalStateException();
    }

    static String getRFC2965SetCookie(HttpCookie httpCookie)
    {
        // Check arguments
        String name = httpCookie.getName();
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Bad cookie name");

        // Format value and params
        StringBuilder buf = new StringBuilder();

        // Name is checked for legality by servlet spec, but can also be passed directly so check again for quoting
        boolean quoteName = isQuoteNeededForCookie(name);
        quoteOnlyOrAppend(buf, name, quoteName);

        buf.append('=');

        // Append the value
        String value = httpCookie.getValue();
        boolean quoteValue = isQuoteNeededForCookie(value);
        quoteOnlyOrAppend(buf, value, quoteValue);

        // Look for domain and path fields and check if they need to be quoted
        String domain = httpCookie.getDomain();
        boolean hasDomain = domain != null && domain.length() > 0;
        boolean quoteDomain = hasDomain && isQuoteNeededForCookie(domain);

        String path = httpCookie.getPath();
        boolean hasPath = path != null && path.length() > 0;
        boolean quotePath = hasPath && isQuoteNeededForCookie(path);

        // Upgrade the version if we have a comment or we need to quote value/path/domain or if they were already quoted
        int version = httpCookie.getVersion();
        String comment = httpCookie.getComment();
        if (version == 0 && (comment != null || quoteName || quoteValue || quoteDomain || quotePath ||
            QuotedStringTokenizer.isQuoted(name) || QuotedStringTokenizer.isQuoted(value) ||
            QuotedStringTokenizer.isQuoted(path) || QuotedStringTokenizer.isQuoted(domain)))
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
            quoteOnlyOrAppend(buf, path, quotePath);
        }

        // Append domain
        if (hasDomain)
        {
            buf.append(";Domain=");
            quoteOnlyOrAppend(buf, domain, quoteDomain);
        }

        // Handle max-age and/or expires
        long maxAge = httpCookie.getMaxAge();
        if (maxAge >= 0)
        {
            // Always use expires
            // This is required as some browser (M$ this means you!) don't handle max-age even with v1 cookies
            buf.append(";Expires=");
            if (maxAge == 0)
                buf.append(__01Jan1970_COOKIE);
            else
                DateGenerator.formatCookieDate(buf, System.currentTimeMillis() + 1000L * maxAge);

            buf.append(";Max-Age=");
            buf.append(maxAge);
        }

        // add the other fields
        if (httpCookie.isSecure())
            buf.append(";Secure");
        if (httpCookie.isHttpOnly())
            buf.append(";HttpOnly");
        if (comment != null)
        {
            buf.append(";Comment=");
            quoteOnlyOrAppend(buf, comment, isQuoteNeededForCookie(comment));
        }
        return buf.toString();
    }

    static String getRFC6265SetCookie(HttpCookie httpCookie)
    {
        // Check arguments
        String name = httpCookie.getName();
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Bad cookie name");

        // Name is checked for legality by servlet spec, but can also be passed directly so check again for quoting
        // Per RFC6265, Cookie.name follows RFC2616 Section 2.2 token rules
        Syntax.requireValidRFC2616Token(name, "RFC6265 Cookie name");
        // Ensure that Per RFC6265, Cookie.value follows syntax rules
        String value = httpCookie.getValue();
        Syntax.requireValidRFC6265CookieValue(value);

        // Format value and params
        StringBuilder buf = new StringBuilder();
        buf.append(name).append('=').append(value == null ? "" : value);

        // Append path
        String path = httpCookie.getPath();
        if (path != null && path.length() > 0)
            buf.append("; Path=").append(path);

        // Append domain
        String domain = httpCookie.getDomain();
        if (domain != null && domain.length() > 0)
            buf.append("; Domain=").append(domain);

        // Handle max-age and/or expires
        long maxAge = httpCookie.getMaxAge();
        if (maxAge >= 0)
        {
            // Always use expires
            // This is required as some browser (M$ this means you!) don't handle max-age even with v1 cookies
            buf.append("; Expires=");
            if (maxAge == 0)
                buf.append(__01Jan1970_COOKIE);
            else
                DateGenerator.formatCookieDate(buf, System.currentTimeMillis() + 1000L * maxAge);

            buf.append("; Max-Age=");
            buf.append(maxAge);
        }

        // add the other fields
        if (httpCookie.isSecure())
            buf.append("; Secure");
        if (httpCookie.isHttpOnly())
            buf.append("; HttpOnly");

        Map<String, String> attributes = httpCookie.getAttributes();

        String sameSiteAttr = attributes.get(SAME_SITE_ATTRIBUTE);
        if (sameSiteAttr != null)
        {
            buf.append("; SameSite=");
            buf.append(sameSiteAttr);
        }
        else
        {
            SameSite sameSite = httpCookie.getSameSite();
            if (sameSite != null)
            {
                buf.append("; SameSite=");
                buf.append(sameSite.getAttributeValue());
            }
        }

        //Add all other attributes
        for (Map.Entry<String, String> e : attributes.entrySet())
        {
            if (KNOWN_ATTRIBUTES.contains(e.getKey()))
                continue;
            buf.append("; ").append(e.getKey()).append("=");
            buf.append(e.getValue());
        }

        return buf.toString();
    }

    /**
     * Get the default value for SameSite cookie attribute, if one
     * has been set for the given context.
     * 
     * @param contextAttributes the context to check for default SameSite value
     * @return the default SameSite value or null if one does not exist
     * @throws IllegalStateException if the default value is not a permitted value
     */
    static SameSite getSameSiteDefault(Attributes contextAttributes)
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
     * Extract the bare minimum of info from a Set-Cookie header string.
     *
     * <p>
     * Ideally this method should not be necessary, however as java.net.HttpCookie
     * does not yet support generic attributes, we have to use it in a minimal
     * fashion. When it supports attributes, we could look at reverting to a
     * constructor on o.e.j.h.HttpCookie to take the set-cookie header string.
     * </p>
     *
     * @param setCookieHeader the header as a string
     * @return a map containing the name, value, domain, path. max-age of the set cookie header
     */
    static Map<String, String> extractBasics(String setCookieHeader)
    {
        //Parse the bare minimum
        List<java.net.HttpCookie> cookies = java.net.HttpCookie.parse(setCookieHeader);
        if (cookies.size() != 1)
            return Collections.emptyMap();
        java.net.HttpCookie cookie = cookies.get(0);
        Map<String, String> fields = new HashMap<>();
        fields.put("name", cookie.getName());
        fields.put("value", cookie.getValue());
        fields.put("domain", cookie.getDomain());
        fields.put("path",  cookie.getPath());
        fields.put("max-age", Long.toString(cookie.getMaxAge()));
        return fields;
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
    static boolean match(String setCookieHeader, String name, String domain, String path)
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
     * @return true if name, domain, and path, match all match the HttpCookie, false otherwise
     */
    static boolean match(HttpCookie cookie, String name, String domain, String path)
    {
        if (cookie == null)
            return false;
        return match(cookie.getName(), cookie.getDomain(), cookie.getPath(), name, domain, path);
    }

    /**
     * Check if all old parameters match the new parameters.
     *
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

        if (oldPath == null)
            return newPath == null;

        return oldPath.equals(newPath);
    }

    class SetCookieHttpField extends HttpField
    {
        final HttpCookie _cookie;

        public SetCookieHttpField(HttpCookie cookie, CookieCompliance compliance)
        {
            super(HttpHeader.SET_COOKIE, getSetCookie(cookie, compliance));
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
    static HttpCookie checkSameSite(HttpCookie cookie, Attributes attributes)
    {
        if (cookie == null || cookie.getSameSite() != null)
            return cookie;

        //sameSite is not set, use the default configured for the context, if one exists
        SameSite contextDefault = HttpCookie.getSameSiteDefault(attributes);
        if (contextDefault == null)
            return cookie; //no default set

        return HttpCookie.from(cookie, HttpCookie.SAME_SITE_ATTRIBUTE, contextDefault.getAttributeValue());
    }
}
