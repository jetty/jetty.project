//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
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

import static java.util.Collections.unmodifiableSet;
import static java.util.EnumSet.allOf;
import static java.util.EnumSet.copyOf;
import static java.util.EnumSet.noneOf;

/**
 * The compliance for Cookie handling.
 */
public class CookieCompliance implements ComplianceViolation.Mode
{
    public enum Violation implements ComplianceViolation
    {
        COMMA_NOT_VALID_OCTET("https://tools.ietf.org/html/rfc6265#section-4.1.1", "Comma not valid as cookie-octet or separator"),
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

    public static final CookieCompliance RFC6265 = new CookieCompliance("RFC6265", noneOf(Violation.class));
    public static final CookieCompliance RFC2965 = new CookieCompliance("RFC2965", allOf(Violation.class));

    private static final List<CookieCompliance> KNOWN_MODES = Arrays.asList(RFC6265, RFC2965);

    public static CookieCompliance valueOf(String name)
    {
        for (CookieCompliance compliance : KNOWN_MODES)
        {
            if (compliance.getName().equals(name))
                return compliance;
        }
        return null;
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
