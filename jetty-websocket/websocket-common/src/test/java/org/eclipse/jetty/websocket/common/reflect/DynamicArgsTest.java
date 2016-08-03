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
import org.junit.Test;

public class DynamicArgsTest
{
    public static class A
    {
        private final String id;
        
        public A(String id)
        {
            this.id = id;
        }
        
        public String toString()
        {
            return String.format("A:%s",id);
        }
    }
    
    public static class B
    {
        private final int val;
        
        public B(int val)
        {
            this.val = val;
        }
        
        public String toString()
        {
            return String.format("B:%d",val);
        }
    }
    
    @SuppressWarnings("unused")
    public static class SampleSignatures
    {
        public String sigEmpty()
        {
            return "sigEmpty<>";
        }
        
        public String sigStr(String str)
        {
            return String.format("sigStr<%s>", q(str));
        }
        
        public String sigStrFile(String str, File foo)
        {
            return String.format("sigStrFile<%s,%s>", q(str), q(foo));
        }
        
        public String sigFileStr(File foo, String str)
        {
            return String.format("sigFileStr<%s,%s>", q(foo), q(str));
        }
        
        public String sigFileStrFin(File foo, String str, @Name("fin") boolean fin)
        {
            return String.format("sigFileStrFin<%s,%s,%b>", q(foo), q(str), fin);
        }
        
        public String sigByteArray(byte[] buf, @Name("offset") int offset, @Name("length") int len)
        {
            return String.format("sigByteArray<%s,%d,%d>", buf == null ? "<null>" : ("[" + buf.length + "]"), offset, len);
        }
        
        public String sigObjectArgs(A a, B b)
        {
            return String.format("sigObjectArgs<%s,%s>", q(a), q(b));
        }
    
        public String sigObjectA(A a)
        {
            return String.format("sigObjectA<%s>", q(a));
        }
    
        public String sigObjectB(B b)
        {
            return String.format("sigObjectB<%s>", q(b));
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
    
    /**
     * Test with method that has empty signature,
     * and desired callable that also has an empty signature
     *
     * @throws Exception on error
     */
    @Test
    public void testEmptySignature() throws Exception
    {
        DynamicArgs.Builder dab = new DynamicArgs.Builder();
        dab.addSignature(); // intentionally empty
        
        SampleSignatures samples = new SampleSignatures();
        Method m = findMethodByName(samples, "sigEmpty");
        DynamicArgs dynamicArgs = dab.build(m);
        assertThat("DynamicArgs", dynamicArgs, notNullValue());
        
        // Test with empty potential args
        String result = (String) dynamicArgs.invoke(samples);
        assertThat("result", result, is("sigEmpty<>"));
    }
    
    /**
     * Test with method that has empty signature,
     * and desired callable that has a String (optional) signature
     *
     * @throws Exception on error
     */
    @Test
    public void testEmptySignature_StringCallable() throws Exception
    {
        final Arg ARG_STR = new Arg(String.class);
        
        DynamicArgs.Builder dab = new DynamicArgs.Builder();
        dab.addSignature(ARG_STR);
        
        SampleSignatures samples = new SampleSignatures();
        Method m = findMethodByName(samples, "sigEmpty");
        DynamicArgs dynamicArgs = dab.build(m);
        assertThat("DynamicArgs", dynamicArgs, notNullValue());
        
        // Test with empty potential args
        String result = (String) dynamicArgs.invoke(samples, "Hello");
        assertThat("result", result, is("sigEmpty<>"));
    }
    
    /**
     * Test with method that has String signature, and
     * a desired callable that also has String signature.
     *
     * @throws Exception on error
     */
    @Test
    public void testStringSignature() throws Exception
    {
        final Arg ARG_STR = new Arg(String.class);
        
        DynamicArgs.Builder dab = new DynamicArgs.Builder();
        dab.addSignature(ARG_STR);
        
        final Arg CALL_STR = new Arg(String.class);
        
        SampleSignatures samples = new SampleSignatures();
        Method m = findMethodByName(samples, "sigStr");
        DynamicArgs dynamicArgs = dab.build(m, CALL_STR);
        assertThat("DynamicArgs", dynamicArgs, notNullValue());
        
        // Test with potential args
        String result = (String) dynamicArgs.invoke(samples, "Hello");
        assertThat("result", result, is("sigStr<Hello>"));
    }
    
    /**
     * Test of finding a match on a method that is tagged
     * via the ArgIdentifier concepts.
     *
     * @throws Exception on error
     */
    @Test
    public void testByteArraySignature() throws Exception
    {
        final Arg ARG_BYTEARRAY = new Arg(byte[].class);
        final Arg ARG_OFFSET = new Arg(int.class).setTag("offset");
        final Arg ARG_LENGTH = new Arg(int.class).setTag("length");
        
        DynamicArgs.Builder dab = new DynamicArgs.Builder();
        dab.addSignature(ARG_BYTEARRAY, ARG_OFFSET, ARG_LENGTH);
        
        SampleSignatures ssigs = new SampleSignatures();
        Method m = findMethodByName(ssigs, "sigByteArray");
        DynamicArgs dynamicArgs = dab.build(m, ARG_BYTEARRAY, ARG_OFFSET, ARG_LENGTH);
        assertThat("DynamicArgs", dynamicArgs, notNullValue());
        
        // Test with potential args
        byte buf[] = new byte[222];
        int offset = 3;
        int len = 44;
        String result = (String) dynamicArgs.invoke(ssigs, buf, offset, len);
        assertThat("result", result, is("sigByteArray<[222],3,44>"));
        
        // Test with empty potential args
        result = (String) dynamicArgs.invoke(ssigs, null, 123, 456);
        assertThat("result", result, is("sigByteArray<<null>,123,456>"));
    }
    
    /**
     * Test of calling a method with 2 custom objects
     *
     * @throws Exception on error
     */
    @Test
    public void testObjects_A_B() throws Exception
    {
        final Arg ARG_A = new Arg(A.class);
        final Arg ARG_B = new Arg(B.class);
        
        DynamicArgs.Builder dab = new DynamicArgs.Builder();
        dab.addSignature(ARG_A, ARG_B);
        
        SampleSignatures ssigs = new SampleSignatures();
        Method m = findMethodByName(ssigs, "sigObjectArgs");
        DynamicArgs dynamicArgs = dab.build(m, ARG_A, ARG_B);
        assertThat("DynamicArgs", dynamicArgs, notNullValue());
        
        // Test with potential args
        A a = new A("foo");
        B b = new B(444);
        String result = (String) dynamicArgs.invoke(ssigs, a, b);
        assertThat("result", result, is("sigObjectArgs<A:foo,B:444>"));
        
        // Test with null potential args
        result = (String) dynamicArgs.invoke(ssigs, null, b);
        assertThat("result", result, is("sigObjectArgs<<null>,B:444>"));
        
        result = (String) dynamicArgs.invoke(ssigs, a, null);
        assertThat("result", result, is("sigObjectArgs<A:foo,<null>>"));
    }
    
    /**
     * Test of calling a method with 2 custom objects, but the method only has 1 declared
     *
     * @throws Exception on error
     */
    @Test
    public void testObjects_A() throws Exception
    {
        final Arg ARG_A = new Arg(A.class);
        final Arg ARG_B = new Arg(B.class);
        
        DynamicArgs.Builder dab = new DynamicArgs.Builder();
        dab.addSignature(ARG_A, ARG_B);
        
        SampleSignatures ssigs = new SampleSignatures();
        Method m = findMethodByName(ssigs, "sigObjectA");
        DynamicArgs dynamicArgs = dab.build(m, ARG_A, ARG_B);
        assertThat("DynamicArgs", dynamicArgs, notNullValue());
        
        // Test with potential args
        A a = new A("foo");
        B b = new B(555);
        String result = (String) dynamicArgs.invoke(ssigs, a, b);
        assertThat("result", result, is("sigObjectA<A:foo>"));
        
        // Test with null potential args
        result = (String) dynamicArgs.invoke(ssigs, null, b);
        assertThat("result", result, is("sigObjectA<<null>>"));
        
        result = (String) dynamicArgs.invoke(ssigs, a, null);
        assertThat("result", result, is("sigObjectA<A:foo>"));
    }
    
    /**
     * Test of calling a method with 2 custom objects, but the method only has 1 declared
     *
     * @throws Exception on error
     */
    @Test
    public void testObjects_B() throws Exception
    {
        final Arg ARG_A = new Arg(A.class);
        final Arg ARG_B = new Arg(B.class);
        
        DynamicArgs.Builder dab = new DynamicArgs.Builder();
        dab.addSignature(ARG_A, ARG_B);
        
        SampleSignatures ssigs = new SampleSignatures();
        Method m = findMethodByName(ssigs, "sigObjectB");
        DynamicArgs dynamicArgs = dab.build(m, ARG_A, ARG_B);
        assertThat("DynamicArgs", dynamicArgs, notNullValue());
        
        // Test with potential args
        A a = new A("foo");
        B b = new B(666);
        String result = (String) dynamicArgs.invoke(ssigs, a, b);
        assertThat("result", result, is("sigObjectB<B:666>"));
        
        // Test with null potential args
        result = (String) dynamicArgs.invoke(ssigs, null, b);
        assertThat("result", result, is("sigObjectB<B:666>"));
        
        result = (String) dynamicArgs.invoke(ssigs, a, null);
        assertThat("result", result, is("sigObjectB<<null>>"));
    }
}
