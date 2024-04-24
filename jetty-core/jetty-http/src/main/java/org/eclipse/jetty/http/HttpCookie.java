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

import java.time.Instant;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.eclipse.jetty.util.Index;

/**
 * <p>Implementation of RFC6265 HTTP Cookies (with fallback support for RFC2965).</p>
 */
public interface HttpCookie
{
    String COMMENT_ATTRIBUTE = "Comment";
    String DOMAIN_ATTRIBUTE = "Domain";
    String EXPIRES_ATTRIBUTE = "Expires";
    String HTTP_ONLY_ATTRIBUTE = "HttpOnly";
    String MAX_AGE_ATTRIBUTE = "Max-Age";
    String PATH_ATTRIBUTE = "Path";
    String SAME_SITE_ATTRIBUTE = "SameSite";
    String SECURE_ATTRIBUTE = "Secure";
    String PARTITIONED_ATTRIBUTE = "Partitioned";

    /**
     * @return the cookie name
     */
    String getName();

    /**
     * @return the cookie value
     */
    String getValue();

    /**
     * @return the value of the {@code Version} attribute
     */
    int getVersion();

    /**
     * @return the attributes associated with this cookie
     */
    Map<String, String> getAttributes();

    /**
     * @return the value of the {@code Expires} attribute, or {@code null} if not present
     * @see #EXPIRES_ATTRIBUTE
     */
    default Instant getExpires()
    {
        String expires = getAttributes().get(EXPIRES_ATTRIBUTE);
        return expires == null ? null : parseExpires(expires);
    }

    /**
     * @return the value of the {@code Max-Age} attribute, in seconds, or {@code -1} if not present
     * @see #MAX_AGE_ATTRIBUTE
     */
    default long getMaxAge()
    {
        String ma = getAttributes().get(MAX_AGE_ATTRIBUTE);
        return ma == null ? -1 : Long.parseLong(ma);
    }

    /**
     * @return whether the cookie is expired
     */
    default boolean isExpired()
    {
        if (getMaxAge() == 0)
            return true;
        Instant expires = getExpires();
        return expires != null && Instant.now().isAfter(expires);
    }

    /**
     * <p>Equivalent to {@code getAttributes().get(COMMENT_ATTRIBUTE)}.</p>
     *
     * @return the value of the {@code Comment} attribute
     * @see #COMMENT_ATTRIBUTE
     */
    default String getComment()
    {
        return getAttributes().get(COMMENT_ATTRIBUTE);
    }

    /**
     * <p>Equivalent to {@code getAttributes().get(DOMAIN_ATTRIBUTE)}.</p>
     *
     * @return the value of the {@code Domain} attribute
     * @see #DOMAIN_ATTRIBUTE
     */
    default String getDomain()
    {
        return getAttributes().get(DOMAIN_ATTRIBUTE);
    }

    /**
     * <p>Equivalent to {@code getAttributes().get(PATH_ATTRIBUTE)}.</p>
     *
     * @return the value of the {@code Path} attribute
     * @see #PATH_ATTRIBUTE
     */
    default String getPath()
    {
        return getAttributes().get(PATH_ATTRIBUTE);
    }

    /**
     * @return whether the {@code Secure} attribute is present
     * @see #SECURE_ATTRIBUTE
     */
    default boolean isSecure()
    {
        return Boolean.parseBoolean(getAttributes().get(SECURE_ATTRIBUTE));
    }

    /**
     * @return the value of the {@code SameSite} attribute
     * @see #SAME_SITE_ATTRIBUTE
     */
    default SameSite getSameSite()
    {
        return SameSite.from(getAttributes().get(SAME_SITE_ATTRIBUTE));
    }

    /**
     * @return whether the {@code HttpOnly} attribute is present
     * @see #HTTP_ONLY_ATTRIBUTE
     */
    default boolean isHttpOnly()
    {
        return Boolean.parseBoolean(getAttributes().get(HTTP_ONLY_ATTRIBUTE));
    }

    /**
     * @return whether the {@code Partitioned} attribute is present
     * @see #PARTITIONED_ATTRIBUTE
     */
    default boolean isPartitioned()
    {
        return Boolean.parseBoolean(getAttributes().get(PARTITIONED_ATTRIBUTE));
    }

    /**
     * @return the cookie hash code
     * @see #hashCode(HttpCookie)
     */
    @Override
    int hashCode();

    /**
     * @param obj the object to test for equality
     * @return whether this cookie is equal to the given object
     * @see #equals(HttpCookie, Object)
     */
    @Override
    boolean equals(Object obj);

    /**
     * <p>A wrapper for {@code HttpCookie} instances.</p>
     */
    class Wrapper implements HttpCookie
    {
        private final HttpCookie wrapped;

        public Wrapper(HttpCookie wrapped)
        {
            this.wrapped = Objects.requireNonNull(wrapped);
        }

        public HttpCookie getWrapped()
        {
            return wrapped;
        }

        @Override
        public String getName()
        {
            return getWrapped().getName();
        }

        @Override
        public String getValue()
        {
            return getWrapped().getValue();
        }

        @Override
        public int getVersion()
        {
            return getWrapped().getVersion();
        }

        @Override
        public Map<String, String> getAttributes()
        {
            return getWrapped().getAttributes();
        }

        @Override
        public Instant getExpires()
        {
            return getWrapped().getExpires();
        }

        @Override
        public long getMaxAge()
        {
            return getWrapped().getMaxAge();
        }

        @Override
        public boolean isExpired()
        {
            return getWrapped().isExpired();
        }

        @Override
        public String getComment()
        {
            return getWrapped().getComment();
        }

        @Override
        public String getDomain()
        {
            return getWrapped().getDomain();
        }

        @Override
        public String getPath()
        {
            return getWrapped().getPath();
        }

        @Override
        public boolean isSecure()
        {
            return getWrapped().isSecure();
        }

        @Override
        public SameSite getSameSite()
        {
            return getWrapped().getSameSite();
        }

        @Override
        public boolean isHttpOnly()
        {
            return getWrapped().isHttpOnly();
        }

        @Override
        public boolean isPartitioned()
        {
            return getWrapped().isPartitioned();
        }

        @Override
        public int hashCode()
        {
            return HttpCookie.hashCode(this);
        }

        @Override
        public boolean equals(Object obj)
        {
            return HttpCookie.equals(this, obj);
        }

        @Override
        public String toString()
        {
            return HttpCookie.toString(this);
        }
    }

    /**
     * <p>Immutable implementation of {@link HttpCookie}.</p>
     */
    class Immutable implements HttpCookie
    {
        private final String _name;
        private final String _value;
        private final int _version;
        private final Map<String, String> _attributes;

        private Immutable(String name, String value, int version, Map<String, String> attributes)
        {
            _name = name;
            _value = value;
            _version = version;
            _attributes = attributes == null || attributes.isEmpty() ? Collections.emptyMap() : attributes;
        }

        @Override
        public String getName()
        {
            return _name;
        }

        @Override
        public String getValue()
        {
            return _value;
        }

        @Override
        public int getVersion()
        {
            return _version;
        }

        @Override
        public Map<String, String> getAttributes()
        {
            return _attributes;
        }

        @Override
        public int hashCode()
        {
            return HttpCookie.hashCode(this);
        }

        @Override
        public boolean equals(Object obj)
        {
            return HttpCookie.equals(this, obj);
        }

        @Override
        public String toString()
        {
            return HttpCookie.toString(this);
        }
    }

    /**
     * <p>The possible values for the {@code SameSite} attribute, defined
     * in the follow-up of RFC 6265, at the time of this writing defined at
     * <a href="https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-rfc6265bis">RFC 6265bis</a>.</p>
     */
    enum SameSite
    {
        /**
         * The value {@code None} for the {@code SameSite} attribute
         */
        NONE("None"),
        /**
         * The value {@code Strict} for the {@code SameSite} attribute
         */
        STRICT("Strict"),
        /**
         * The value {@code Lax} for the {@code SameSite} attribute
         */
        LAX("Lax");

        private final String attributeValue;

        SameSite(String attributeValue)
        {
            this.attributeValue = attributeValue;
        }

        /**
         * @return the {@code SameSite} attribute value
         */
        public String getAttributeValue()
        {
            return this.attributeValue;
        }

        private static final Index<SameSite> CACHE = new Index.Builder<SameSite>()
            .caseSensitive(false)
            .with(NONE.attributeValue, NONE)
            .with(STRICT.attributeValue, STRICT)
            .with(LAX.attributeValue, LAX)
            .build();

        /**
         * @param sameSite the {@code SameSite} attribute value
         * @return the enum constant associated with the {@code SameSite} attribute value,
         * or {@code null} if the value is not a known {@code SameSite} attribute value
         */
        public static SameSite from(String sameSite)
        {
            if (sameSite == null)
                return null;
            return CACHE.get(sameSite);
        }
    }

    /**
     * <p>A {@link HttpCookie} that wraps a {@link java.net.HttpCookie}.</p>
     */
    class JavaNetHttpCookie implements HttpCookie
    {
        private final java.net.HttpCookie _httpCookie;
        private Map<String, String> _attributes;

        private JavaNetHttpCookie(java.net.HttpCookie httpCookie)
        {
            _httpCookie = httpCookie;
        }

        @Override
        public String getComment()
        {
            return _httpCookie.getComment();
        }

        @Override
        public String getDomain()
        {
            return _httpCookie.getDomain();
        }

        @Override
        public long getMaxAge()
        {
            return _httpCookie.getMaxAge();
        }

        @Override
        public String getPath()
        {
            return _httpCookie.getPath();
        }

        @Override
        public boolean isSecure()
        {
            return _httpCookie.getSecure();
        }

        @Override
        public String getName()
        {
            return _httpCookie.getName();
        }

        @Override
        public String getValue()
        {
            return _httpCookie.getValue();
        }

        @Override
        public int getVersion()
        {
            return _httpCookie.getVersion();
        }

        @Override
        public boolean isHttpOnly()
        {
            return _httpCookie.isHttpOnly();
        }

        @Override
        public Map<String, String> getAttributes()
        {
            if (_attributes == null)
            {
                Map<String, String> attributes = lazyAttributePut(null, COMMENT_ATTRIBUTE, getComment());
                attributes = lazyAttributePut(attributes, DOMAIN_ATTRIBUTE, getDomain());
                if (isHttpOnly())
                    attributes = lazyAttributePut(attributes, HTTP_ONLY_ATTRIBUTE, Boolean.TRUE.toString());
                if (getMaxAge() >= 0)
                    attributes = lazyAttributePut(attributes, MAX_AGE_ATTRIBUTE, Long.toString(getMaxAge()));
                attributes = lazyAttributePut(attributes, PATH_ATTRIBUTE, getPath());
                if (isSecure())
                    attributes = lazyAttributePut(attributes, SECURE_ATTRIBUTE, Boolean.TRUE.toString());
                _attributes = HttpCookie.lazyAttributes(attributes);
            }
            return _attributes;
        }

        @Override
        public int hashCode()
        {
            return HttpCookie.hashCode(this);
        }

        @Override
        public boolean equals(Object obj)
        {
            return HttpCookie.equals(this, obj);
        }

        @Override
        public String toString()
        {
            return HttpCookie.toString(this);
        }
    }

    /**
     * <p>A builder for {@link HttpCookie} instances.</p>
     * <p>The typical usage is to use one of the
     * {@link HttpCookie#build(String, String) build methods}
     * to obtain the builder, and then chain method calls to
     * customize the cookie attributes and finally calling
     * the {@link #build()} method, for example:</p>
     * <pre>{@code
     * HttpCookie cookie = HttpCookie.build("name", "value")
     *     .maxAge(24 * 60 * 60)
     *     .domain("example.com")
     *     .path("/")
     *     .build();
     * }</pre>
     *
     * @see HttpCookie#build(String, String)
     * @see #build()
     */
    class Builder
    {
        private final String _name;
        private final String _value;
        private final int _version;
        private Map<String, String> _attributes;

        private Builder(String name, String value, int version)
        {
            _name = name;
            _value = value;
            _version = version;
        }

        public Builder attribute(String name, String value)
        {
            if (name == null)
                return this;
            // Sanity checks on the values, expensive but necessary to avoid to store garbage.
            switch (name.toLowerCase(Locale.ENGLISH))
            {
                case "expires" -> expires(parseExpires(value));
                case "httponly" ->
                {
                    if (!isTruthy(value))
                        throw new IllegalArgumentException("Invalid HttpOnly attribute");
                    httpOnly(true);
                }
                case "max-age" -> maxAge(Long.parseLong(value));
                case "samesite" ->
                {
                    SameSite sameSite = SameSite.from(value);
                    if (sameSite == null)
                        throw new IllegalArgumentException("Invalid SameSite attribute");
                    sameSite(sameSite);
                }
                case "secure" ->
                {
                    if (!isTruthy(value))
                        throw new IllegalArgumentException("Invalid Secure attribute");
                    secure(true);
                }
                case "partitioned" ->
                {
                    if (!isTruthy(value))
                        throw new IllegalArgumentException("Invalid Partitioned attribute");
                    partitioned(true);
                }
                default -> _attributes = lazyAttributePut(_attributes, name, value);
            }
            return this;
        }

        private boolean isTruthy(String value)
        {
            return value != null && (value.isEmpty() || "true".equalsIgnoreCase(value));
        }

        public Builder comment(String comment)
        {
            _attributes = lazyAttributePut(_attributes, COMMENT_ATTRIBUTE, comment);
            return this;
        }

        public Builder domain(String domain)
        {
            _attributes = lazyAttributePut(_attributes, DOMAIN_ATTRIBUTE, domain);
            return this;
        }

        public Builder httpOnly(boolean httpOnly)
        {
            if (httpOnly)
                _attributes = lazyAttributePut(_attributes, HTTP_ONLY_ATTRIBUTE, Boolean.TRUE.toString());
            else
                _attributes = lazyAttributeRemove(_attributes, HTTP_ONLY_ATTRIBUTE);
            return this;
        }

        public Builder maxAge(long maxAge)
        {
            if (maxAge >= 0)
                _attributes = lazyAttributePut(_attributes, MAX_AGE_ATTRIBUTE, Long.toString(maxAge));
            else
                _attributes = lazyAttributeRemove(_attributes, MAX_AGE_ATTRIBUTE);
            return this;
        }

        public Builder expires(Instant expires)
        {
            if (expires != null)
                _attributes = lazyAttributePut(_attributes, EXPIRES_ATTRIBUTE, formatExpires(expires));
            else
                _attributes = lazyAttributeRemove(_attributes, EXPIRES_ATTRIBUTE);
            return this;
        }

        public Builder path(String path)
        {
            _attributes = lazyAttributePut(_attributes, PATH_ATTRIBUTE, path);
            return this;
        }

        public Builder secure(boolean secure)
        {
            if (secure)
                _attributes = lazyAttributePut(_attributes, SECURE_ATTRIBUTE, Boolean.TRUE.toString());
            else
                _attributes = lazyAttributeRemove(_attributes, SECURE_ATTRIBUTE);
            return this;
        }

        public Builder sameSite(SameSite sameSite)
        {
            _attributes = lazyAttributePut(_attributes, SAME_SITE_ATTRIBUTE, sameSite.attributeValue);
            return this;
        }

        public Builder partitioned(boolean partitioned)
        {
            if (partitioned)
                _attributes = lazyAttributePut(_attributes, PARTITIONED_ATTRIBUTE, Boolean.TRUE.toString());
            else
                _attributes = lazyAttributeRemove(_attributes, PARTITIONED_ATTRIBUTE);
            return this;
        }

        /**
         * @return an immutable {@link HttpCookie} instance.
         */
        public HttpCookie build()
        {
            return new Immutable(_name, _value, _version, lazyAttributes(_attributes));
        }
    }

    /**
     * Creates a new {@code HttpCookie} from the given name and value.
     *
     * @param name the name of the cookie
     * @param value the value of the cookie
     */
    static HttpCookie from(String name, String value)
    {
        return from(name, value, 0, null);
    }

    /**
     * Creates a new {@code HttpCookie} from the given name, value and attributes.
     *
     * @param name the name of the cookie
     * @param value the value of the cookie
     * @param attributes the map of attributes to use with this cookie (this map is used for field values
     * such as {@link #getDomain()}, {@link #getPath()}, {@link #getMaxAge()}, {@link #isHttpOnly()},
     * {@link #isSecure()}, {@link #isPartitioned()}, {@link #getComment()}, plus any newly defined
     * attributes unknown to this code base.
     */
    static HttpCookie from(String name, String value, Map<String, String> attributes)
    {
        return from(name, value, 0, attributes);
    }

    /**
     * Creates a new {@code HttpCookie} from the given name, value, version and attributes.
     *
     * @param name the name of the cookie
     * @param value the value of the cookie
     * @param version the version of the cookie (only used in RFC2965 mode)
     * @param attributes the map of attributes to use with this cookie (this map is used for field values
     * such as {@link #getDomain()}, {@link #getPath()}, {@link #getMaxAge()}, {@link #isHttpOnly()},
     * {@link #isSecure()}, {@link #isPartitioned()}, {@link #getComment()}, plus any newly defined
     * attributes unknown to this code base.
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
        {
            attributes.put(additionalAttributes[i], additionalAttributes[i + 1]);
        }
        return from(cookie.getName(), cookie.getValue(), cookie.getVersion(), attributes);
    }

    /**
     * Creates a new {@code HttpCookie} copied from the given {@link java.net.HttpCookie}.
     *
     * @param httpCookie the {@link java.net.HttpCookie} instance to copy
     * @return a new {@code HttpCookie} copied from the {@link java.net.HttpCookie}
     * @see #asJavaNetHttpCookie(HttpCookie)
     */
    static HttpCookie from(java.net.HttpCookie httpCookie)
    {
        return new JavaNetHttpCookie(httpCookie);
    }

    /**
     * Creates a {@link Builder} to build a {@code HttpCookie}.
     *
     * @param name the cookie name
     * @param value the cookie value
     * @return a new {@link Builder} initialized with the given values
     */
    static Builder build(String name, String value)
    {
        return build(name, value, 0);
    }

    /**
     * Creates a {@link Builder} to build a {@code HttpCookie}.
     *
     * @param name the cookie name
     * @param value the cookie value
     * @param version the cookie version
     * @return a new {@link Builder} initialized with the given values
     */
    static Builder build(String name, String value, int version)
    {
        return new Builder(name, value, version);
    }

    /**
     * Creates a {@link Builder} to build a {@code HttpCookie}.
     *
     * @param httpCookie the cookie to copy
     * @return a new {@link Builder} initialized with the given cookie
     */
    static Builder build(HttpCookie httpCookie)
    {
        Builder builder = new Builder(httpCookie.getName(), httpCookie.getValue(), httpCookie.getVersion());
        for (Map.Entry<String, String> entry : httpCookie.getAttributes().entrySet())
        {
            builder = builder.attribute(entry.getKey(), entry.getValue());
        }
        return builder;
    }

    /**
     * Creates a {@link Builder} to build a {@code HttpCookie}.
     *
     * @param httpCookie the {@link java.net.HttpCookie} to copy
     * @return a new {@link Builder} initialized with the given cookie
     */
    static Builder build(java.net.HttpCookie httpCookie)
    {
        return new Builder(httpCookie.getName(), httpCookie.getValue(), httpCookie.getVersion())
            .comment(httpCookie.getComment())
            .domain(httpCookie.getDomain())
            .httpOnly(httpCookie.isHttpOnly())
            .maxAge(httpCookie.getMaxAge())
            .path(httpCookie.getPath())
            .secure(httpCookie.getSecure());
    }

    /**
     * Converts a {@code HttpCookie} to a {@link java.net.HttpCookie}.
     *
     * @param httpCookie the cookie to convert
     * @return a new {@link java.net.HttpCookie}
     * @see #from(java.net.HttpCookie)
     */
    static java.net.HttpCookie asJavaNetHttpCookie(HttpCookie httpCookie)
    {
        if (httpCookie.getSameSite() != null)
            throw new IllegalArgumentException("SameSite attribute not supported by " + java.net.HttpCookie.class.getName());
        if (httpCookie.isPartitioned())
            throw new IllegalArgumentException("Partitioned attribute not supported by " + java.net.HttpCookie.class.getName());
        java.net.HttpCookie cookie = new java.net.HttpCookie(httpCookie.getName(), httpCookie.getValue());
        cookie.setVersion(httpCookie.getVersion());
        cookie.setComment(httpCookie.getComment());
        cookie.setDomain(httpCookie.getDomain());
        cookie.setHttpOnly(httpCookie.isHttpOnly());
        cookie.setMaxAge(httpCookie.getMaxAge());
        cookie.setPath(httpCookie.getPath());
        cookie.setSecure(httpCookie.isSecure());
        return cookie;
    }

    /**
     * <p>Implementation of {@link Object#hashCode()} compatible with RFC 6265.</p>
     *
     * @param httpCookie the cookie to be hashed
     * @return the hash code of the cookie
     * @see #equals(HttpCookie, Object)
     */
    static int hashCode(HttpCookie httpCookie)
    {
        String domain = httpCookie.getDomain();
        if (domain != null)
            domain = domain.toLowerCase(Locale.ENGLISH);
        return Objects.hash(httpCookie.getName(), domain, httpCookie.getPath());
    }

    /**
     * <p>Implementation of {@link Object#equals(Object)} compatible with RFC 6265.</p>
     * <p>Two cookies are equal if they have the same name (case-sensitive), the same
     * domain (case-insensitive) and the same path (case-sensitive).</p>
     *
     * @param cookie1 the first cookie to equal
     * @param obj the second cookie to equal
     * @return whether the cookies are equal
     * @see #hashCode(HttpCookie)
     */
    static boolean equals(HttpCookie cookie1, Object obj)
    {
        if (cookie1 == obj)
            return true;
        if (cookie1 == null || obj == null)
            return false;
        if (!(obj instanceof HttpCookie cookie2))
            return false;
        // RFC 2965 section. 3.3.3 and RFC 6265 section 4.1.2.
        // Names are case-sensitive.
        if (!Objects.equals(cookie1.getName(), cookie2.getName()))
            return false;
        // Domains are case-insensitive.
        if (!equalsIgnoreCase(cookie1.getDomain(), cookie2.getDomain()))
            return false;
        // Paths are case-sensitive.
        return Objects.equals(cookie1.getPath(), cookie2.getPath());
    }

    private static boolean equalsIgnoreCase(String obj1, String obj2)
    {
        if (obj1 == obj2)
            return true;
        if (obj1 == null || obj2 == null)
            return false;
        return obj1.equalsIgnoreCase(obj2);
    }

    private static String asString(HttpCookie httpCookie)
    {
        StringBuilder builder = new StringBuilder();
        builder.append(httpCookie.getName()).append("=").append(httpCookie.getValue());
        Map<String, String> attributes = httpCookie.getAttributes();
        if (!attributes.isEmpty())
        {
            for (Map.Entry<String, String> entry : attributes.entrySet())
            {
                builder.append("; ");
                builder.append(entry.getKey()).append("=").append(entry.getValue());
            }
        }
        return builder.toString();
    }

    /**
     * <p>Formats this cookie into a string suitable to be used
     * for logging.</p>
     *
     * @param httpCookie the cookie to format
     * @return a logging string representation of the cookie
     */
    static String toString(HttpCookie httpCookie)
    {
        return "%s@%x[%s]".formatted(httpCookie.getClass().getSimpleName(), httpCookie.hashCode(), asString(httpCookie));
    }

    /**
     * <p>Formats the {@link Instant} associated with the
     * {@code Expires} attribute into a RFC 1123 string.</p>
     *
     * @param expires the expiration instant
     * @return the instant formatted as an RFC 1123 string
     * @see #parseExpires(String)
     */
    static String formatExpires(Instant expires)
    {
        return HttpDateTime.format(expires);
    }

    /**
     * <p>Parses the {@code Expires} Date/Time attribute value
     * into an {@link Instant}.</p>
     *
     * @param expires a date/time in one of the RFC6265 supported formats
     * @return an {@link Instant} parsed from the given string
     */
    static Instant parseExpires(String expires)
    {
        return HttpDateTime.parse(expires).toInstant();
    }

    private static Map<String, String> lazyAttributePut(Map<String, String> attributes, String key, String value)
    {
        if (value == null)
            return lazyAttributeRemove(attributes, key);
        if (attributes == null)
            attributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        attributes.put(key, value);
        return attributes;
    }

    private static Map<String, String> lazyAttributeRemove(Map<String, String> attributes, String key)
    {
        if (attributes == null)
            return null;
        attributes.remove(key);
        return attributes;
    }

    private static Map<String, String> lazyAttributes(Map<String, String> attributes)
    {
        return attributes == null || attributes.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(attributes);
    }
}
