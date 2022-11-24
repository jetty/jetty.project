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

/**
 * <p>If this rule matches, terminates the processing of other rules, allowing
 * the request to be processed by the handlers after the {@link RewriteHandler}.</p>
 */
public class TerminatingPatternRule extends PatternRule
{
    public TerminatingPatternRule()
    {
    }

    public TerminatingPatternRule(String pattern)
    {
        super(pattern);
    }

    @Override
    public boolean isTerminating()
    {
        return true;
    }

    @Override
    protected RuleProcessor apply(RuleProcessor input) throws IOException
    {
        return input;
    }
}
