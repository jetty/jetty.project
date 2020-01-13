//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
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

    public AnnotatedTestConfiguration(@Name("first") String first, @Name("second") String second, @Name("third") String third)
    {
        this.first = first;
        this.second = second;
        this.third = third;
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
