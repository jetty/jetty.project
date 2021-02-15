//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Tests for LazyList utility class.
 */
public class JavaVersionTest
{
    @Test
    public void testAndroid()
    {
        JavaVersion version = JavaVersion.parse("0.9");
        assertThat(version.toString(), is("0.9"));
        assertThat(version.getPlatform(), is(9));
        assertThat(version.getMajor(), is(0));
        assertThat(version.getMinor(), is(9));
        assertThat(version.getMicro(), is(0));
    }

    @Test
    public void test9()
    {
        JavaVersion version = JavaVersion.parse("9.0.1");
        assertThat(version.toString(), is("9.0.1"));
        assertThat(version.getPlatform(), is(9));
        assertThat(version.getMajor(), is(9));
        assertThat(version.getMinor(), is(0));
        assertThat(version.getMicro(), is(1));
    }

    @Test
    public void test9nano()
    {
        JavaVersion version = JavaVersion.parse("9.0.1.3");
        assertThat(version.toString(), is("9.0.1.3"));
        assertThat(version.getPlatform(), is(9));
        assertThat(version.getMajor(), is(9));
        assertThat(version.getMinor(), is(0));
        assertThat(version.getMicro(), is(1));
    }

    @Test
    public void test9build()
    {
        JavaVersion version = JavaVersion.parse("9.0.1+11");
        assertThat(version.toString(), is("9.0.1+11"));
        assertThat(version.getPlatform(), is(9));
        assertThat(version.getMajor(), is(9));
        assertThat(version.getMinor(), is(0));
        assertThat(version.getMicro(), is(1));
    }

    @Test
    public void test9all()
    {
        JavaVersion version = JavaVersion.parse("9.0.1-ea+11-b01");
        assertThat(version.toString(), is("9.0.1-ea+11-b01"));
        assertThat(version.getPlatform(), is(9));
        assertThat(version.getMajor(), is(9));
        assertThat(version.getMinor(), is(0));
        assertThat(version.getMicro(), is(1));
    }

    @Test
    public void test9yuck()
    {
        JavaVersion version = JavaVersion.parse("9.0.1.2.3-ea+11-b01");
        assertThat(version.toString(), is("9.0.1.2.3-ea+11-b01"));
        assertThat(version.getPlatform(), is(9));
        assertThat(version.getMajor(), is(9));
        assertThat(version.getMinor(), is(0));
        assertThat(version.getMicro(), is(1));
    }

    @Test
    public void test10ea()
    {
        JavaVersion version = JavaVersion.parse("10-ea");
        assertThat(version.toString(), is("10-ea"));
        assertThat(version.getPlatform(), is(10));
        assertThat(version.getMajor(), is(10));
        assertThat(version.getMinor(), is(0));
        assertThat(version.getMicro(), is(0));
    }

    @Test
    public void test8()
    {
        JavaVersion version = JavaVersion.parse("1.8.0_152");
        assertThat(version.toString(), is("1.8.0_152"));
        assertThat(version.getPlatform(), is(8));
        assertThat(version.getMajor(), is(1));
        assertThat(version.getMinor(), is(8));
        assertThat(version.getMicro(), is(0));
    }

    @Test
    public void test8ea()
    {
        JavaVersion version = JavaVersion.parse("1.8.1_03-ea");
        assertThat(version.toString(), is("1.8.1_03-ea"));
        assertThat(version.getPlatform(), is(8));
        assertThat(version.getMajor(), is(1));
        assertThat(version.getMinor(), is(8));
        assertThat(version.getMicro(), is(1));
    }

    @Test
    public void test3eaBuild()
    {
        JavaVersion version = JavaVersion.parse("1.3.1_05-ea-b01");
        assertThat(version.toString(), is("1.3.1_05-ea-b01"));
        assertThat(version.getPlatform(), is(3));
        assertThat(version.getMajor(), is(1));
        assertThat(version.getMinor(), is(3));
        assertThat(version.getMicro(), is(1));
    }

    @Test
    public void testUbuntu()
    {
        JavaVersion version = JavaVersion.parse("9-Ubuntu+0-9b181-4");
        assertThat(version.toString(), is("9-Ubuntu+0-9b181-4"));
        assertThat(version.getPlatform(), is(9));
        assertThat(version.getMajor(), is(9));
        assertThat(version.getMinor(), is(0));
        assertThat(version.getMicro(), is(0));
    }

    @Test
    public void testUbuntu8()
    {
        JavaVersion version = JavaVersion.parse("1.8.0_151-8u151-b12-1~deb9u1-b12");
        assertThat(version.toString(), is("1.8.0_151-8u151-b12-1~deb9u1-b12"));
        assertThat(version.getPlatform(), is(8));
        assertThat(version.getMajor(), is(1));
        assertThat(version.getMinor(), is(8));
        assertThat(version.getMicro(), is(0));
    }
}
