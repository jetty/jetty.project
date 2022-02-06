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

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.util.annotation.Name;
import org.junit.jupiter.api.Disabled;

@Disabled("Not a test case")
public class TestConfiguration extends HashMap<String, Object>
{
    public static int VALUE = 77;

    public final Object id = new Object();

    public final String name;
    public TestConfiguration nested;
    public String testString = "default";
    public Object testObject;
    public int testInt;
    public URL url;
    public static boolean called = false;
    public Object[] oa;
    public int[] ia;
    public int testField1;
    public int testField2;
    public int propValue;
    @SuppressWarnings("rawtypes")
    private List list;
    @SuppressWarnings("rawtypes")
    private Set set;
    private ConstructorArgTestClass constructorArgTestClass;
    public Map map;
    public Double number;

    public TestConfiguration()
    {
        this("");
    }

    public TestConfiguration(@Name("name") String n)
    {
        name = n;
    }

    public void setNumber(Object value)
    {
        testObject = value;
    }

    public void setNumber(double value)
    {
        number = value;
    }

    public void setTest(Object value)
    {
        testObject = value;
    }

    public void setTest(int value)
    {
        testInt = value;
    }

    public void setPropertyTest(int value)
    {
        propValue = value;
    }

    public TestConfiguration getNested()
    {
        return nested;
    }

    public void setNested(TestConfiguration nested)
    {
        this.nested = nested;
    }

    public String getTestString()
    {
        return testString;
    }

    public void setTestString(String testString)
    {
        this.testString = testString;
    }

    public void call()
    {
        put("Called", "Yes");
    }

    public TestConfiguration call(Boolean b)
    {
        nested.put("Arg", b);
        return nested;
    }

    public void call(URL u, boolean b)
    {
        put("URL", b ? "1" : "0");
        url = u;
    }

    public String getString()
    {
        return "String";
    }

    public static void callStatic()
    {
        called = true;
    }

    public void call(Object[] oa)
    {
        this.oa = oa;
    }

    public void call(int[] ia)
    {
        this.ia = ia;
    }

    @SuppressWarnings("rawtypes")
    public List getList()
    {
        if (constructorArgTestClass != null)
            return constructorArgTestClass.getList();
        return list;
    }

    @SuppressWarnings("rawtypes")
    public void setList(List list)
    {
        this.list = list;
    }

    @SuppressWarnings("rawtypes")
    public void setLinkedList(LinkedList list)
    {
        this.list = list;
    }

    @SuppressWarnings("rawtypes")
    public void setArrayList(ArrayList list)
    {
        this.list = list;
    }

    @SuppressWarnings("rawtypes")
    public Set getSet()
    {
        if (constructorArgTestClass != null)
            return constructorArgTestClass.getSet();
        return set;
    }

    @SuppressWarnings("rawtypes")
    public void setSet(Set set)
    {
        this.set = set;
    }

    public void setConstructorArgTestClass(ConstructorArgTestClass constructorArgTestClass)
    {
        this.constructorArgTestClass = constructorArgTestClass;
    }

    public void setMap(Map map)
    {
        this.map = map;
    }
}
