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
import java.util.regex.Matcher;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.annotation.Name;

/**
 * If this rule matches, terminate the processing of other rules.
 * Allowing the request to be processed by the handlers after the rewrite rules.
 */
public class TerminatingRegexRule extends RegexRule
{
    public TerminatingRegexRule()
    {
        this(null);
    }

    public TerminatingRegexRule(@Name("regex") String regex)
    {
        super(regex);
        super.setTerminating(true);
    }

    @Override
    public void setTerminating(boolean terminating)
    {
        if (!terminating)
        {
            throw new RuntimeException("Not allowed to disable terminating on a " + TerminatingRegexRule.class.getName());
        }
    }

    @Override
    public String apply(String target, HttpServletRequest request, HttpServletResponse response, Matcher matcher) throws IOException
    {
        return target;
    }
}
