// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.xml;import java.net.URL;
import java.util.HashMap;


public class TestConfiguration extends HashMap
{
    public static int VALUE=77;

    public TestConfiguration nested;
    public Object testObject;
    public int testInt;
    public URL url;
    public static boolean called=false;
    public Object[] oa;
    public int[] ia;
    public int testField1;
    public int testField2;

    public void setTest(Object value)
    {
        testObject=value;
    }

    public void setTest(int value)
    {
        testInt=value;
    }

    public void call()
    {
        put("Called","Yes");
    }

    public TestConfiguration call(Boolean b)
    {
        nested=new TestConfiguration();
        nested.put("Arg",b);
        return nested;
    }

    public void call(URL u,boolean b)
    {
        put("URL",b?"1":"0");
        url=u;
    }

    public String getString()
    {
        return "String";
    }

    public static void callStatic()
    {
        called=true;
    }

    public void call(Object[] oa)
    {
        this.oa=oa;
    }

    public void call(int[] ia)
    {
        this.ia=ia;
    }
}
