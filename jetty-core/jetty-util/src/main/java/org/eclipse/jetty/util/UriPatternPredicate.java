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

package org.eclipse.jetty.util;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * <p>
 * Predicate for matching {@link URI} against a provided Regex {@link Pattern}
 * </p>
 *
 * <p>
 * If the pattern is null and isNullInclusive is true, then
 * all URIs will match.
 * </p>
 *
 * <p>
 * A pattern is a set of acceptable URI names. Each acceptable
 * name is a regex. Each regex can be separated by either a
 * "," or a "|". If you use a "|" this or's together the URI
 * name patterns. This means that ordering of the matches is
 * unimportant to you. If instead, you want to match particular
 * names, and you want to match them in order, you should
 * separate the regexs with "," instead.
 * </p>
 *
 * <p>
 * Eg "aaa-.*\\.jar|bbb-.*\\.jar"
 * Will iterate over the jar names and match in any order.
 * </p>
 *
 * <p>
 * Eg "aaa-*\\.jar,bbb-.*\\.jar"
 * Will iterate over the jar names, matching
 * all those starting with "aaa-" first, then "bbb-".
 * </p>
 */
public class UriPatternPredicate implements Predicate<URI>
{
    private final List<Pattern> subPatterns;
    private final boolean isNullInclusive;

    public UriPatternPredicate(String regex, boolean isNullInclusive)
    {
        this(Pattern.compile(regex), isNullInclusive);
    }

    public UriPatternPredicate(Pattern pattern, boolean isNullInclusive)
    {
        this.isNullInclusive = isNullInclusive;
        String[] patterns = (pattern == null ? null : pattern.pattern().split(","));

        subPatterns = new ArrayList<>();
        for (int i = 0; patterns != null && i < patterns.length; i++)
        {
            subPatterns.add(Pattern.compile(patterns[i]));
        }
        if (subPatterns.isEmpty())
            subPatterns.add(pattern);
    }

    @Override
    public boolean test(URI uri)
    {
        if (subPatterns.isEmpty())
        {
            return match(null, uri);
        }
        else
        {
            // for each sub-pattern, test if a match
            for (Pattern p : subPatterns)
            {
                if (match(p, uri))
                    return true;
            }
        }

        return false;
    }

    private boolean match(Pattern pattern, URI uri)
    {
        String s = uri.toString();
        return ((pattern == null && isNullInclusive) ||
            (pattern != null && pattern.matcher(s).matches()));
    }
}
