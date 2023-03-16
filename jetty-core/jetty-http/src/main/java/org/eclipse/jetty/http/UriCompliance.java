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
 * URI compliance modes for Jetty request handling.
 * A Compliance mode consists of a set of {@link Violation}s which are allowed
 * when the mode is enabled.
 */
public final class UriCompliance implements ComplianceViolation.Mode
{
    private static final Logger LOG = LoggerFactory.getLogger(UriCompliance.class);

    /**
     * These are URI compliance "violations", which may be allowed by the compliance mode. These are actual
     * violations of the RFC, as they represent additional requirements in excess of the strict compliance of
     * <a href="https://datatracker.ietf.org/doc/html/rfc3986">RFC 3986</a>.
     * A compliance mode that contains one or more of these Violations, allows request to violate the corresponding
     * additional requirement.
     */
    public enum Violation implements ComplianceViolation
    {
        /**
         * Allow ambiguous path segments e.g. <code>/foo/%2e%2e/bar</code>
         */
        AMBIGUOUS_PATH_SEGMENT("https://tools.ietf.org/html/rfc3986#section-3.3", "Ambiguous URI path segment"),
        /**
         * Allow ambiguous empty segments e.g. <code>//</code>
         */
        AMBIGUOUS_EMPTY_SEGMENT("https://tools.ietf.org/html/rfc3986#section-3.3", "Ambiguous URI empty segment"),
        /**
         * Allow ambiguous path separator within a URI segment e.g. <code>/foo/b%2fr</code>
         */
        AMBIGUOUS_PATH_SEPARATOR("https://tools.ietf.org/html/rfc3986#section-3.3", "Ambiguous URI path separator"),
        /**
         * Allow ambiguous path parameters within a URI segment e.g. <code>/foo/..;/bar</code> or <code>/foo/%2e%2e;param/bar</code>
         */
        AMBIGUOUS_PATH_PARAMETER("https://tools.ietf.org/html/rfc3986#section-3.3", "Ambiguous URI path parameter"),
        /**
         * Allow ambiguous path encoding within a URI segment e.g. <code>/%2557EB-INF</code>
         */
        AMBIGUOUS_PATH_ENCODING("https://tools.ietf.org/html/rfc3986#section-3.3", "Ambiguous URI path encoding"),
        /**
         * Allow UTF-16 encoding eg <code>/foo%u2192bar</code>.
         */
        UTF16_ENCODINGS("https://www.w3.org/International/iri-edit/draft-duerst-iri.html#anchor29", "UTF16 encoding"),
        /**
         * Allow Bad UTF-8 encodings to be substituted by the replacement character.</code>.
         */
        BAD_UTF8_ENCODING("https://datatracker.ietf.org/doc/html/rfc5987#section-3.2.1", "Bad UTF8 encoding");

        private final String _url;
        private final String _description;

        Violation(String url, String description)
        {
            _url = url;
            _description = description;
        }

        @Override
        public String getName()
        {
            return name();
        }

        @Override
        public String getURL()
        {
            return _url;
        }

        @Override
        public String getDescription()
        {
            return _description;
        }
    }

    public static final EnumSet<Violation> AMBIGUOUS_VIOLATIONS = EnumSet.of(
        Violation.AMBIGUOUS_EMPTY_SEGMENT,
        Violation.AMBIGUOUS_PATH_ENCODING,
        Violation.AMBIGUOUS_PATH_PARAMETER,
        Violation.AMBIGUOUS_PATH_SEGMENT,
        Violation.AMBIGUOUS_PATH_SEPARATOR);

    /**
     * Compliance mode that exactly follows <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>,
     * excluding all URI Violations.
     */
    public static final UriCompliance RFC3986 = new UriCompliance("RFC3986", noneOf(Violation.class));

    /**
     * Compliance mode that allows all unambiguous violations.
     */
    public static final UriCompliance UNAMBIGUOUS = new UriCompliance("UNAMBIGUOUS", complementOf(AMBIGUOUS_VIOLATIONS));

    /**
     * The default compliance mode allows no violations from <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>
     * and is equivalent to {@link #RFC3986} compliance.
     */
    public static final UriCompliance DEFAULT = RFC3986;

    /**
     * LEGACY compliance mode that models Jetty-9.4 behavior by allowing {@link Violation#AMBIGUOUS_PATH_SEGMENT},
     * {@link Violation#AMBIGUOUS_EMPTY_SEGMENT}, {@link Violation#AMBIGUOUS_PATH_SEPARATOR}, {@link Violation#AMBIGUOUS_PATH_ENCODING}
     * and {@link Violation#UTF16_ENCODINGS}.
     */
    public static final UriCompliance LEGACY = new UriCompliance("LEGACY",
        of(Violation.AMBIGUOUS_PATH_SEGMENT,
            Violation.AMBIGUOUS_PATH_SEPARATOR,
            Violation.AMBIGUOUS_PATH_ENCODING,
            Violation.AMBIGUOUS_EMPTY_SEGMENT,
            Violation.UTF16_ENCODINGS));

    /**
     * Compliance mode that allows all URI Violations, including allowing ambiguous paths in non-canonical form.
     */
    public static final UriCompliance UNSAFE = new UriCompliance("UNSAFE", allOf(Violation.class));

    private static final AtomicInteger __custom = new AtomicInteger();
    private static final List<UriCompliance> KNOWN_MODES = List.of(DEFAULT, LEGACY, RFC3986, UNAMBIGUOUS, UNSAFE);

    public static boolean isAmbiguous(EnumSet<Violation> violations)
    {
        if (violations.isEmpty())
            return false;
        for (Violation v : AMBIGUOUS_VIOLATIONS)
            if (violations.contains(v))
                return true;
        return false;
    }

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
     * Create compliance set from a set of allowed Violations.
     *
     * @param violations A string of violations to allow:
     * @return the compliance from the string spec
     */
    public static UriCompliance from(Set<Violation> violations)
    {
        return new UriCompliance("CUSTOM" + __custom.getAndIncrement(), violations);
    }

    /**
     * Create compliance set from string.
     * <p>
     * Format: &lt;BASE&gt;[,[-]&lt;violation&gt;]...
     * </p>
     * <p>BASE is one of:</p>
     * <dl>
     * <dt>0</dt><dd>No {@link Violation}s</dd>
     * <dt>*</dt><dd>All {@link Violation}s</dd>
     * <dt>&lt;name&gt;</dt><dd>The name of a static instance of UriCompliance (e.g. {@link UriCompliance#RFC3986}).
     * </dl>
     * <p>
     * The remainder of the list can contain then names of {@link Violation}s to include them in the mode, or prefixed
     * with a '-' to exclude them from the mode.  Examples are:
     * </p>
     * <dl>
     * <dt>{@code 0,AMBIGUOUS_PATH_PARAMETER}</dt><dd>Only allow {@link Violation#AMBIGUOUS_PATH_PARAMETER}</dd>
     * <dt>{@code *,-AMBIGUOUS_PATH_PARAMETER}</dt><dd>Only all except {@link Violation#AMBIGUOUS_PATH_PARAMETER}</dd>
     * <dt>{@code RFC3986,AMBIGUOUS_PATH_PARAMETER}</dt><dd>Same as RFC3986 plus {@link Violation#AMBIGUOUS_PATH_PARAMETER}</dd>
     * </dl>
     *
     * @param spec A string describing the compliance
     * @return the UriCompliance instance derived from the string description
     */
    public static UriCompliance from(String spec)
    {
        UriCompliance compliance = valueOf(spec);
        if (compliance == null)
        {
            String[] elements = spec.split("\\s*,\\s*");

            Set<Violation> violations = switch (elements[0])
            {
                case "0" -> noneOf(Violation.class);
                case "*" -> allOf(Violation.class);
                default ->
                {
                    UriCompliance mode = UriCompliance.valueOf(elements[0]);
                    yield (mode == null) ? noneOf(Violation.class) : copyOf(mode.getAllowed());
                }
            };

            for (int i = 1; i < elements.length; i++)
            {
                String element = elements[i];
                boolean exclude = element.startsWith("-");
                if (exclude)
                    element = element.substring(1);

                // Ignore removed name. TODO: remove in future release.
                if (element.equals("NON_CANONICAL_AMBIGUOUS_PATHS"))
                    continue;

                Violation section = Violation.valueOf(element);
                if (exclude)
                    violations.remove(section);
                else
                    violations.add(section);
            }

            compliance = new UriCompliance("CUSTOM" + __custom.getAndIncrement(), violations);
        }
        if (LOG.isDebugEnabled())
            LOG.debug("UriCompliance from {}->{}", spec, compliance);
        return compliance;
    }

    private final String _name;
    private final Set<Violation> _allowed;

    public UriCompliance(String name, Set<Violation> violations)
    {
        Objects.requireNonNull(violations);
        _name = name;
        _allowed = unmodifiableSet(violations.isEmpty() ? noneOf(Violation.class) : copyOf(violations));
    }

    @Override
    public boolean allows(ComplianceViolation violation)
    {
        return violation instanceof Violation && _allowed.contains(violation);
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
        return _allowed;
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
        Set<Violation> union = _allowed.isEmpty() ? EnumSet.noneOf(Violation.class) : copyOf(_allowed);
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
        Set<Violation> remainder = _allowed.isEmpty() ? EnumSet.noneOf(Violation.class) : copyOf(_allowed);
        remainder.removeAll(copyOf(violations));
        return new UriCompliance(name, remainder);
    }

    @Override
    public String toString()
    {
        return String.format("%s%s", _name, _allowed);
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

    public static String checkUriCompliance(UriCompliance compliance, HttpURI uri)
    {
        for (UriCompliance.Violation violation : UriCompliance.Violation.values())
        {
            if (uri.hasViolation(violation) && (compliance == null || !compliance.allows(violation)))
                return violation.getDescription();
        }
        return null;
    }
}
