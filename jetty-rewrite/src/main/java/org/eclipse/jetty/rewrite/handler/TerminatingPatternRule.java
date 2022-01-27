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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * If this rule matches, terminate the processing of other rules.
 * Allowing the request to be processed by the handlers after the rewrite rules.
 */
public class TerminatingPatternRule extends PatternRule
{
    public TerminatingPatternRule()
    {
        this(null);
    }

    public TerminatingPatternRule(String pattern)
    {
        super(pattern);
        super.setTerminating(true);
    }

    @Override
    public void setTerminating(boolean terminating)
    {
        if (!terminating)
        {
            throw new RuntimeException("Not allowed to disable terminating on a " + TerminatingPatternRule.class.getName());
        }
    }

    @Override
    protected String apply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        return target;
    }
}
