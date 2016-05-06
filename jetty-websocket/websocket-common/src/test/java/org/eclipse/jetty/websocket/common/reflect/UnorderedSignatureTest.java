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

public class UnorderedSignatureTest
{
    @SuppressWarnings("unused")
    public static class SampleSignatures
    {
        public String sigEmpty()
        {
            return "sigEmpty<>";
        }

        public String sigStr(String str)
        {
            return String.format("sigStr<%s>",str);
        }

        public String sigStrFile(String str, File foo)
        {
            return String.format("sigStrFile<%s,%s>",str,foo);
        }

        public String sigFileStr(File foo, String str)
        {
            return String.format("sigFileStr<%s,%s>",foo,str);
        }

        public String sigFileStrFin(File foo, String str, @Name("fin") boolean fin)
        {
            return String.format("sigFileStrFin<%s,%s,%b>",foo,str,fin);
        }

        public String sigByteArray(byte[] buf, @Name("offset")int offset, @Name("length")int len)
        {
            return String.format("sigByteArray<%s,%d,%d>",buf == null ? "<null>" : ("[" + buf.length + "]"),offset,len);
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

    private static final Arg ARG_STR = new Arg(String.class);
    private static final Arg ARG_BOOL = new Arg(Boolean.class);
    private static final Arg ARG_FILE = new Arg(File.class);
    private static final Arg ARG_BYTEARRAY = new Arg(byte[].class);
    private static final Arg ARG_OFFSET = new Arg(int.class).setTag("offset");
    private static final Arg ARG_LENGTH = new Arg(int.class).setTag("length");
    private static final Arg ARG_FIN = new Arg(Boolean.class).setTag("fin");

    /**
     * Test with method that has empty signature,
     * and desired callable that also has an empty signature
     * @throws Exception on error
     */
    @Test
    public void testEmptySignature() throws Exception
    {
        DynamicArgs.Builder dab = new DynamicArgs.Builder();
        dab.addSignature(new UnorderedSignature());

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
     * @throws Exception on error
     */
    @Test
    public void testEmptySignature_StringCallable
    () throws Exception
    {
        DynamicArgs.Builder dab = new DynamicArgs.Builder();
        dab.addSignature(new UnorderedSignature(ARG_STR));

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
     * @throws Exception on error
     */
    @Test
    public void testStringSignature() throws Exception
    {
        DynamicArgs.Builder dab = new DynamicArgs.Builder();
        dab.addSignature(new UnorderedSignature(ARG_STR));

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
     * via a the ArgIdentifier concepts.
     * @throws Exception on error
     */
    @Test
    public void testByteArraySignature() throws Exception
    {
        DynamicArgs.Builder dab = new DynamicArgs.Builder();
        dab.addSignature(new UnorderedSignature(ARG_BYTEARRAY, ARG_OFFSET, ARG_LENGTH));

        final Arg CALL_BYTEARRAY = new Arg(byte[].class);
        final Arg CALL_OFFSET = new Arg(int.class).setTag("offset");
        final Arg CALL_LENGTH = new Arg(int.class).setTag("length");

        SampleSignatures ssigs = new SampleSignatures();
        Method m = findMethodByName(ssigs, "sigByteArray");
        DynamicArgs dynamicArgs = dab.build(m,CALL_BYTEARRAY, CALL_OFFSET, CALL_LENGTH);
        assertThat("DynamicArgs", dynamicArgs, notNullValue());

        // Test with potential args
        byte buf[] = new byte[222];
        int offset = 3;
        int len = 44;
        String result = (String)dynamicArgs.invoke(ssigs,buf,offset,len);
        assertThat("result", result, is("sigByteArray<[222],3,44>"));

        // Test with empty potential args
        result = (String)dynamicArgs.invoke(ssigs,null,123,456);
        assertThat("result", result, is("sigByteArray<<null>,123,456>"));
    }
}
