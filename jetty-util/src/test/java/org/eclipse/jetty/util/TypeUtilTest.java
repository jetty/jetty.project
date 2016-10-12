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


import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class TypeUtilTest
{
    @Test
    public void convertHexDigitTest()
    {   
        Assert.assertEquals((byte)0,TypeUtil.convertHexDigit((byte)'0'));
        Assert.assertEquals((byte)9,TypeUtil.convertHexDigit((byte)'9'));
        Assert.assertEquals((byte)10,TypeUtil.convertHexDigit((byte)'a'));
        Assert.assertEquals((byte)10,TypeUtil.convertHexDigit((byte)'A'));
        Assert.assertEquals((byte)15,TypeUtil.convertHexDigit((byte)'f'));
        Assert.assertEquals((byte)15,TypeUtil.convertHexDigit((byte)'F'));
        
        Assert.assertEquals((int)0,TypeUtil.convertHexDigit((int)'0'));
        Assert.assertEquals((int)9,TypeUtil.convertHexDigit((int)'9'));
        Assert.assertEquals((int)10,TypeUtil.convertHexDigit((int)'a'));
        Assert.assertEquals((int)10,TypeUtil.convertHexDigit((int)'A'));
        Assert.assertEquals((int)15,TypeUtil.convertHexDigit((int)'f'));
        Assert.assertEquals((int)15,TypeUtil.convertHexDigit((int)'F'));
    }
    
    @Test
    public void testToHexInt() throws Exception
    {
        StringBuilder b = new StringBuilder();
        
        b.setLength(0);
        TypeUtil.toHex((int)0,b);
        Assert.assertEquals("00000000",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(Integer.MAX_VALUE,b);
        Assert.assertEquals("7FFFFFFF",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(Integer.MIN_VALUE,b);
        Assert.assertEquals("80000000",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(0x12345678,b);
        Assert.assertEquals("12345678",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(0x9abcdef0,b);
        Assert.assertEquals("9ABCDEF0",b.toString());
    }

    @Test
    public void testToHexLong() throws Exception
    {
        StringBuilder b = new StringBuilder();
        
        b.setLength(0);
        TypeUtil.toHex((long)0,b);
        Assert.assertEquals("0000000000000000",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(Long.MAX_VALUE,b);
        Assert.assertEquals("7FFFFFFFFFFFFFFF",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(Long.MIN_VALUE,b);
        Assert.assertEquals("8000000000000000",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(0x123456789abcdef0L,b);
        Assert.assertEquals("123456789ABCDEF0",b.toString());
    }

    @Test
    public void testIsTrue() throws Exception
    {
        Assert.assertTrue(TypeUtil.isTrue(Boolean.TRUE));
        Assert.assertTrue(TypeUtil.isTrue(true));
        Assert.assertTrue(TypeUtil.isTrue("true"));
        Assert.assertTrue(TypeUtil.isTrue(new Object(){@Override public String toString(){return "true";}}));
        
        Assert.assertFalse(TypeUtil.isTrue(Boolean.FALSE));
        Assert.assertFalse(TypeUtil.isTrue(false));
        Assert.assertFalse(TypeUtil.isTrue("false"));
        Assert.assertFalse(TypeUtil.isTrue("blargle"));
        Assert.assertFalse(TypeUtil.isTrue(new Object(){@Override public String toString(){return "false";}}));
    }

    @Test
    public void testIsFalse() throws Exception
    {
        Assert.assertTrue(TypeUtil.isFalse(Boolean.FALSE));
        Assert.assertTrue(TypeUtil.isFalse(false));
        Assert.assertTrue(TypeUtil.isFalse("false"));
        Assert.assertTrue(TypeUtil.isFalse(new Object(){@Override public String toString(){return "false";}}));
        
        Assert.assertFalse(TypeUtil.isFalse(Boolean.TRUE));
        Assert.assertFalse(TypeUtil.isFalse(true));
        Assert.assertFalse(TypeUtil.isFalse("true"));
        Assert.assertFalse(TypeUtil.isFalse("blargle"));
        Assert.assertFalse(TypeUtil.isFalse(new Object(){@Override public String toString(){return "true";}}));
    }
    
    @Test
    public void testLoadedFrom() throws Exception
    {
        Assert.assertThat(TypeUtil.getLoadedFrom(String.class).toString(),Matchers.containsString("/rt.jar"));
        Assert.assertThat(TypeUtil.getLoadedFrom(Assert.class).toString(),Matchers.containsString(".jar"));
        Assert.assertThat(TypeUtil.getLoadedFrom(TypeUtil.class).toString(),Matchers.containsString("/classes/"));
    }
}
