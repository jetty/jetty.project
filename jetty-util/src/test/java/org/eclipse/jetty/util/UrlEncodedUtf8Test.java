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

package org.eclipse.jetty.util;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Assert;
import org.junit.Test;

public class UrlEncodedUtf8Test
{

    static final Logger LOG=Log.getLogger(UrlEncodedUtf8Test.class);

    
    @Test
    public void testIncompleteSequestAtTheEnd() throws Exception
    {
        byte[] bytes= { 97, 98, 61, 99, -50 };
        String test=new String(bytes,StandardCharsets.UTF_8);
        String expected = "c"+Utf8Appendable.REPLACEMENT;

        fromString(test,test,"ab",expected,false);
        fromInputStream(test,bytes,"ab",expected,false);
    }

    @Test
    public void testIncompleteSequestAtTheEnd2() throws Exception
    {
        byte[] bytes={ 97, 98, 61, -50 };
        String test=new String(bytes,StandardCharsets.UTF_8);
        String expected = ""+Utf8Appendable.REPLACEMENT;

        fromString(test,test,"ab",expected,false);
        fromInputStream(test,bytes,"ab",expected,false);
        
    }
    
    @Test
    public void testIncompleteSequestInName() throws Exception
    {
        byte[] bytes= { 101, -50, 61, 102, 103, 38, 97, 98, 61, 99, 100 };
        String test=new String(bytes,StandardCharsets.UTF_8);
        String name = "e"+Utf8Appendable.REPLACEMENT;
        String value = "fg";

        fromString(test,test,name,value,false);
        fromInputStream(test,bytes,name,value,false);
    }
    
    @Test
    public void testIncompleteSequestInValue() throws Exception
    {
        byte[] bytes= { 101, 102, 61, 103, -50, 38, 97, 98, 61, 99, 100 };
        String test=new String(bytes,StandardCharsets.UTF_8);
        String name = "ef";
        String value = "g"+Utf8Appendable.REPLACEMENT;

        fromString(test,test,name,value,false);
        fromInputStream(test,bytes,name,value,false);
    }

    @Test
    public void testCorrectUnicode() throws Exception
    {
        String chars="a=%u0061";
        byte[] bytes= chars.getBytes(StandardCharsets.UTF_8);
        String test=new String(bytes,StandardCharsets.UTF_8);
        String name = "a";
        String value = "a";

        fromString(test,test,name,value,false);
        fromInputStream(test,bytes,name,value,false);
    }
    
    @Test
    public void testIncompleteUnicode() throws Exception
    {
        String chars="a=%u0";
        byte[] bytes= chars.getBytes(StandardCharsets.UTF_8);
        String test=new String(bytes,StandardCharsets.UTF_8);
        String name = "a";
        String value = ""+Utf8Appendable.REPLACEMENT;

        fromString(test,test,name,value,false);
        fromInputStream(test,bytes,name,value,false);
    }
    
    @Test
    public void testIncompletePercent() throws Exception
    {
        String chars="a=%A";
        byte[] bytes= chars.getBytes(StandardCharsets.UTF_8);
        String test=new String(bytes,StandardCharsets.UTF_8);
        String name = "a";
        String value = ""+Utf8Appendable.REPLACEMENT;

        fromString(test,test,name,value,false);
        fromInputStream(test,bytes,name,value,false);
    }

    static void fromString(String test,String s,String field,String expected,boolean thrown) throws Exception
    {
        MultiMap<String> values=new MultiMap<>();
        try
        {
            UrlEncoded.decodeUtf8To(s, 0, s.length(), values);
            if (thrown)
                Assert.fail();
            Assert.assertEquals(test, expected, values.getString(field));
        }
        catch (Exception e)
        {
            if (!thrown)
                throw e;
            LOG.ignore(e);
        }
    }

    static void fromInputStream(String test, byte[] b,String field, String expected,boolean thrown) throws Exception
    {
        InputStream is=new ByteArrayInputStream(b);
        MultiMap<String> values=new MultiMap<>();
        try
        {
            UrlEncoded.decodeUtf8To(is, values, 1000000,-1);
            if (thrown)
                Assert.fail();
            Assert.assertEquals(test, expected, values.getString(field));
        }
        catch (Exception e)
        {
            if (!thrown)
                throw e;
            LOG.ignore(e);
        }
    }
    
}
