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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.EnumSet.allOf;
import static java.util.EnumSet.noneOf;

/**
 * The compliance mode for MultiPart handling.
 */
public class MultiPartCompliance implements ComplianceViolation.Mode
{
    public enum Violation implements ComplianceViolation
    {
        CONTENT_TRANSFER_ENCODING("https://tools.ietf.org/html/rfc7578#section-4.7", "Content-Transfer-Encoding header is deprecated"),
        CR_LINE_TERMINATION("https://tools.ietf.org/html/rfc2046#section-4.1.1", "CR only line termination is forbidden"),
        LF_LINE_TERMINATION("https://tools.ietf.org/html/rfc2046#section-4.1.1", "LF only line termination is forbidden"),
        WHITESPACE_BEFORE_BOUNDARY("https://tools.ietf.org/html/rfc2046#section-5.1.1", "Whitespace not allowed before boundary"),
        BASE64_TRANSFER_ENCODING("https://tools.ietf.org/html/rfc7578#section-4.7", "'base64' Content-Transfer-Encoding is deprecated"),
        QUOTED_PRINTABLE_TRANSFER_ENCODING("https://tools.ietf.org/html/rfc7578#section-4.7", "'quoted-printable' Content-Transfer-Encoding is deprecated");

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
     * RFC7578 {@code multiPart/form-data} compliant strict parsing.
     */
    public static final MultiPartCompliance RFC7578 = new MultiPartCompliance(
        "RFC7578", EnumSet.of(Violation.CONTENT_TRANSFER_ENCODING));

    /**
     * Legacy {@code multiPart/form-data} parsing which is slow, buggy, but forgiving to a fault.
     * This mode is not recommended for websites on the public internet.
     * It will accept non-compliant preambles and inconsistent line termination that are in violation of RFC7578.
     */
    public static final MultiPartCompliance LEGACY =  new MultiPartCompliance(
        "LEGACY", EnumSet.complementOf(EnumSet.of(Violation.BASE64_TRANSFER_ENCODING)));

    private static final List<MultiPartCompliance> KNOWN_MODES = Arrays.asList(RFC7578, LEGACY);
    private static final AtomicInteger __custom = new AtomicInteger();

    public static MultiPartCompliance valueOf(String name)
    {
        for (MultiPartCompliance compliance : KNOWN_MODES)
        {
            if (compliance.getName().equals(name))
                return compliance;
        }
        return null;
    }

    /**
     * Create compliance set from a set of allowed Violations.
     *
     * @param violations A string of violations to allow:
     * @return the compliance from the string spec
     */
    public static MultiPartCompliance from(Set<MultiPartCompliance.Violation> violations)
    {
        return new MultiPartCompliance("CUSTOM" + __custom.getAndIncrement(), violations);
    }

    /**
     * Create compliance set from string.
     * <p>
     * Format: &lt;BASE&gt;[,[-]&lt;violation&gt;]...
     * </p>
     * <p>BASE is one of:</p>
     * <dl>
     * <dt>0</dt><dd>No {@link MultiPartCompliance.Violation}s</dd>
     * <dt>*</dt><dd>All {@link MultiPartCompliance.Violation}s</dd>
     * <dt>&lt;name&gt;</dt><dd>The name of a static instance of MultiPartCompliance (e.g. {@link MultiPartCompliance#LEGACY}).
     * </dl>
     * <p>
     * The remainder of the list can contain then names of {@link MultiPartCompliance.Violation}s to include them in the mode, or prefixed
     * with a '-' to exclude them from the mode.  Examples are:
     * </p>
     * <dl>
     * <dt>{@code 0,CONTENT_TRANSFER_ENCODING}</dt><dd>Only allow {@link MultiPartCompliance.Violation#CONTENT_TRANSFER_ENCODING}</dd>
     * <dt>{@code *,-BASE64_TRANSFER_ENCODING}</dt><dd>Only all except {@link MultiPartCompliance.Violation#BASE64_TRANSFER_ENCODING}</dd>
     * <dt>{@code LEGACY,BASE64_TRANSFER_ENCODING}</dt><dd>Same as LEGACY plus {@link MultiPartCompliance.Violation#BASE64_TRANSFER_ENCODING}</dd>
     * </dl>
     *
     * @param spec A string describing the compliance
     * @return the MultiPartCompliance instance derived from the string description
     */
    public static MultiPartCompliance from(String spec)
    {
        MultiPartCompliance compliance = valueOf(spec);
        if (compliance == null)
        {
            String[] elements = spec.split("\\s*,\\s*");

            Set<MultiPartCompliance.Violation> violations = switch (elements[0])
            {
                case "0" -> noneOf(MultiPartCompliance.Violation.class);
                case "*" -> allOf(MultiPartCompliance.Violation.class);
                default ->
                {
                    MultiPartCompliance mode = MultiPartCompliance.valueOf(elements[0]);
                    yield (mode == null) ? noneOf(MultiPartCompliance.Violation.class) : copyOf(mode.getAllowed());
                }
            };

            for (int i = 1; i < elements.length; i++)
            {
                String element = elements[i];
                boolean exclude = element.startsWith("-");
                if (exclude)
                    element = element.substring(1);

                MultiPartCompliance.Violation section = MultiPartCompliance.Violation.valueOf(element);
                if (exclude)
                    violations.remove(section);
                else
                    violations.add(section);
            }

            compliance = new MultiPartCompliance("CUSTOM" + __custom.getAndIncrement(), violations);
        }
        return compliance;
    }

    private static Set<MultiPartCompliance.Violation> copyOf(Set<MultiPartCompliance.Violation> violations)
    {
        if (violations == null || violations.isEmpty())
            return EnumSet.noneOf(MultiPartCompliance.Violation.class);
        return EnumSet.copyOf(violations);
    }

    private final String name;
    private final Set<Violation> violations;

    public MultiPartCompliance(String name, Set<Violation> violations)
    {
        this.name = name;
        this.violations = violations;
    }

    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public boolean allows(ComplianceViolation violation)
    {
        return violations.contains(violation);
    }

    @Override
    public Set<Violation> getKnown()
    {
        return EnumSet.allOf(Violation.class);
    }

    @Override
    public Set<Violation> getAllowed()
    {
        return violations;
    }

    @Override
    public String toString()
    {
        if (this == RFC7578 || this == LEGACY)
            return name;
        return String.format("%s@%x(v=%s)", name, hashCode(), violations);
    }
}
