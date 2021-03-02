//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import static java.util.EnumSet.noneOf;

/**
 * URI compliance modes for Jetty request handling.
 * A Compliance mode consists of a set of {@link Violation}s which are applied
 * when the mode is enabled.
 */
public final class UriCompliance implements ComplianceViolation.Mode
{
    protected static final Logger LOG = LoggerFactory.getLogger(UriCompliance.class);

    // These are compliance violations, which may optionally be allowed by the compliance mode, which mean that
    // the relevant section of the RFC is not strictly adhered to.
    public enum Violation implements ComplianceViolation
    {
        AMBIGUOUS_PATH_SEGMENT("https://tools.ietf.org/html/rfc3986#section-3.3", "Ambiguous URI path segment"),
        AMBIGUOUS_PATH_SEPARATOR("https://tools.ietf.org/html/rfc3986#section-3.3", "Ambiguous URI path separator");

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

    public static final UriCompliance SAFE = new UriCompliance("SAFE", noneOf(Violation.class));
    public static final UriCompliance STRICT = new UriCompliance("STRICT", allOf(Violation.class));
    private static final List<UriCompliance> KNOWN_MODES = Arrays.asList(SAFE, STRICT);
    private static final AtomicInteger __custom = new AtomicInteger();

    public static UriCompliance valueOf(String name)
    {
        for (UriCompliance compliance : KNOWN_MODES)
        {
            if (compliance.getName().equals(name))
                return compliance;
        }
        LOG.warn("Unknown UriCompliance mode {}", name);
        return null;
    }

    /**
     * Create compliance set from string.
     * <p>
     * Format:
     * </p>
     * <dl>
     * <dt>0</dt><dd>No {@link Violation}s</dd>
     * <dt>*</dt><dd>All {@link Violation}s</dd>
     * <dt>RFC2616</dt><dd>The set of {@link Violation}s application to https://tools.ietf.org/html/rfc2616,
     * but not https://tools.ietf.org/html/rfc7230</dd>
     * <dt>RFC7230</dt><dd>The set of {@link Violation}s application to https://tools.ietf.org/html/rfc7230</dd>
     * <dt>name</dt><dd>Any of the known modes defined in {@link UriCompliance#KNOWN_MODES}</dd>
     * </dl>
     * <p>
     * The remainder of the list can contain then names of {@link Violation}s to include them in the mode, or prefixed
     * with a '-' to exclude thm from the mode.
     * </p>
     *
     * @param spec A string in the format of a comma separated list starting with one of the following strings:
     * @return the compliance from the string spec
     */
    public static UriCompliance from(String spec)
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
                UriCompliance mode = UriCompliance.valueOf(elements[0]);
                sections = (mode == null) ? noneOf(Violation.class) : copyOf(mode.getAllowed());
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

        UriCompliance compliance = new UriCompliance("CUSTOM" + __custom.getAndIncrement(), sections);
        if (LOG.isDebugEnabled())
            LOG.debug("UriCompliance from {}->{}", spec, compliance);
        return compliance;
    }

    private final String _name;
    private final Set<Violation> _violations;

    private UriCompliance(String name, Set<Violation> violations)
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

    /**
     * Get the set of {@link Violation}s allowed by this compliance mode.
     *
     * @return The immutable set of {@link Violation}s allowed by this compliance mode.
     */
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
     * Create a new UriCompliance mode that includes the passed {@link Violation}s.
     *
     * @param name The name of the new mode
     * @param violations The violations to include
     * @return A new {@link UriCompliance} mode.
     */
    public UriCompliance with(String name, Violation... violations)
    {
        Set<Violation> union = _violations.isEmpty() ? EnumSet.noneOf(Violation.class) : copyOf(_violations);
        union.addAll(copyOf(violations));
        return new UriCompliance(name, union);
    }

    /**
     * Create a new UriCompliance mode that excludes the passed {@link Violation}s.
     *
     * @param name The name of the new mode
     * @param violations The violations to exclude
     * @return A new {@link UriCompliance} mode.
     */
    public UriCompliance without(String name, Violation... violations)
    {
        Set<Violation> remainder = _violations.isEmpty() ? EnumSet.noneOf(Violation.class) : copyOf(_violations);
        remainder.removeAll(copyOf(violations));
        return new UriCompliance(name, remainder);
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
