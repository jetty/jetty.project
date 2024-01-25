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
        CONTENT_TRANSFER_ENCODING("https://tools.ietf.org/html/rfc7578#section-4.7", "Content-Transfer-Encoding is deprecated");

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

    public static final MultiPartCompliance RFC7578 = new MultiPartCompliance(
        "RFC7578", EnumSet.of(Violation.CONTENT_TRANSFER_ENCODING));

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
