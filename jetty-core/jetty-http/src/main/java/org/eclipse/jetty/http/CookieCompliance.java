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

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Collections.unmodifiableSet;
import static java.util.EnumSet.allOf;
import static java.util.EnumSet.complementOf;
import static java.util.EnumSet.copyOf;
import static java.util.EnumSet.noneOf;
import static java.util.EnumSet.of;

/**
 * The compliance mode for Cookie handling.
 */
public class CookieCompliance implements ComplianceViolation.Mode
{
    private static final Logger LOG = LoggerFactory.getLogger(CookieCompliance.class);

    public enum Violation implements ComplianceViolation
    {
        /**
         * A comma was found in a cookie value.
         *
         * @deprecated Use SPECIAL_CHARS_IN_QUOTES
         */
        @Deprecated
        COMMA_NOT_VALID_OCTET("https://tools.ietf.org/html/rfc6265#section-4.2.1", "Comma not valid as cookie-octet or separator"),

        /**
         * A comma was found as separator between cookies.
         */
        COMMA_SEPARATOR("https://www.rfc-editor.org/rfc/rfc2965.html", "Comma cookie separator"),

        /**
         * @deprecated no replacement because was mistakenly considered a violation
         */
        @Deprecated
        RESERVED_NAMES_NOT_DOLLAR_PREFIXED("https://tools.ietf.org/html/rfc6265#section-4.2.1", "Reserved name no longer use '$' prefix"),

        /**
         * Special characters were found in a quoted cookie value.
         */
        SPECIAL_CHARS_IN_QUOTES("https://www.rfc-editor.org/rfc/rfc6265#section-4.2.1", "Special character cannot be quoted"),

        /**
         * A backslash was found in a quoted cookie value.
         */
        ESCAPE_IN_QUOTES("https://www.rfc-editor.org/rfc/rfc2616#section-2.2", "Escaped character in quotes"),

        /**
         * Quotes are not balanced or are embedded in value.
         */
        BAD_QUOTES("https://www.rfc-editor.org/rfc/rfc2616#section-2.2", "Bad quotes"),

        /**
         * An invalid cookie was found, without a more specific violation.
         * When this violation is not allowed, an exception is thrown.
         */
        INVALID_COOKIES("https://tools.ietf.org/html/rfc6265", "Invalid cookie"),

        /**
         * A cookie attribute was found.
         * The attribute value is retained only if {@link #ATTRIBUTE_VALUES} is allowed.
         */
        ATTRIBUTES("https://www.rfc-editor.org/rfc/rfc6265#section-4.2.1", "Cookie attribute"),

        /**
         * A cookie attribute value was found and its value is retained.
         * Allowing {@code ATTRIBUTE_VALUE} implies allowing {@link #ATTRIBUTES}.
         */
        ATTRIBUTE_VALUES("https://www.rfc-editor.org/rfc/rfc6265#section-4.2.1", "Cookie attribute value"),

        /**
         * Whitespace was found around the cookie name and/or around the cookie value.
         */
        OPTIONAL_WHITE_SPACE("https://www.rfc-editor.org/rfc/rfc6265#section-5.2", "White space around name/value"),

        /**
         * Allow spaces within values without quotes.
         */
        SPACE_IN_VALUES("https://www.rfc-editor.org/rfc/rfc6265#section-5.2", "Space in value");

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
     * <p>A CookieCompliance mode that enforces <a href="https://tools.ietf.org/html/rfc6265">RFC 6265</a> compliance,
     * but allows:</p>
     * <ul>
     * <li>{@link Violation#INVALID_COOKIES}</li>
     * <li>{@link Violation#OPTIONAL_WHITE_SPACE}</li>
     * <li>{@link Violation#SPACE_IN_VALUES}</li>
     * </ul>
     */
    public static final CookieCompliance RFC6265 = new CookieCompliance("RFC6265", of(
        Violation.INVALID_COOKIES, Violation.OPTIONAL_WHITE_SPACE, Violation.SPACE_IN_VALUES)
    );

    /**
     * A CookieCompliance mode that enforces <a href="https://tools.ietf.org/html/rfc6265">RFC 6265</a> compliance.
     */
    public static final CookieCompliance RFC6265_STRICT = new CookieCompliance("RFC6265_STRICT", noneOf(Violation.class));

    /**
     * <p>A CookieCompliance mode that enforces <a href="https://tools.ietf.org/html/rfc6265">RFC 6265</a> compliance,
     * but allows:</p>
     * <ul>
     * <li>{@link Violation#ATTRIBUTES}</li>
     * <li>{@link Violation#BAD_QUOTES}</li>
     * <li>{@link Violation#ESCAPE_IN_QUOTES}</li>
     * <li>{@link Violation#INVALID_COOKIES}</li>
     * <li>{@link Violation#OPTIONAL_WHITE_SPACE}</li>
     * <li>{@link Violation#SPECIAL_CHARS_IN_QUOTES}</li>
     * <li>{@link Violation#SPACE_IN_VALUES}</li>
     * </ul>
     */
    public static final CookieCompliance RFC6265_LEGACY = new CookieCompliance("RFC6265_LEGACY", EnumSet.of(
        Violation.ATTRIBUTES, Violation.BAD_QUOTES, Violation.ESCAPE_IN_QUOTES, Violation.INVALID_COOKIES, Violation.OPTIONAL_WHITE_SPACE, Violation.SPECIAL_CHARS_IN_QUOTES, Violation.SPACE_IN_VALUES)
    );

    /**
     * A CookieCompliance mode that allows <a href="https://tools.ietf.org/html/rfc2965">RFC 2965</a> compliance, but with
     * exceptions that match the behavior of legacy Jetty releases.
     */
    public static final CookieCompliance RFC2965_LEGACY = new CookieCompliance("RFC2965_LEGACY", allOf(Violation.class));

    /**
     * A CookieCompliance mode that allows <a href="https://tools.ietf.org/html/rfc2965">RFC 2965</a> compliance
     * but does <b>not</b> allow:
     * <ul>
     * <li>{@link Violation#BAD_QUOTES}</li>
     * <li>{@link Violation#RESERVED_NAMES_NOT_DOLLAR_PREFIXED}</li>
     * </ul>
     */
    public static final CookieCompliance RFC2965 = new CookieCompliance("RFC2965", complementOf(of(
        Violation.BAD_QUOTES, Violation.RESERVED_NAMES_NOT_DOLLAR_PREFIXED)
    ));

    private static final List<CookieCompliance> KNOWN_MODES = Arrays.asList(RFC6265, RFC6265_STRICT, RFC6265_LEGACY, RFC2965, RFC2965_LEGACY);
    private static final AtomicInteger __custom = new AtomicInteger();

    public static CookieCompliance valueOf(String name)
    {
        for (CookieCompliance compliance : KNOWN_MODES)
        {
            if (compliance.getName().equals(name))
                return compliance;
        }
        return null;
    }

    /**
     * Create compliance set from string.
     * <p>
     * Format: &lt;BASE&gt;[,[-]&lt;violation&gt;]...
     * </p>
     * <p>BASE is one of:</p>
     * <dl>
     * <dt>0</dt><dd>No {@link CookieCompliance.Violation}s</dd>
     * <dt>*</dt><dd>All {@link CookieCompliance.Violation}s</dd>
     * <dt>&lt;name&gt;</dt><dd>The name of a static instance of CookieCompliance (e.g. {@link CookieCompliance#RFC6265}).
     * </dl>
     * <p>
     * The remainder of the list can contain then names of {@link CookieCompliance.Violation}s to include them in the mode, or prefixed
     * with a '-' to exclude them from the mode.  Examples are:
     * </p>
     * <dl>
     * <dt>{@code 0,ESCAPE_IN_QUOTES}</dt><dd>Only allow {@link CookieCompliance.Violation#ESCAPE_IN_QUOTES}</dd>
     * <dt>{@code *,-ESCAPE_IN_QUOTES}</dt><dd>Allow all violations, except {@link CookieCompliance.Violation#ESCAPE_IN_QUOTES}</dd>
     * <dt>{@code RFC2965,ESCAPE_IN_QUOTES}</dt><dd>Same as RFC2965, but allows {@link CookieCompliance.Violation#ESCAPE_IN_QUOTES}</dd>
     * </dl>
     *
     * @param spec A string describing the compliance
     * @return the compliance from the string spec
     */
    public static CookieCompliance from(String spec)
    {
        CookieCompliance compliance = valueOf(spec);
        if (compliance == null)
        {
            String[] elements = spec.split("\\s*,\\s*");
            Set<Violation> violations = switch (elements[0])
            {
                case "0" -> noneOf(Violation.class);
                case "*" -> allOf(Violation.class);
                default ->
                {
                    CookieCompliance mode = valueOf(elements[0]);
                    if (mode == null)
                        throw new IllegalArgumentException("Unknown base mode: " + elements[0]);
                    if (mode.getAllowed().isEmpty())
                        yield noneOf(Violation.class);
                    else
                        yield copyOf(mode.getAllowed());
                }
            };

            for (int i = 1; i < elements.length; i++)
            {
                String element = elements[i];
                boolean exclude = element.startsWith("-");
                if (exclude)
                    element = element.substring(1);
                Violation section = Violation.valueOf(element);
                if (exclude)
                    violations.remove(section);
                else
                    violations.add(section);
            }

            compliance = new CookieCompliance("CUSTOM" + __custom.getAndIncrement(), violations);
        }
        if (LOG.isDebugEnabled())
            LOG.debug("CookieCompliance from {}->{}", spec, compliance);
        return compliance;
    }

    private final String _name;
    private final Set<Violation> _violations;

    public CookieCompliance(String name, Set<Violation> violations)
    {
        _name = name;
        _violations = unmodifiableSet(copyOf(Objects.requireNonNull(violations)));
    }

    @Override
    public boolean allows(ComplianceViolation violation)
    {
        return _violations.contains(violation);
    }

    @Override
    public String getName()
    {
        return _name;
    }

    @Override
    public Set<Violation> getKnown()
    {
        return EnumSet.allOf(Violation.class);
    }

    @Override
    public Set<Violation> getAllowed()
    {
        return _violations;
    }

    public boolean compliesWith(CookieCompliance mode)
    {
        return this == mode || getAllowed().containsAll(mode.getAllowed());
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x%s", _name, hashCode(), _violations);
    }
}
