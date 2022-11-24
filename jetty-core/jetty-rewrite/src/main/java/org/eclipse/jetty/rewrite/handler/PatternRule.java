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

import org.eclipse.jetty.http.pathmap.ServletPathSpec;

/**
 * <p>Abstract rule that uses the Servlet pattern syntax via
 * {@link ServletPathSpec} for path pattern matching.</p>
 */
public abstract class PatternRule extends Rule
{
    private String _pattern;

    protected PatternRule()
    {
    }

    protected PatternRule(String pattern)
    {
        _pattern = pattern;
    }

    public String getPattern()
    {
        return _pattern;
    }

    public void setPattern(String pattern)
    {
        _pattern = pattern;
    }

    @Override
    public RuleProcessor matchAndApply(RuleProcessor input) throws IOException
    {
        if (ServletPathSpec.match(_pattern, input.getHttpURI().getPath()))
            return apply(input);
        return null;
    }

    /**
     * <p>Invoked after the Servlet pattern matched the URI path to apply the rule's logic.</p>
     *
     * @param input the input {@code Request} and {@code Processor}
     * @return the possibly wrapped {@code Request} and {@code Processor}
     * @throws IOException if applying the rule failed
     */
    protected abstract RuleProcessor apply(RuleProcessor input) throws IOException;

    @Override
    public String toString()
    {
        return "%s[pattern=%s]".formatted(super.toString(), getPattern());
    }
}
