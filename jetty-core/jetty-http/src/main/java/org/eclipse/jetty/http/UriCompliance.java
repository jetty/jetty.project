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

import java.util.Collections;
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
         * Allow ambiguous path segments e.g. <code>/foo/%2e%2e/bar</code>.
         * When allowing this {@code Violation}, the application developer/deployer must ensure that the decoded URI path is not
         * passed to any API that may inadvertently normalize dot or double dot segments.
         * Any resulting '.' characters in the decoded path should be treated as literal characters.
         */
        AMBIGUOUS_PATH_SEGMENT("https://tools.ietf.org/html/rfc3986#section-3.3", "Ambiguous URI path segment"),

        /**
         * Allow ambiguous empty segments e.g. <code>//</code>.
         * When allowing this {@code Violation}, the application developer/deployer must ensure that the application behaves
         * as desired when it receives a URI path containing <code>//</code>. Specifically, any URI pattern matching for
         * security concerns needs to be carefully audited.
         */
        AMBIGUOUS_EMPTY_SEGMENT("https://tools.ietf.org/html/rfc3986#section-3.3", "Ambiguous URI empty segment"),

        /**
         * Allow ambiguous path separator within a URI segment e.g. <code>/foo/b%2fr</code>
         * When allowing this {@code Violation}, the application developer/deployer must be aware that the decoded URI path is
         * ambiguous and that it is not possible to distinguish in the decoded path a real path separator versus an encoded
         * separator character. Any URI matching based on decoded segments may be affected by this ambiguity. It is highly
         * recommended that applications using this violation work only with encoded URI paths.  Some APIs that return
         * decoded paths may throw an exception rather than return such an ambiguous path.
         */
        AMBIGUOUS_PATH_SEPARATOR("https://tools.ietf.org/html/rfc3986#section-3.3", "Ambiguous URI path separator"),

        /**
         * Allow ambiguous path parameters within a URI segment e.g. <code>/foo/..;/bar</code> or <code>/foo/%2e%2e;param/bar</code>.
         * Since a dot or double dot segment with a parameter will not be normalized, then when allowing this {@code Violation},
         * the application developer/deployer must ensure that the decoded URI path is not passed to any API that may
         * inadvertently normalize dot or double dot segments.
         */
        AMBIGUOUS_PATH_PARAMETER("https://tools.ietf.org/html/rfc3986#section-3.3", "Ambiguous URI path parameter"),

        /**
         * Allow ambiguous path encoding within a URI segment e.g. <code>/%2557EB-INF</code>.  When allowing this
         * {@code Violation}, the deployer must ensure that the decoded URI path is not passed to any API that may inadvertently
         * further decode any percent encoded characters. Any resulting `%` character in the decoded path should be treated as
         * a literal character.
         */
        AMBIGUOUS_PATH_ENCODING("https://tools.ietf.org/html/rfc3986#section-3.3", "Ambiguous URI path encoding"),

        /**
         * Allow UTF-16 encoding eg <code>/foo%u2192bar</code>.
         */
        UTF16_ENCODINGS("https://www.w3.org/International/iri-edit/draft-duerst-iri.html#anchor29", "UTF-16 encoding"),

        /**
         * Allow Bad UTF-8 encodings to be substituted by the replacement character in query strings
         */
        BAD_UTF8_ENCODING("https://datatracker.ietf.org/doc/html/rfc5987#section-3.2.1", "Bad UTF-8 encoding"),

        /**
         * Allow Truncated UTF-8 encodings to be substituted by the replacement character in query strings
         */
        TRUNCATED_UTF8_ENCODING("https://datatracker.ietf.org/doc/html/rfc5987#section-3.2.1", "Truncated UTF-8 encoding"),

        /**
         * Allow bad percent encodings such as %xx or %% in query strings
         */
        BAD_PERCENT_ENCODING("https://datatracker.ietf.org/doc/html/rfc5987#section-3.2.1", "Bad percent encoding"),

        /**
         * Allow encoded path characters not allowed by the Servlet spec rules.
         */
        SUSPICIOUS_PATH_CHARACTERS("https://jakarta.ee/specifications/servlet/6.0/jakarta-servlet-spec-6.0.html#uri-path-canonicalization", "Suspicious Path Character"),

        /**
         * Allow path characters not allowed in the path portion of the URI and HTTP specs.
         * <p>This would allow characters that fall outside of the {@code unreserved / pct-encoded / sub-delims / ":" / "@"} ABNF</p>
         */
        ILLEGAL_PATH_CHARACTERS("https://datatracker.ietf.org/doc/html/rfc3986#section-3.3", "Illegal Path Character"),

        /**
         * Allow user info in the authority portion of the URI and HTTP specs.
         */
        USER_INFO("https://datatracker.ietf.org/doc/html/rfc9110#name-deprecation-of-userinfo-in-", "Deprecated User Info");

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

    public static final Set<Violation> NO_VIOLATION = Collections.unmodifiableSet(EnumSet.noneOf(Violation.class));

    /**
     * Set of violations that can trigger a HttpURI.isAmbiguous violation.
     */
    public static final Set<Violation> AMBIGUOUS_VIOLATIONS = Collections.unmodifiableSet(EnumSet.of(
        Violation.AMBIGUOUS_EMPTY_SEGMENT,
        Violation.AMBIGUOUS_PATH_ENCODING,
        Violation.AMBIGUOUS_PATH_PARAMETER,
        Violation.AMBIGUOUS_PATH_SEGMENT,
        Violation.AMBIGUOUS_PATH_SEPARATOR));

    /**
     * List of Violations that apply only to the HttpURI.path section.
     */
    private static final Set<Violation> PATH_VIOLATIONS = Collections.unmodifiableSet(EnumSet.of(
        Violation.AMBIGUOUS_EMPTY_SEGMENT,
        Violation.AMBIGUOUS_PATH_ENCODING,
        Violation.AMBIGUOUS_PATH_PARAMETER,
        Violation.AMBIGUOUS_PATH_SEGMENT,
        Violation.AMBIGUOUS_PATH_SEPARATOR,
        Violation.SUSPICIOUS_PATH_CHARACTERS,
        Violation.ILLEGAL_PATH_CHARACTERS));

    /**
     * Compliance mode that exactly follows <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>,
     * excluding all URI Violations.
     */
    public static final UriCompliance RFC3986 = new UriCompliance("RFC3986", noneOf(Violation.class));

    /**
     * Compliance mode that allows all unambiguous violations.
     */
    public static final UriCompliance UNAMBIGUOUS = new UriCompliance("UNAMBIGUOUS",
        complementOf(EnumSet.copyOf(AMBIGUOUS_VIOLATIONS)));

    /**
     * The default compliance mode allows no violations from <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>
     * and is equivalent to {@link #RFC3986} compliance.
     */
    public static final UriCompliance DEFAULT = new UriCompliance("DEFAULT", RFC3986.getAllowed());

    /**
     * JETTY_11 compliance mode that models Jetty 11 DEFAULT behavior by allowing:
     * <ul>
     *     <li>{@link Violation#AMBIGUOUS_PATH_SEGMENT}</li>
     *     <li>{@link Violation#AMBIGUOUS_PATH_SEPARATOR}</li>
     *     <li>{@link Violation#AMBIGUOUS_PATH_ENCODING}</li>
     *     <li>{@link Violation#SUSPICIOUS_PATH_CHARACTERS}</li>
     *     <li>{@link Violation#TRUNCATED_UTF8_ENCODING}</li>
     *     <li>{@link Violation#UTF16_ENCODINGS}</li>
     *     <li>{@link Violation#USER_INFO}</li>
     * </ul>
     * <p>
     *     Note: this mode allows URL/URIs that the Servlet spec will reject.
     *     <br>
     *     See <a href="https://github.com/jakartaee/servlet/blob/6.0.0-RELEASE/spec/src/main/asciidoc/servlet-spec-body.adoc#352-uri-path-canonicalization">point 10 "Rejecting Suspicious Sequences" in Section 3.5.2. URI Path Canonicalization</a>,
     *     <br>
     *     and <a href="https://jetty.org/docs/jetty/12/programming-guide/server/compliance.html#servleturi">Jetty Documentation: Servlet URI Compliance Modes.</a>
     * </p>
     */
    public static final UriCompliance JETTY_11 = new UriCompliance("JETTY_11",
        of(Violation.AMBIGUOUS_PATH_SEGMENT,
            Violation.AMBIGUOUS_PATH_SEPARATOR,
            Violation.AMBIGUOUS_PATH_ENCODING,
            Violation.SUSPICIOUS_PATH_CHARACTERS,
            Violation.TRUNCATED_UTF8_ENCODING,
            Violation.UTF16_ENCODINGS,
            Violation.USER_INFO));

    /**
     * LEGACY compliance mode that models pre Jetty 12 LEGACY behaviors by allowing:
     * <ul>
     *     <li>{@link Violation#AMBIGUOUS_PATH_SEGMENT}</li>
     *     <li>{@link Violation#AMBIGUOUS_PATH_SEPARATOR}</li>
     *     <li>{@link Violation#AMBIGUOUS_PATH_ENCODING}</li>
     *     <li>{@link Violation#AMBIGUOUS_EMPTY_SEGMENT}</li>
     *     <li>{@link Violation#SUSPICIOUS_PATH_CHARACTERS}</li>
     *     <li>{@link Violation#TRUNCATED_UTF8_ENCODING}</li>
     *     <li>{@link Violation#UTF16_ENCODINGS}</li>
     *     <li>{@link Violation#USER_INFO}</li>
     * </ul>
     * <p>
     *     Note: this mode allows URL/URIs that the Servlet spec will reject.
     *     <br>
     *     See <a href="https://github.com/jakartaee/servlet/blob/6.0.0-RELEASE/spec/src/main/asciidoc/servlet-spec-body.adoc#352-uri-path-canonicalization">point 10 "Rejecting Suspicious Sequences" in Section 3.5.2. URI Path Canonicalization</a>,
     *     <br>
     *     and <a href="https://jetty.org/docs/jetty/12/programming-guide/server/compliance.html#servleturi">Jetty Documentation: Servlet URI Compliance Modes.</a>
     * </p>
     */
    public static final UriCompliance LEGACY = new UriCompliance("LEGACY",
        of(Violation.AMBIGUOUS_PATH_SEGMENT,
            Violation.AMBIGUOUS_PATH_SEPARATOR,
            Violation.AMBIGUOUS_PATH_ENCODING,
            Violation.AMBIGUOUS_EMPTY_SEGMENT,
            Violation.SUSPICIOUS_PATH_CHARACTERS,
            Violation.TRUNCATED_UTF8_ENCODING,
            Violation.UTF16_ENCODINGS,
            Violation.USER_INFO));

    /**
     * Compliance mode that allows all URI Violations, including allowing ambiguous paths in non-canonical form, and illegal characters.
     * <p>
     *     Note: this mode allows URL/URIs that the Servlet spec will reject.
     *     <br>
     *     See <a href="https://github.com/jakartaee/servlet/blob/6.0.0-RELEASE/spec/src/main/asciidoc/servlet-spec-body.adoc#352-uri-path-canonicalization">point 10 "Rejecting Suspicious Sequences" in Section 3.5.2. URI Path Canonicalization</a>,
     *     <br>
     *     and <a href="https://jetty.org/docs/jetty/12/programming-guide/server/compliance.html#servleturi">Jetty Documentation: Servlet URI Compliance Modes.</a>
     * </p>
     */
    public static final UriCompliance UNSAFE = new UriCompliance("UNSAFE", allOf(Violation.class));

    private static final AtomicInteger __custom = new AtomicInteger();
    private static final List<UriCompliance> KNOWN_MODES = List.of(DEFAULT, JETTY_11, LEGACY, RFC3986, UNAMBIGUOUS, UNSAFE);

    public static boolean isAmbiguous(Set<Violation> violations)
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
        if (name.indexOf(',') == -1) // skip warning if delimited, will be handled by .from() properly as a CUSTOM mode.
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

                Violation section = Violation.valueOf(element);
                if (exclude)
                    violations.remove(section);
                else
                    violations.add(section);
            }

            compliance = new UriCompliance("CUSTOM" + __custom.getAndIncrement(), violations);
        }
        return compliance;
    }

    private final String _name;
    private final Set<Violation> _allowed;

    public UriCompliance(String name, Set<Violation> violations)
    {
        Objects.requireNonNull(violations);
        _name = name;
        _allowed = violations.isEmpty() ? NO_VIOLATION : unmodifiableSet(copyOf(violations));
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

    /**
     * Test if violation is referencing a HttpURI.path violation.
     *
     * @param violation the violation to test.
     * @return true if violation is a path violation.
     */
    public static boolean isPathViolation(UriCompliance.Violation violation)
    {
        return PATH_VIOLATIONS.contains(violation);
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

    public static String checkUriCompliance(UriCompliance compliance, HttpURI uri, ComplianceViolation.Listener listener)
    {
        if (uri.hasViolations())
        {
            StringBuilder violations = null;
            for (UriCompliance.Violation violation : uri.getViolations())
            {
                if (compliance == null || !compliance.allows(violation))
                {
                    if (listener != null)
                        listener.onComplianceViolation(new ComplianceViolation.Event(compliance, violation, uri.toString()));

                    if (violations == null)
                        violations = new StringBuilder();
                    else
                        violations.append(", ");
                    violations.append(violation.getDescription());
                }
            }
            if (violations != null)
                return violations.toString();
        }
        return null;
    }
}
