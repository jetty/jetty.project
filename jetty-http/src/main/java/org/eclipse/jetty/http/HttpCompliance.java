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

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableSet;
import static java.util.EnumSet.allOf;
import static java.util.EnumSet.complementOf;
import static java.util.EnumSet.noneOf;
import static java.util.EnumSet.of;

/**
 * HTTP compliance modes for Jetty HTTP parsing and handling.
 * A Compliance mode consists of a set of {@link Violation}s which are applied
 * when the mode is enabled.
 */
public final class HttpCompliance implements ComplianceViolation.Mode
{
    protected static final Logger LOG = LoggerFactory.getLogger(HttpCompliance.class);

    // These are compliance violations, which may optionally be allowed by the compliance mode, which mean that
    // the relevant section of the RFC is not strictly adhered to.
    public enum Violation implements ComplianceViolation
    {
        /**
         * The HTTP RFC(s) require that field names are case-insensitive, so for example the fields "{@code Content-Type: text/xml}"
         * and "{@code content-type: text/xml}" are considered equivalent.  Jetty has been optimized to take advantage of this by
         * looking up field names in a case insensitive cache and will by default provide the standard capitalisation of a field
         * name rather than create a new string with the actual capitalisation received.   However, some applications have been
         * written to expect a specific capitalisation of field, so deployments of such applications must include this violation
         * in their {@link HttpCompliance} mode to prevent Jetty altering the case of the fields received. Jetty itself will still
         * match and handle fields names insensitively and this violation only affects how the names are reported to the application.
         * There is a small performance and garbage impact of using this mode.
         */
        CASE_SENSITIVE_FIELD_NAME("https://tools.ietf.org/html/rfc7230#section-3.2", "Field name is case-insensitive"),

        /**
         * The HTTP RFC(s) require that method names are case sensitive, so that "{@code Get}" and "{@code GET}" are considered
         * different methods.   Jetty releases prior to 9.4 used a case insensitive cache to match method names, thus this requirement
         * was violated.  Deployments which wish to retain this legacy violation can include this violation in the
         * {@link HttpCompliance} mode.
         */
        CASE_INSENSITIVE_METHOD("https://tools.ietf.org/html/rfc7230#section-3.1.1", "Method is case-sensitive"),

        /**
         * Since RFC 7230, the expectation that HTTP/0.9 is supported has been removed from the specification.  If a deployment
         * wished to accept HTTP/0.9 requests, then it can include this violation in it's {@link HttpCompliance} mode.
         */
        HTTP_0_9("https://tools.ietf.org/html/rfc7230#appendix-A.2", "HTTP/0.9 not supported"),

        /**
         * Since <a href="https://tools.ietf.org/html/rfc7230#section-3.2.4">RFC 7230</a>, the HTTP protocol no longer supports
         * line folding, which allows a field value to be provided over several lines.  Deployments that wish to receive folder
         * field values may include this violation in their {@link HttpCompliance} mode.
         */
        MULTILINE_FIELD_VALUE("https://tools.ietf.org/html/rfc7230#section-3.2.4", "Line Folding not supported"),

        /**
         * Since <a href="https://tools.ietf.org/html/rfc7230#section-3.3.2">RFC 7230</a>, the HTTP protocol has required that
         * a request is invalid if it contains multiple {@code Content-Length} fields or values.  The request may be treated
         * as invalid even if the multiple values are the same. A deployment may include this violation to allow multiple
         * {@code Content-Length} values to be received, but only if they are identical.
         */
        MULTIPLE_CONTENT_LENGTHS("https://tools.ietf.org/html/rfc7230#section-3.3.2", "Multiple Content-Lengths"),

        /**
         * Since <a href="https://tools.ietf.org/html/rfc7230#section-3.3.1">RFC 7230</a>, the HTTP protocol has required that
         * a request is invalid if it contains both a {@code Transfer-Encoding} field and  {@code Content-Length} field.
         * A deployment may include this violation to allow both fields to be in a received request.
         */
        TRANSFER_ENCODING_WITH_CONTENT_LENGTH("https://tools.ietf.org/html/rfc7230#section-3.3.1", "Transfer-Encoding and Content-Length"),

        /**
         * Since <a href="https://tools.ietf.org/html/rfc7230#section-3.2.4">RFC 7230</a>, the HTTP protocol has required that
         * a request header field has no white space after the field name and before the ':'.
         * A deployment may include this violation to allow such fields to be in a received request.
         */
        WHITESPACE_AFTER_FIELD_NAME("https://tools.ietf.org/html/rfc7230#section-3.2.4", "Whitespace not allowed after field name"),

        /**
         * Prior to <a href="https://tools.ietf.org/html/rfc7230#section-3.2">RFC 7230</a>, the HTTP protocol allowed a header
         * line of a single token with neither a colon nor value following, to be interpreted as a field name with no value.
         * A deployment may include this violation to allow such fields to be in a received request.
         */
        NO_COLON_AFTER_FIELD_NAME("https://tools.ietf.org/html/rfc7230#section-3.2", "Fields must have a Colon");

        private final String url;
        private final String description;

        Violation(String url, String description)
        {
            this.url = url;
            this.description = description;
        }

        @Override
        public String getName()
        {
            return name();
        }

        @Override
        public String getURL()
        {
            return url;
        }

        @Override
        public String getDescription()
        {
            return description;
        }
    }

    /**
     * The request attribute which may be set to record any allowed HTTP violations.
     */
    public static final String VIOLATIONS_ATTR = "org.eclipse.jetty.http.compliance.violations";

    /**
     * The HttpCompliance mode that supports <a href="https://tools.ietf.org/html/rfc7230">RFC 7230</a>
     * with no known violations.
     */
    public static final HttpCompliance RFC7230 = new HttpCompliance("RFC7230", noneOf(Violation.class));

    /**
     * The HttpCompliance mode that supports <a href="https://tools.ietf.org/html/rfc2616">RFC 7230</a>
     * with only the violations that differ from {@link #RFC7230}.
     */
    public static final HttpCompliance RFC2616 = new HttpCompliance("RFC2616", of(Violation.HTTP_0_9, Violation.MULTILINE_FIELD_VALUE));

    /**
     * A legacy HttpCompliance mode that allows all violations except case-insensitive methods.
     */
    public static final HttpCompliance LEGACY = new HttpCompliance("LEGACY", complementOf(of(Violation.CASE_INSENSITIVE_METHOD)));

    /**
     * A legacy HttpCompliance mode that supports {@link #RFC2616}, but that also allows: case-insensitive methods;
     * colons after field names; {@code Transfer-Encoding} with {@code Content-Length} fields; and multiple {@code Content-Length} values.
     */
    public static final HttpCompliance RFC2616_LEGACY = RFC2616.with("RFC2616_LEGACY",
        Violation.CASE_INSENSITIVE_METHOD,
        Violation.NO_COLON_AFTER_FIELD_NAME,
        Violation.TRANSFER_ENCODING_WITH_CONTENT_LENGTH,
        Violation.MULTIPLE_CONTENT_LENGTHS);

    /**
     * A legacy HttpCompliance mode that supports {@link #RFC7230}, but with case-insensitive methods allowed.
     */
    public static final HttpCompliance RFC7230_LEGACY = RFC7230.with("RFC7230_LEGACY", Violation.CASE_INSENSITIVE_METHOD);

    private static final List<HttpCompliance> KNOWN_MODES = Arrays.asList(RFC7230, RFC2616, LEGACY, RFC2616_LEGACY, RFC7230_LEGACY);
    private static final AtomicInteger __custom = new AtomicInteger();

    /**
     * Get a known compliance mode by name.
     * @param name The name of a known {@link HttpCompliance} mode.
     * @return The mode matching the name.
     */
    public static HttpCompliance valueOf(String name)
    {
        for (HttpCompliance compliance : KNOWN_MODES)
        {
            if (compliance.getName().equals(name))
                return compliance;
        }
        LOG.warn("Unknown HttpCompliance mode {}", name);
        return null;
    }

    /**
     * Create compliance mode from a String description.
     * <p>
     * Format:
     * </p>
     * <dl>
     * <dt>0</dt><dd>No {@link Violation}s</dd>
     * <dt>*</dt><dd>All {@link Violation}s</dd>
     * <dt>RFC2616</dt><dd>The set of {@link Violation}s application to https://tools.ietf.org/html/rfc2616,
     * but not https://tools.ietf.org/html/rfc7230</dd>
     * <dt>RFC7230</dt><dd>The set of {@link Violation}s application to https://tools.ietf.org/html/rfc7230</dd>
     * <dt>name</dt><dd>Any of the known modes defined in {@link HttpCompliance#KNOWN_MODES}</dd>
     * </dl>
     * <p>
     * The remainder of the list can contain then names of {@link Violation}s to include them in the mode, or prefixed
     * with a '-' to exclude them from the mode.
     * </p>
     *
     * @param spec A string describing the compliance
     * @return the HttpCompliance instance derived from the string description
     */
    public static HttpCompliance from(String spec)
    {
        Set<Violation> sections;
        String[] elements = spec.split("\\s*,\\s*");
        switch (elements[0])
        {
            case "0":
                sections = noneOf(Violation.class);
                break;

            case "*":
                sections = allOf(Violation.class);
                break;

            default:
            {
                HttpCompliance mode = HttpCompliance.valueOf(elements[0]);
                sections = (mode == null) ? noneOf(HttpCompliance.Violation.class) : copyOf(mode.getAllowed());
            }
        }

        for (int i = 1; i < elements.length; i++)
        {
            String element = elements[i];
            boolean exclude = element.startsWith("-");
            if (exclude)
                element = element.substring(1);
            Violation section = Violation.valueOf(element);
            if (exclude)
                sections.remove(section);
            else
                sections.add(section);
        }

        return new HttpCompliance("CUSTOM" + __custom.getAndIncrement(), sections);
    }

    private final String _name;
    private final Set<Violation> _violations;

    private HttpCompliance(String name, Set<Violation> violations)
    {
        Objects.requireNonNull(violations);
        _name = name;
        _violations = unmodifiableSet(violations.isEmpty() ? noneOf(Violation.class) : copyOf(violations));
    }

    @Override
    public boolean allows(ComplianceViolation violation)
    {
        return violation instanceof Violation && _violations.contains(violation);
    }

    @Override
    public String getName()
    {
        return _name;
    }

    @Override
    public Set<Violation> getAllowed()
    {
        return _violations;
    }

    @Override
    public Set<Violation> getKnown()
    {
        return EnumSet.allOf(Violation.class);
    }

    /**
     * Create a new HttpCompliance mode that includes the passed {@link Violation}s.
     *
     * @param name The name of the new mode
     * @param violations The violations to include
     * @return A new {@link HttpCompliance} mode.
     */
    public HttpCompliance with(String name, Violation... violations)
    {
        Set<Violation> union = _violations.isEmpty() ? EnumSet.noneOf(Violation.class) : copyOf(_violations);
        union.addAll(copyOf(violations));
        return new HttpCompliance(name, union);
    }

    /**
     * Create a new HttpCompliance mode that excludes the passed {@link Violation}s.
     *
     * @param name The name of the new mode
     * @param violations The violations to exclude
     * @return A new {@link HttpCompliance} mode.
     */
    public HttpCompliance without(String name, Violation... violations)
    {
        Set<Violation> remainder = _violations.isEmpty() ? EnumSet.noneOf(Violation.class) : copyOf(_violations);
        remainder.removeAll(copyOf(violations));
        return new HttpCompliance(name, remainder);
    }

    @Override
    public String toString()
    {
        return String.format("%s%s", _name, _violations);
    }

    private static Set<Violation> copyOf(Violation[] violations)
    {
        if (violations == null || violations.length == 0)
            return EnumSet.noneOf(Violation.class);
        return EnumSet.copyOf(asList(violations));
    }

    private static Set<Violation> copyOf(Set<Violation> violations)
    {
        if (violations == null || violations.isEmpty())
            return EnumSet.noneOf(Violation.class);
        return EnumSet.copyOf(violations);
    }
}
