//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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