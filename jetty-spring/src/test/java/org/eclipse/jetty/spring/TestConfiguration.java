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

package org.eclipse.jetty.spring;

import java.net.URL;

import org.junit.jupiter.api.Disabled;

@Disabled("Not a test case")
public class TestConfiguration
{
    public static int VALUE = 77;

    public TestConfiguration nested;
    public String testString0 = "default";
    public String testString1;
    public String testString2;
    public int testInt0 = -1;
    public int testInt1;
    public int testInt2;
    public URL url;
    public Object[] objArray;
    public int[] intArray;

    public static int getVALUE()
    {
        return VALUE;
    }

    public static void setVALUE(int vALUE)
    {
        VALUE = vALUE;
    }

    public TestConfiguration()
    {
    }

    public TestConfiguration getNested()
    {
        return nested;
    }

    public void setNested(TestConfiguration nested)
    {
        this.nested = nested;
    }

    public String getTestString0()
    {
        return testString0;
    }

    public void setTestString0(String testString0)
    {
        this.testString0 = testString0;
    }

    public String getTestString1()
    {
        return testString1;
    }

    public void setTestString1(String testString1)
    {
        this.testString1 = testString1;
    }

    public String getTestString2()
    {
        return testString2;
    }

    public void setTestString2(String testString2)
    {
        this.testString2 = testString2;
    }

    public int getTestInt0()
    {
        return testInt0;
    }

    public void setTestInt0(int testInt0)
    {
        this.testInt0 = testInt0;
    }

    public int getTestInt1()
    {
        return testInt1;
    }

    public void setTestInt1(int testInt1)
    {
        this.testInt1 = testInt1;
    }

    public int getTestInt2()
    {
        return testInt2;
    }

    public void setTestInt2(int testInt2)
    {
        this.testInt2 = testInt2;
    }

    public URL getUrl()
    {
        return url;
    }

    public void setUrl(URL url)
    {
        this.url = url;
    }

    public Object[] getObjArray()
    {
        return objArray;
    }

    public void setObjArray(Object[] objArray)
    {
        this.objArray = objArray;
    }

    public int[] getIntArray()
    {
        return intArray;
    }

    public void setIntArray(int[] intArray)
    {
        this.intArray = intArray;
    }
}
