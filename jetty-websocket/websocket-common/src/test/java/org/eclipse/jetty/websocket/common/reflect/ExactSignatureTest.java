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

import org.junit.Test;

public class ExactSignatureTest
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
        
        public String sigByteArray(byte[] buf, int offset, int len)
        {
            return String.format("sigByteArray<%s,%d,%d>", buf == null ? "<null>" : ("[" + buf.length + "]"), offset, len);
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
    
    private static final Arg ARG_STR = new Arg(1, String.class);
    private static final Arg ARG_BOOL = new Arg(2, Boolean.class);
    private static final Arg ARG_FILE = new Arg(3, File.class);
    private static final Arg ARG_BYTEARRAY = new Arg(4, byte[].class);
    private static final Arg ARG_OFFSET = new Arg(5, int.class);
    private static final Arg ARG_LEN = new Arg(6, int.class);

    @Test
    public void testEmptySignature() throws Exception
    {
        DynamicArgs.Builder dab = new DynamicArgs.Builder();
        dab.addSignature(new ExactSignature());

        SampleSignatures ssigs = new SampleSignatures();
        Method m = findMethodByName(ssigs, "sigEmpty");
        DynamicArgs dargs = dab.build(m, ARG_STR, ARG_BOOL, ARG_FILE);
        assertThat("DynamicArgs", dargs, notNullValue());
        
        // Test with potential args
        String result = (String) dargs.invoke(ssigs, "Hello", Boolean.TRUE, new File("bar"));
        assertThat("result", result, is("sigEmpty<>"));
    }
    
    @Test
    public void testStringSignature() throws Exception
    {
        DynamicArgs.Builder dab = new DynamicArgs.Builder();
        dab.addSignature(new ExactSignature(ARG_STR));

        SampleSignatures ssigs = new SampleSignatures();
        Method m = findMethodByName(ssigs, "sigStr");
        DynamicArgs dargs = dab.build(m, ARG_STR, ARG_BOOL, ARG_FILE);
        assertThat("DynamicArgs", dargs, notNullValue());
        
        // Test with potential args
        String result = (String) dargs.invoke(ssigs, "Hello", Boolean.TRUE, new File("bar"));
        assertThat("result", result, is("sigStr<Hello>"));
    }
    
    @Test
    public void testByteArraySignature() throws Exception
    {
        DynamicArgs.Builder dab = new DynamicArgs.Builder();
        dab.addSignature(new ExactSignature(ARG_BYTEARRAY, ARG_OFFSET, ARG_LEN));

        SampleSignatures ssigs = new SampleSignatures();
        Method m = findMethodByName(ssigs, "sigByteArray");
        DynamicArgs dargs = dab.build(m, ARG_BYTEARRAY, ARG_OFFSET, ARG_LEN);
        assertThat("DynamicArgs", dargs, notNullValue());
        
        // Test with potential args
        byte buf[] = new byte[222];
        int offset = 3;
        int len = 44;
        String result = (String) dargs.invoke(ssigs, buf, offset, len);
        assertThat("result", result, is("sigByteArray<[222],3,44>"));
        
        // Test with empty potential args
        result = (String) dargs.invoke(ssigs, null, 123, 456);
        assertThat("result", result, is("sigByteArray<<null>,123,456>"));
    }
}
