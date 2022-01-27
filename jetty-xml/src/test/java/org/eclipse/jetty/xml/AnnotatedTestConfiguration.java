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

package org.eclipse.jetty.xml;

import org.eclipse.jetty.util.annotation.Name;

public class AnnotatedTestConfiguration
{
    private String first;
    private String second;
    private String third;
    private String deprecated;
    private AnnotatedTestConfiguration nested;
    // Do not remove deprecation, used in tests.
    @Deprecated
    public String obsolete;

    // Do not remove deprecation, used in tests.
    @Deprecated
    public AnnotatedTestConfiguration()
    {
    }

    public AnnotatedTestConfiguration(Integer test)
    {
        // exists to make constructor matching harder
        throw new UnsupportedOperationException("Should not be called");
    }

    public AnnotatedTestConfiguration(Integer one, Integer two, Integer three)
    {
        // exists to make constructor matching harder
        throw new UnsupportedOperationException("Should not be called");
    }

    public AnnotatedTestConfiguration(@Name("first") String first, @Name("second") String second, @Name("third") String third)
    {
        this.first = first;
        this.second = second;
        this.third = third;
    }

    public AnnotatedTestConfiguration(Long one, Long two, Long three)
    {
        // exists to make constructor matching harder
        throw new UnsupportedOperationException("Should not be called");
    }

    public void setAll(Integer one, Integer two, Integer three)
    {
        // exists to make method matching harder
        throw new UnsupportedOperationException("Should not be called");
    }

    public void setAll(@Name("first") String first, @Name("second") String second, @Name("third") String third)
    {
        this.first = first;
        this.second = second;
        this.third = third;
    }

    public void setAll(long one, long two, long three)
    {
        // exists to make method matching harder
        throw new UnsupportedOperationException("Should not be called");
    }

    public void setVarArgs(String first, String... theRest)
    {
        this.first = first;
        this.second = theRest.length > 0 ? theRest[0] : null;
        this.third = theRest.length > 1 ? theRest[1] : null;
    }

    public void call(Integer value)
    {
        this.first = String.valueOf(value);
    }

    public void call(String value)
    {
        this.second = value;
    }

    public <E> void call(E value)
    {
        this.third = String.valueOf(value);
    }

    public String getFirst()
    {
        return first;
    }

    public void setFirst(String first)
    {
        this.first = first;
    }

    public String getSecond()
    {
        return second;
    }

    public void setSecond(String second)
    {
        this.second = second;
    }

    public String getThird()
    {
        return third;
    }

    public void setThird(String third)
    {
        this.third = third;
    }

    public AnnotatedTestConfiguration getNested()
    {
        return nested;
    }

    public void setNested(AnnotatedTestConfiguration nested)
    {
        this.nested = nested;
    }

    // Do not remove deprecation, used in tests.
    @Deprecated
    public void setDeprecated(String value)
    {
        this.deprecated = value;
    }

    // Do not remove deprecation, used in tests.
    @Deprecated
    public String getDeprecated()
    {
        return deprecated;
    }
}
