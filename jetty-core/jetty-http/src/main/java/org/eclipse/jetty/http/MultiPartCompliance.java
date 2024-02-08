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
        WHITESPACE_AFTER_PREAMBLE("https://tools.ietf.org/html/rfc2046#section-5.1.1", "Whitespace not allowed after preamble"),
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
        "LEGACY", EnumSet.noneOf(Violation.class));

    private static final List<MultiPartCompliance> KNOWN_MODES = Arrays.asList(RFC7578);

    public static MultiPartCompliance valueOf(String name)
    {
        for (MultiPartCompliance compliance : KNOWN_MODES)
        {
            if (compliance.getName().equals(name))
                return compliance;
        }
        return null;
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
    public Set<? extends ComplianceViolation> getKnown()
    {
        return EnumSet.allOf(Violation.class);
    }

    @Override
    public Set<? extends ComplianceViolation> getAllowed()
    {
        return violations;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x%s", name, hashCode(), violations);
    }
}
