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

public class HttpQuotedCSV extends QuotedCSV
{
    private final ComplianceViolation.Mode complianceMode;
    private final ComplianceViolation.Listener listener;

    public HttpQuotedCSV(ComplianceViolation.Mode complianceMode, ComplianceViolation.Listener listener)
    {
        this(complianceMode, listener, true);
    }

    public HttpQuotedCSV(ComplianceViolation.Mode complianceMode, ComplianceViolation.Listener listener, boolean keepQuotes, String... values)
    {
        // Do not pass in `values` here.
        super(keepQuotes);
        this.complianceMode = complianceMode;
        this.listener = listener;
        // Need to parse AFTER the complianceMode and listener are set.
        if (values != null)
        {
            for (String value : values)
            {
                addValue(value);
            }
        }
    }

    @Override
    protected void onComplianceViolation(ComplianceViolation violation, String value)
    {
        if (complianceMode != null)
        {
            boolean allowed = complianceMode.allows(violation);
            listener.onComplianceViolation(new ComplianceViolation.Event(complianceMode, violation, value, allowed));
            if (!allowed)
                throw new BadMessageException("Invalid quoted: " + value);
        }
    }

    public static class Etags extends HttpQuotedCSV
    {
        public Etags(ComplianceViolation.Mode complianceMode, ComplianceViolation.Listener listener, String... values)
        {
            super(complianceMode, listener, true, values);
        }

        @Override
        protected void openingQuoteInValue(String value, int i)
        {
            if (i < 1 || Character.toLowerCase(value.charAt(i - 2)) != 'w' || value.charAt(i - 1) != '/')
                super.openingQuoteInValue(value, i);
        }
    }
}
