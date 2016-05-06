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

package org.eclipse.jetty.websocket.common.test;

import java.util.regex.Pattern;

import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.TypeSafeMatcher;

public class RegexMatcher extends TypeSafeMatcher
{
    private final Pattern pattern;

    public RegexMatcher(String pattern)
    {
        this(Pattern.compile(pattern));
    }

    public RegexMatcher(Pattern pattern)
    {
        this.pattern = pattern;
    }

    @Override
    public void describeTo(Description description)
    {
        description.appendText("matches regular expression ").appendValue(pattern);
    }

    @Override
    protected boolean matchesSafely(Object item)
    {
        if(item == null) return false;
        return pattern.matcher(item.toString()).matches();
    }

    @Factory
    public static RegexMatcher matchesPattern(Pattern pattern)
    {
        return new RegexMatcher(pattern);
    }

    @Factory
    public static RegexMatcher matchesPattern(String pattern)
    {
        return new RegexMatcher(pattern);
    }

}
