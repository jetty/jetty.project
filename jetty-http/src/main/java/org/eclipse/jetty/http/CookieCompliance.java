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

import static java.util.Collections.unmodifiableSet;
import static java.util.EnumSet.allOf;
import static java.util.EnumSet.copyOf;
import static java.util.EnumSet.noneOf;

/**
 * The compliance mode for Cookie handling.
 */
public class CookieCompliance implements ComplianceViolation.Mode
{
    private static final Logger LOG = LoggerFactory.getLogger(CookieCompliance.class);

    public enum Violation implements ComplianceViolation
    {
        /**
         * Allow a comma as part of a cookie value
         */
        COMMA_NOT_VALID_OCTET("https://tools.ietf.org/html/rfc6265#section-4.1.1", "Comma not valid as cookie-octet or separator"),

        /**
         * Allow cookies to have $ prefixed reserved parameters
         */
        RESERVED_NAMES_NOT_DOLLAR_PREFIXED("https://tools.ietf.org/html/rfc6265#section-4.1.1", "Reserved names no longer use '$' prefix");

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
     * A CookieCompliance mode that enforces <a href="https://tools.ietf.org/html/rfc6265">RFC 6265</a> compliance.
     */
    public static final CookieCompliance RFC6265 = new CookieCompliance("RFC6265", noneOf(Violation.class));

    /**
     * A CookieCompliance mode that allows <a href="https://tools.ietf.org/html/rfc2965">RFC 2965</a> compliance.
     */
    public static final CookieCompliance RFC2965 = new CookieCompliance("RFC2965", allOf(Violation.class));

    private static final List<CookieCompliance> KNOWN_MODES = Arrays.asList(RFC6265, RFC2965);
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
     * <dt>{@code 0,RESERVED_NAMES_NOT_DOLLAR_PREFIXED}</dt><dd>Only allow {@link CookieCompliance.Violation#RESERVED_NAMES_NOT_DOLLAR_PREFIXED}</dd>
     * <dt>{@code *,-RESERVED_NAMES_NOT_DOLLAR_PREFIXED}</dt><dd>Allow all violations, except {@link CookieCompliance.Violation#RESERVED_NAMES_NOT_DOLLAR_PREFIXED}</dd>
     * <dt>{@code RFC2965,RESERVED_NAMES_NOT_DOLLAR_PREFIXED}</dt><dd>Same as RFC2965, but allows {@link CookieCompliance.Violation#RESERVED_NAMES_NOT_DOLLAR_PREFIXED}</dd>
     * </dl>
     *
     * @param spec A string describing the compliance
     * @return the compliance from the string spec
     */
    public static CookieCompliance from(String spec)
    {
        Set<Violation> violations;
        String[] elements = spec.split("\\s*,\\s*");
        switch (elements[0])
        {
            case "0":
                violations = noneOf(Violation.class);
                break;

            case "*":
                violations = allOf(Violation.class);
                break;

            default:
            {
                CookieCompliance mode = valueOf(elements[0]);
                violations = (mode == null) ? noneOf(Violation.class) : copyOf(mode.getAllowed());
                break;
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
                violations.remove(section);
            else
                violations.add(section);
        }

        CookieCompliance compliance = new CookieCompliance("CUSTOM" + __custom.getAndIncrement(), violations);
        if (LOG.isDebugEnabled())
            LOG.debug("CookieCompliance from {}->{}", spec, compliance);
        return compliance;
    }

    private final String _name;
    private final Set<Violation> _violations;

    private CookieCompliance(String name, Set<Violation> violations)
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
}
