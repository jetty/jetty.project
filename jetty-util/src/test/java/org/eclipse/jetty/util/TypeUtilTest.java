//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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


import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assume.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

public class TypeUtilTest
{
    @Test
    public void convertHexDigitTest()
    {   
        assertEquals((byte)0,TypeUtil.convertHexDigit((byte)'0'));
        assertEquals((byte)9,TypeUtil.convertHexDigit((byte)'9'));
        assertEquals((byte)10,TypeUtil.convertHexDigit((byte)'a'));
        assertEquals((byte)10,TypeUtil.convertHexDigit((byte)'A'));
        assertEquals((byte)15,TypeUtil.convertHexDigit((byte)'f'));
        assertEquals((byte)15,TypeUtil.convertHexDigit((byte)'F'));
        
        assertEquals((int)0,TypeUtil.convertHexDigit((int)'0'));
        assertEquals((int)9,TypeUtil.convertHexDigit((int)'9'));
        assertEquals((int)10,TypeUtil.convertHexDigit((int)'a'));
        assertEquals((int)10,TypeUtil.convertHexDigit((int)'A'));
        assertEquals((int)15,TypeUtil.convertHexDigit((int)'f'));
        assertEquals((int)15,TypeUtil.convertHexDigit((int)'F'));
    }
    
    @Test
    public void testToHexInt() throws Exception
    {
        StringBuilder b = new StringBuilder();
        
        b.setLength(0);
        TypeUtil.toHex((int)0,b);
        assertEquals("00000000",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(Integer.MAX_VALUE,b);
        assertEquals("7FFFFFFF",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(Integer.MIN_VALUE,b);
        assertEquals("80000000",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(0x12345678,b);
        assertEquals("12345678",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(0x9abcdef0,b);
        assertEquals("9ABCDEF0",b.toString());
    }

    @Test
    public void testToHexLong() throws Exception
    {
        StringBuilder b = new StringBuilder();
        
        b.setLength(0);
        TypeUtil.toHex((long)0,b);
        assertEquals("0000000000000000",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(Long.MAX_VALUE,b);
        assertEquals("7FFFFFFFFFFFFFFF",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(Long.MIN_VALUE,b);
        assertEquals("8000000000000000",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(0x123456789abcdef0L,b);
        assertEquals("123456789ABCDEF0",b.toString());
    }

    @Test
    public void testIsTrue() throws Exception
    {
        assertTrue(TypeUtil.isTrue(Boolean.TRUE));
        assertTrue(TypeUtil.isTrue(true));
        assertTrue(TypeUtil.isTrue("true"));
        assertTrue(TypeUtil.isTrue(new Object(){@Override public String toString(){return "true";}}));
        
        assertFalse(TypeUtil.isTrue(Boolean.FALSE));
        assertFalse(TypeUtil.isTrue(false));
        assertFalse(TypeUtil.isTrue("false"));
        assertFalse(TypeUtil.isTrue("blargle"));
        assertFalse(TypeUtil.isTrue(new Object(){@Override public String toString(){return "false";}}));
    }

    @Test
    public void testIsFalse() throws Exception
    {
        assertTrue(TypeUtil.isFalse(Boolean.FALSE));
        assertTrue(TypeUtil.isFalse(false));
        assertTrue(TypeUtil.isFalse("false"));
        assertTrue(TypeUtil.isFalse(new Object(){@Override public String toString(){return "false";}}));
        
        assertFalse(TypeUtil.isFalse(Boolean.TRUE));
        assertFalse(TypeUtil.isFalse(true));
        assertFalse(TypeUtil.isFalse("true"));
        assertFalse(TypeUtil.isFalse("blargle"));
        assertFalse(TypeUtil.isFalse(new Object(){@Override public String toString(){return "true";}}));
    }
    
    @Test
    public void testGetLocationOfClass() throws Exception
    {
        String mavenRepoPathProperty = System.getProperty( "mavenRepoPath");
        assumeTrue(mavenRepoPathProperty != null);
        Path mavenRepoPath = Paths.get( mavenRepoPathProperty );

        String mavenRepo = mavenRepoPath.toFile().getPath().replaceAll("\\\\", "/");

        // Classes from maven dependencies
        assertThat(TypeUtil.getLocationOfClass(Assertions.class).toASCIIString(),containsString(mavenRepo));
        
        // Class from project dependencies
        assertThat(TypeUtil.getLocationOfClass(TypeUtil.class).toASCIIString(),containsString("/classes/"));
    }

    @Test
    @DisabledOnJre(JRE.JAVA_8)
    public void testGetLocation_JvmCore_JPMS()
    {
        // Class from JVM core
        String expectedJavaBase = "/java.base/";
        assertThat(TypeUtil.getLocationOfClass(String.class).toASCIIString(),containsString(expectedJavaBase));
    }

    @Test
    @EnabledOnJre(JRE.JAVA_8)
    public void testGetLocation_JvmCore_Java8RT()
    {
        // Class from JVM core
        String expectedJavaBase = "/rt.jar";
        assertThat(TypeUtil.getLocationOfClass(String.class).toASCIIString(),containsString(expectedJavaBase));
    }
}
