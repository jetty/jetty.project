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

import java.util.List;
import java.util.function.ToIntFunction;

public class HttpQuotedQualityCSV extends QuotedQualityCSV
{
    private final ComplianceViolation.Mode complianceMode;
    private final ComplianceViolation.Listener listener;

    public HttpQuotedQualityCSV(ComplianceViolation.Mode complianceMode, ComplianceViolation.Listener listener, String[] preferredOrder)
    {
        super(preferredOrder);
        this.complianceMode = complianceMode;
        this.listener = listener;
    }

    public HttpQuotedQualityCSV(ComplianceViolation.Mode complianceMode, ComplianceViolation.Listener listener, List<String> preferredOrder)
    {
        super(preferredOrder);
        this.complianceMode = complianceMode;
        this.listener = listener;
    }

    public HttpQuotedQualityCSV(ComplianceViolation.Mode complianceMode, ComplianceViolation.Listener listener, ToIntFunction<String> secondaryOrdering)
    {
        super(secondaryOrdering);
        this.complianceMode = complianceMode;
        this.listener = listener;
    }

    @Override
    protected void onComplianceViolation(ComplianceViolation violation, String value)
    {
        boolean allowed = complianceMode.allows(violation);
        listener.onComplianceViolation(new ComplianceViolation.Event(complianceMode, violation, value, allowed));
        if (!allowed)
            throw new BadMessageException("Invalid quoted-quality: " + value);
    }
}
