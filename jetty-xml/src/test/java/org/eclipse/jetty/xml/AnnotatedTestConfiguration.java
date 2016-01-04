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

package org.eclipse.jetty.xml;

import org.eclipse.jetty.util.annotation.Name;

public class AnnotatedTestConfiguration
{
    private String first;
    private String second;
    private String third;
    
    AnnotatedTestConfiguration nested;
    
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
    
}
