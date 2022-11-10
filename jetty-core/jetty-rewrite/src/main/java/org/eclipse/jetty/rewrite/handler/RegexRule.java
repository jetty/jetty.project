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
import java.util.regex.Pattern;

/**
 * <p>Abstract rule that uses the regular expression syntax for path pattern matching.</p>
 */
public abstract class RegexRule extends Rule
{
    private Pattern _regex;

    public RegexRule()
    {
    }

    public RegexRule(String pattern)
    {
        setRegex(pattern);
    }

    /**
     * @return the regular expression
     */
    public String getRegex()
    {
        return _regex == null ? null : _regex.pattern();
    }

    /**
     * <p>Sets the regular expression to match with the request path.</p>
     *
     * @param regex the regular expression
     */
    public void setRegex(String regex)
    {
        _regex = regex == null ? null : Pattern.compile(regex);
    }

    @Override
    public RequestProcessor matchAndApply(RequestProcessor input) throws IOException
    {
        String target = input.getHttpURI().getPathQuery();
        Matcher matcher = _regex.matcher(target);
        if (matcher.matches())
            return apply(input, matcher);
        return null;
    }

    /**
     * <p>Invoked after the regular expression matched the URI path to apply the rule's logic.</p>
     *
     * @param input the input {@code Request} and {@code Processor}
     * @param matcher the {@code Matcher} that matched the request path, with capture groups available for replacement.
     * @return the possibly wrapped {@code Request} and {@code Processor}
     * @throws IOException if applying the rule failed
     */
    protected abstract RequestProcessor apply(RequestProcessor input, Matcher matcher) throws IOException;

    @Override
    public String toString()
    {
        return "%s[regex:%s]".formatted(super.toString(), getRegex());
    }
}
