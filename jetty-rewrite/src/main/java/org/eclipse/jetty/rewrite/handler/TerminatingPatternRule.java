//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
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
