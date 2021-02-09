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
    private long timeout = -1;
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

    // Do not remove deprecation, used in tests.
    @Deprecated
    public long getTimeout()
    {
        return timeout;
    }

    // Do not remove deprecation, used in tests.
    @Deprecated
    public void setTimeout(long value)
    {
        this.timeout = value;
    }
}
