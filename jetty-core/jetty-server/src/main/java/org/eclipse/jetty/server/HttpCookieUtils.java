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

package org.eclipse.jetty.server;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.CookieCompliance;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.QuotedCSVParser;
import org.eclipse.jetty.http.Syntax;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.QuotedStringTokenizer;

/**
 * <p>Utility methods for server-side HTTP cookie handling.</p>
 */
public final class HttpCookieUtils
{
    /**
     * Name of context attribute with default SameSite cookie value
     */
    public static final String SAME_SITE_DEFAULT_ATTRIBUTE = "org.eclipse.jetty.cookie.sameSiteDefault";

    private static final Index<String> KNOWN_ATTRIBUTES = new Index.Builder<String>().caseSensitive(false)
        .with(HttpCookie.COMMENT_ATTRIBUTE)
        .with(HttpCookie.DOMAIN_ATTRIBUTE)
        .with(HttpCookie.EXPIRES_ATTRIBUTE)
        .with(HttpCookie.HTTP_ONLY_ATTRIBUTE)
        .with(HttpCookie.MAX_AGE_ATTRIBUTE)
        .with(HttpCookie.PATH_ATTRIBUTE)
        .with(HttpCookie.SAME_SITE_ATTRIBUTE)
        .with(HttpCookie.SECURE_ATTRIBUTE)
        .build();
    // RFC 1123 format of epoch for the Expires attribute.
    private static final String EPOCH_EXPIRES = "Thu, 01 Jan 1970 00:00:00 GMT";

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
        HttpCookie.SameSite contextDefault = getSameSiteDefault(attributes);
        if (contextDefault == null)
            return cookie; //no default set

        return HttpCookie.from(cookie, HttpCookie.SAME_SITE_ATTRIBUTE, contextDefault.getAttributeValue());
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
    public static Map<String, String> extractBasics(String setCookieHeader)
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
     * Get the default value for SameSite cookie attribute, if one
     * has been set for the given context.
     *
     * @param contextAttributes the context to check for default SameSite value
     * @return the default SameSite value or null if one does not exist
     * @throws IllegalStateException if the default value is not a permitted value
     */
    public static HttpCookie.SameSite getSameSiteDefault(Attributes contextAttributes)
    {
        if (contextAttributes == null)
            return null;
        Object o = contextAttributes.getAttribute(SAME_SITE_DEFAULT_ATTRIBUTE);
        if (o == null)
            return null;

        if (o instanceof HttpCookie.SameSite)
            return (HttpCookie.SameSite)o;

        try
        {
            HttpCookie.SameSite samesite = Enum.valueOf(HttpCookie.SameSite.class, o.toString().trim().toUpperCase(Locale.ENGLISH));
            contextAttributes.setAttribute(SAME_SITE_DEFAULT_ATTRIBUTE, samesite);
            return samesite;
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    public static String getSetCookie(HttpCookie httpCookie, CookieCompliance compliance)
    {
        if (compliance == null || CookieCompliance.RFC6265_LEGACY.compliesWith(compliance))
            return getRFC6265SetCookie(httpCookie);
        return getRFC2965SetCookie(httpCookie);
    }

    public static String getRFC2965SetCookie(HttpCookie httpCookie)
    {
        // Check arguments
        String name = httpCookie.getName();
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Invalid cookie name");

        StringBuilder builder = new StringBuilder();

        quoteIfNeededAndAppend(name, builder);

        builder.append('=');

        String value = httpCookie.getValue();
        quoteIfNeededAndAppend(value, builder);

        // Look for domain and path fields and check if they need to be quoted.
        String domain = httpCookie.getDomain();
        boolean hasDomain = domain != null && domain.length() > 0;
        boolean quoteDomain = hasDomain && isQuoteNeeded(domain);

        String path = httpCookie.getPath();
        boolean hasPath = path != null && path.length() > 0;
        boolean quotePath = hasPath && isQuoteNeeded(path);

        // Upgrade the version if we have a comment or we need to quote value/path/domain or if they were already quoted
        int version = httpCookie.getVersion();
        String comment = httpCookie.getComment();
        if (version == 0 && (comment != null || isQuoteNeeded(name) || isQuoteNeeded(value) || quoteDomain || quotePath ||
                             QuotedStringTokenizer.isQuoted(name) || QuotedStringTokenizer.isQuoted(value) ||
                             QuotedStringTokenizer.isQuoted(path) || QuotedStringTokenizer.isQuoted(domain)))
            version = 1;

        if (version == 1)
            builder.append(";Version=1");
        else if (version > 1)
            builder.append(";Version=").append(version);

        if (hasDomain)
        {
            builder.append(";Domain=");
            if (quoteDomain)
                HttpField.PARAMETER_TOKENIZER.quote(builder, domain);
            else
                builder.append(domain);
        }

        if (hasPath)
        {
            builder.append(";Path=");
            if (quotePath)
                HttpField.PARAMETER_TOKENIZER.quote(builder, path);
            else
                builder.append(path);
        }

        // Handle max-age and/or expires
        long maxAge = httpCookie.getMaxAge();
        if (maxAge >= 0)
        {
            // Always add the Expires attribute too, as some
            // browsers do not handle max-age even with v1 cookies.
            builder.append(";Expires=");
            if (maxAge == 0)
                builder.append(EPOCH_EXPIRES);
            else
                builder.append(HttpCookie.formatExpires(Instant.now().plusSeconds(maxAge)));

            builder.append(";Max-Age=");
            builder.append(maxAge);
        }

        if (httpCookie.isSecure())
            builder.append(";Secure");

        if (httpCookie.isHttpOnly())
            builder.append(";HttpOnly");

        HttpCookie.SameSite sameSite = httpCookie.getSameSite();
        if (sameSite != null)
            builder.append(";SameSite=").append(sameSite.getAttributeValue());

        if (comment != null)
        {
            builder.append(";Comment=");
            quoteIfNeededAndAppend(comment, builder);
        }

        return builder.toString();
    }

    public static String getRFC6265SetCookie(HttpCookie httpCookie)
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
        StringBuilder builder = new StringBuilder();
        builder.append(name).append('=').append(value == null ? "" : value);

        // Append path
        String path = httpCookie.getPath();
        if (path != null && path.length() > 0)
            builder.append("; Path=").append(path);

        // Append domain
        String domain = httpCookie.getDomain();
        if (domain != null && domain.length() > 0)
            builder.append("; Domain=").append(domain);

        // Handle max-age and/or expires
        long maxAge = httpCookie.getMaxAge();
        if (maxAge >= 0)
        {
            // Always use expires
            // This is required as some browser (M$ this means you!) don't handle max-age even with v1 cookies
            builder.append("; Expires=");
            if (maxAge == 0)
                builder.append(EPOCH_EXPIRES);
            else
                builder.append(HttpCookie.formatExpires(Instant.now().plusSeconds(maxAge)));

            builder.append("; Max-Age=");
            builder.append(maxAge);
        }

        // add the other fields
        if (httpCookie.isSecure())
            builder.append("; Secure");
        if (httpCookie.isHttpOnly())
            builder.append("; HttpOnly");

        Map<String, String> attributes = httpCookie.getAttributes();

        String sameSiteAttr = attributes.get(HttpCookie.SAME_SITE_ATTRIBUTE);
        if (sameSiteAttr != null)
        {
            builder.append("; SameSite=");
            builder.append(sameSiteAttr);
        }
        else
        {
            HttpCookie.SameSite sameSite = httpCookie.getSameSite();
            if (sameSite != null)
            {
                builder.append("; SameSite=");
                builder.append(sameSite.getAttributeValue());
            }
        }

        //Add all other attributes
        for (Map.Entry<String, String> e : attributes.entrySet())
        {
            if (KNOWN_ATTRIBUTES.contains(e.getKey()))
                continue;
            builder.append("; ").append(e.getKey()).append("=");
            builder.append(e.getValue());
        }

        return builder.toString();
    }

    /**
     * <p>Whether a cookie name/value/attribute needs to be quoted.</p>
     *
     * @param text the text to check
     * @return whether the text needs to be quoted
     * @throws IllegalArgumentException if the text contains illegal characters
     */
    private static boolean isQuoteNeeded(String text)
    {
        if (text == null || text.length() == 0)
            return true;

        if (QuotedStringTokenizer.isQuoted(text))
            return false;

        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if ("\",;\\ \t".indexOf(c) >= 0)
                return true;

            if (c < 0x20 || c >= 0x7F)
                throw new IllegalArgumentException("Illegal character in cookie value");
        }

        return false;
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
     * @return true if name, domain, and path, match all match the HttpCookie, false otherwise
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

    /**
     * Get a {@link HttpHeader#SET_COOKIE} field as a {@link HttpCookie}, either
     * by optimally checking for a {@link SetCookieHttpField} or by parsing
     * the value with {@link #parseSetCookie(String)}.
     * @param field The field
     * @return The field value as a {@link HttpCookie} or null if the field
     *         is not a {@link HttpHeader#SET_COOKIE} or cannot be parsed.
     */
    public static HttpCookie getSetCookie(HttpField field)
    {
        if (field == null || field.getHeader() != HttpHeader.SET_COOKIE)
            return null;
        if (field instanceof SetCookieHttpField setCookieHttpField)
            return setCookieHttpField.getHttpCookie();
        return parseSetCookie(field.getValue());
    }

    public static HttpCookie parseSetCookie(String value)
    {
        AtomicReference<HttpCookie.Builder> builder = new AtomicReference<>();
        new QuotedCSVParser(false)
        {
            @Override
            protected void parsedParam(StringBuffer buffer, int valueLength, int paramName, int paramValue)
            {
                String name = buffer.substring(paramName, paramValue - 1);
                String value = buffer.substring(paramValue);
                HttpCookie.Builder b = builder.get();
                if (b == null)
                {
                    b = HttpCookie.build(name, value);
                    builder.set(b);
                }
                else
                {
                    b.attribute(name, value);
                }
            }
        }.addValue(value);

        HttpCookie.Builder b = builder.get();
        if (b == null)
            return null;
        return b.build();
    }

    private static void quoteIfNeededAndAppend(String text, StringBuilder builder)
    {
        if (isQuoteNeeded(text))
            HttpField.PARAMETER_TOKENIZER.quote(builder, text);
        else
            builder.append(text);
    }

    private HttpCookieUtils()
    {
    }

    /**
     * A {@link HttpField} that holds an {@link HttpHeader#SET_COOKIE} as a
     * {@link HttpCookie} instance, delaying any value generation until
     * {@link #getValue()} is called.
     */
    public static class SetCookieHttpField extends HttpField
    {
        private final HttpCookie _cookie;
        private final CookieCompliance _compliance;

        public SetCookieHttpField(HttpCookie cookie, CookieCompliance compliance)
        {
            super(HttpHeader.SET_COOKIE, (String)null);
            this._cookie = cookie;
            _compliance = compliance;
        }

        public HttpCookie getHttpCookie()
        {
            return _cookie;
        }

        @Override
        public String getValue()
        {
            return getSetCookie(_cookie, _compliance);
        }
    }
}
