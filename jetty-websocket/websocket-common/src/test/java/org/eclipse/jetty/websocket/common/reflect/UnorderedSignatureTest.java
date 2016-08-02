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

package org.eclipse.jetty.websocket.common.reflect;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.lang.reflect.Method;

import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.Test;

public class UnorderedSignatureTest
{
    public static class SampleSignatures
    {
        public String sigEmpty()
        {
            return "sigEmpty<>";
        }
        
        public String sigStr(String str)
        {
            return String.format("sigStr<%s>", str);
        }
        
        public String sigStrFile(String str, File foo)
        {
            return String.format("sigStrFile<%s,%s>", str, q(foo));
        }
        
        public String sigFileStr(File foo, String str)
        {
            return String.format("sigFileStr<%s,%s>", q(foo), str);
        }
        
        public String sigFileStrFin(File foo, String str, @Name("fin") boolean fin)
        {
            return String.format("sigFileStrFin<%s,%s,%b>", q(foo), q(str), fin);
        }
        
        public String sigByteArray(byte[] buf, @Name("offset") int offset, @Name("length") int len)
        {
            return String.format("sigByteArray<%s,%d,%d>", buf == null ? "<null>" : ("[" + buf.length + "]"), offset, len);
        }
        
        private String q(Object obj)
        {
            if (obj == null)
                return "<null>";
            else
                return obj.toString();
        }
    }
    
    public static Method findMethodByName(Object obj, String name)
    {
        for (Method method : obj.getClass().getMethods())
        {
            if (method.getName().equals(name))
            {
                return method;
            }
        }
        throw new AssertionError("Unable to find method: " + name);
    }
    
    private void assertMappings(int actualMapping[], int... expectedMapping)
    {
        assertThat("mapping", actualMapping, notNullValue());
        assertThat("mapping.length", actualMapping.length, is(expectedMapping.length));
        if (expectedMapping.length > 0)
        {
            for (int i = 0; i < expectedMapping.length; i++)
            {
                assertThat("mapping[" + i + "]", actualMapping[i], is(expectedMapping[i]));
            }
        }
    }
    
    @Test
    public void testEmpty_Call_Session()
    {
        UnorderedSignature sig = new UnorderedSignature(new Arg(Session.class));
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigEmpty");
        
        int mapping[] = sig.getArgMapping(method, false, new Arg(Session.class));
        assertMappings(mapping);
    }
    
    @Test
    public void testEmpty_Call_None()
    {
        UnorderedSignature sig = new UnorderedSignature();
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigEmpty");
        
        int mapping[] = sig.getArgMapping(method, false);
        assertMappings(mapping);
    }
    
    @Test
    public void testString_Call_String()
    {
        UnorderedSignature sig = new UnorderedSignature();
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigStr");
        
        int mapping[] = sig.getArgMapping(method, false, new Arg(String.class));
        assertMappings(mapping, 0);
    }
    
    @Test
    public void testString_Call_Session_String()
    {
        UnorderedSignature sig = new UnorderedSignature();
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigStr");
        
        int mapping[] = sig.getArgMapping(method, false, new Arg(Session.class), new Arg(String.class));
        assertMappings(mapping, 1);
    }
    
    @Test
    public void testStringFile_Call_String_File()
    {
        UnorderedSignature sig = new UnorderedSignature();
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigStrFile");
        
        int mapping[] = sig.getArgMapping(method, false, new Arg(String.class), new Arg(File.class));
        assertMappings(mapping, 0, 1);
    }
    
    @Test
    public void testStringFile_Call_File_String()
    {
        UnorderedSignature sig = new UnorderedSignature();
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigStrFile");
        
        int mapping[] = sig.getArgMapping(method, false, new Arg(File.class), new Arg(String.class));
        assertMappings(mapping, 1, 0);
    }
    
    @Test
    public void testFileString_Call_String_File()
    {
        UnorderedSignature sig = new UnorderedSignature();
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigFileStr");
        
        int mapping[] = sig.getArgMapping(method, false, new Arg(String.class), new Arg(File.class));
        assertMappings(mapping, 1, 0);
    }
    
    @Test
    public void testFileString_Call_File_String()
    {
        UnorderedSignature sig = new UnorderedSignature();
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigFileStr");
        
        int mapping[] = sig.getArgMapping(method, false, new Arg(File.class), new Arg(String.class));
        assertMappings(mapping, 0, 1);
    }
    
    @Test
    public void testFileStringFin_Call_File_String_BoolTag()
    {
        UnorderedSignature sig = new UnorderedSignature();
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigFileStrFin");
        
        Arg callArgs[] = {new Arg(File.class), new Arg(String.class), new Arg(boolean.class).setTag("fin")};
        
        int mapping[] = sig.getArgMapping(method, false, callArgs);
        assertMappings(mapping, 0, 1, 2);
        
        Object params[] = {new File("foo"), "bar", true};
        String resp = (String) sig.getInvoker(method, callArgs).apply(samples, params);
        assertThat("Invoked response", resp, is("sigFileStrFin<foo,bar,true>"));
    }
    
    @Test
    public void testFileStringFin_Call_BoolTag_File_String()
    {
        UnorderedSignature sig = new UnorderedSignature();
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigFileStrFin");
        
        Arg callArgs[] = {new Arg(boolean.class).setTag("fin"), new Arg(File.class), new Arg(String.class)};
        
        int mapping[] = sig.getArgMapping(method, false, callArgs);
        assertMappings(mapping, 1, 2, 0);
        
        Object params[] = {true, new File("foo"), "bar"};
        String resp = (String) sig.getInvoker(method, callArgs).apply(samples, params);
        assertThat("Invoked response", resp, is("sigFileStrFin<foo,bar,true>"));
    }
    
    @Test
    public void testFileStringFin_Call_BoolTag_Null_String()
    {
        UnorderedSignature sig = new UnorderedSignature();
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigFileStrFin");
        
        Arg callArgs[] = {new Arg(boolean.class).setTag("fin"), new Arg(File.class), new Arg(String.class)};
        
        int mapping[] = sig.getArgMapping(method, false, callArgs);
        assertMappings(mapping, 1, 2, 0);
        
        Object params[] = {true, null, "bar"};
        String resp = (String) sig.getInvoker(method, callArgs).apply(samples, params);
        assertThat("Invoked response", resp, is("sigFileStrFin<<null>,bar,true>"));
    }
}
