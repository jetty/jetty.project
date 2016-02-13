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

package org.eclipse.jetty.websocket.common.util;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.lang.reflect.Method;

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
        
        public String sigFileStrFin(File foo, String str, boolean fin)
        {
            return String.format("sigFileStrFin<%s,%s,%b>",foo,str,fin);
        }
        
        public String sigByteArray(byte[] buf, int offset, int len)
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
    
    private static final int ROLE_STR = 1;
    private static final int ROLE_BOOL = 2;
    private static final int ROLE_FILE = 3;
    private static final int ROLE_BYTEARRAY = 4;
    private static final int ROLE_OFFSET = 5;
    private static final int ROLE_LEN = 6;
    private static final int ROLE_FIN = 7;

    @Test
    public void testEmptySignature() throws Exception
    {
        DynamicArgs.Builder<String> dab = new DynamicArgs.Builder<>();
        dab.addSignature(new UnorderedSignature()
                .addParam(String.class,ROLE_STR)
                .addParam(File.class,ROLE_FILE)
                .addParam(Boolean.class,ROLE_FIN));

        SampleSignatures ssigs = new SampleSignatures();
        Method m = findMethodByName(ssigs, "sigEmpty");
        DynamicArgs dargs = dab.build(m);
        assertThat("DynamicArgs", dargs, notNullValue());
        dargs.setArgReferences(ROLE_STR,ROLE_BOOL,ROLE_FILE);
        
        // Test with potential args
        Object args[] = dargs.toArgs("Hello", Boolean.TRUE, new File("bar"));
        
        String result = (String)m.invoke(ssigs,args);
        assertThat("result", result, is("sigEmpty<>"));
        
        // Test with empty potential args
        args = dargs.toArgs();
        
        result = (String)m.invoke(ssigs,args);
        assertThat("result", result, is("sigEmpty<>"));
    }
    
    @Test
    public void testStringSignature() throws Exception
    {
        DynamicArgs.Builder dab = new DynamicArgs.Builder();
        dab.addSignature(new UnorderedSignature()
                .addParam(String.class,ROLE_STR)
                .addParam(File.class,ROLE_FILE)
                .addParam(Boolean.class,ROLE_FIN));

        SampleSignatures ssigs = new SampleSignatures();
        Method m = findMethodByName(ssigs, "sigStr");
        DynamicArgs dargs = dab.build(m);
        assertThat("DynamicArgs", dargs, notNullValue());
        dargs.setArgReferences(ROLE_STR,ROLE_BOOL,ROLE_FILE);
        
        // Test with potential args
        Object args[] = dargs.toArgs("Hello", Boolean.TRUE, new File("bar"));
        
        String result = (String)m.invoke(ssigs,args);
        assertThat("result", result, is("sigStr<Hello>"));
        
        // Test with empty potential args
        args = dargs.toArgs();
        
        result = (String)m.invoke(ssigs,args);
        assertThat("result", result, is("sigStr<null>"));
    }
    
    @Test
    public void testByteArraySignature() throws Exception
    {
        DynamicArgs.Builder dab = new DynamicArgs.Builder();
        dab.addSignature(new UnorderedSignature()
                .addParam(String.class,ROLE_STR)
                .addParam(File.class,ROLE_FILE)
                .addParam(Boolean.class,ROLE_FIN));

        SampleSignatures ssigs = new SampleSignatures();
        Method m = findMethodByName(ssigs, "sigByteArray");
        DynamicArgs dargs = dab.build(m);
        assertThat("DynamicArgs", dargs, notNullValue());
        dargs.setArgReferences(ROLE_BYTEARRAY,ROLE_OFFSET,ROLE_LEN);
        
        // Test with potential args
        byte buf[] = new byte[222];
        int offset = 3;
        int len = 44;
        String result = (String)dargs.invoke(m,ssigs,buf,offset,len);
        assertThat("result", result, is("sigByteArray<[222],3,44>"));
        
        // Test with empty potential args
        result = (String)dargs.invoke(m,ssigs,null,123,456);
        assertThat("result", result, is("sigByteArray<<null>,123,456>"));
    }
}
