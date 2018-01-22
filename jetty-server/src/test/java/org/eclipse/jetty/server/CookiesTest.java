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

package org.eclipse.jetty.server;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import javax.servlet.http.Cookie;

import org.eclipse.jetty.http.CookieCompliance;
import org.hamcrest.Matchers;
import org.junit.Ignore;
import org.junit.Test;

public class CookiesTest
{
    @Test
    public void testEmpty()
    {
        Cookies cutter = new Cookies();
        assertThat(cutter.getCookies().length,is(0));
        cutter.reset();
        assertThat(cutter.getCookies().length,is(0));
    }

    @Test
    public void testCacheHit()
    {
        Cookies cutter = new Cookies();
        cutter.addCookieField("nameA0=A0; nameA1=A1");
        cutter.addCookieField("nameB0=B0; nameB1=B1");

        Cookie[] cookiesX = cutter.getCookies();
        assertThat(cookiesX.length,is(4));
        assertThat(cookiesX[0].getName(),is("nameA0"));
        assertThat(cookiesX[3].getValue(),is("B1"));

        cutter.reset();
        cutter.addCookieField("nameA0=A0; nameA1=A1");
        cutter.addCookieField("nameB0=B0; nameB1=B1");
        Cookie[] cookiesY = cutter.getCookies();
        assertThat(cookiesY.length,is(4));
        assertThat(cookiesY[0].getName(),is("nameA0"));
        assertThat(cookiesY[3].getValue(),is("B1"));

        assertThat(cookiesX, Matchers.sameInstance(cookiesY));
    }

    @Test
    public void testCacheMiss()
    {
        Cookies cutter = new Cookies();
        cutter.addCookieField("nameA0=A0; nameA1=A1");
        cutter.addCookieField("nameB0=B0; nameB1=B1");

        Cookie[] cookiesX = cutter.getCookies();
        assertThat(cookiesX.length,is(4));
        assertThat(cookiesX[0].getName(),is("nameA0"));
        assertThat(cookiesX[3].getValue(),is("B1"));

        cutter.reset();
        cutter.addCookieField("nameA0=A0; nameA1=A1");
        cutter.addCookieField("nameC0=C0; nameC1=C1");
        Cookie[] cookiesY = cutter.getCookies();
        assertThat(cookiesY.length,is(4));
        assertThat(cookiesY[0].getName(),is("nameA0"));
        assertThat(cookiesY[3].getValue(),is("C1"));

        assertThat(cookiesX, Matchers.not(Matchers.sameInstance(cookiesY)));
    }

    @Test
    public void testCacheUnder()
    {
        Cookies cutter = new Cookies();
        cutter.addCookieField("nameA0=A0; nameA1=A1");
        cutter.addCookieField("nameB0=B0; nameB1=B1");

        Cookie[] cookiesX = cutter.getCookies();
        assertThat(cookiesX.length,is(4));
        assertThat(cookiesX[0].getName(),is("nameA0"));
        assertThat(cookiesX[3].getValue(),is("B1"));

        cutter.reset();
        cutter.addCookieField("nameA0=A0; nameA1=A1");
        Cookie[] cookiesY = cutter.getCookies();
        assertThat(cookiesY.length,is(2));
        assertThat(cookiesY[0].getName(),is("nameA0"));
        assertThat(cookiesY[1].getValue(),is("A1"));

        assertThat(cookiesX, Matchers.not(Matchers.sameInstance(cookiesY)));
    }

    @Test
    public void testCacheOver()
    {
        Cookies cutter = new Cookies();
        cutter.addCookieField("nameA0=A0; nameA1=A1");
        cutter.addCookieField("nameB0=B0; nameB1=B1");

        Cookie[] cookiesX = cutter.getCookies();
        assertThat(cookiesX.length, is(4));
        assertThat(cookiesX[0].getName(), is("nameA0"));
        assertThat(cookiesX[3].getValue(), is("B1"));

        cutter.reset();
        cutter.addCookieField("nameA0=A0; nameA1=A1");
        cutter.addCookieField("nameB0=B0; nameB1=B1");
        cutter.addCookieField("nameC0=C0; nameC1=C1");
        Cookie[] cookiesY = cutter.getCookies();
        assertThat(cookiesY.length, is(6));
        assertThat(cookiesY[0].getName(), is("nameA0"));
        assertThat(cookiesY[5].getValue(), is("C1"));

        assertThat(cookiesX, Matchers.not(Matchers.sameInstance(cookiesY)));
    }

    @Test
    public void testCacheReset()
    {
        Cookies cutter = new Cookies();
        cutter.addCookieField("nameA0=A0; nameA1=A1");
        cutter.addCookieField("nameB0=B0; nameB1=B1");

        Cookie[] cookiesX = cutter.getCookies();
        assertThat(cookiesX.length,is(4));
        assertThat(cookiesX[0].getName(),is("nameA0"));
        assertThat(cookiesX[3].getValue(),is("B1"));

        cutter.reset();
        assertThat(cutter.getCookies().length,is(0));

        cutter.addCookieField("nameA0=A0; nameA1=A1");
        cutter.addCookieField("nameB0=B0; nameB1=B1");
        Cookie[] cookiesY = cutter.getCookies();
        assertThat(cookiesY.length,is(4));
        assertThat(cookiesY[0].getName(),is("nameA0"));
        assertThat(cookiesY[3].getValue(),is("B1"));

        assertThat(cookiesX, Matchers.not(Matchers.sameInstance(cookiesY)));
    }

    @Test
    public void testSet()
    {
        Cookies cutter = new Cookies();
        cutter.setCookies(new Cookie[]
        {
            new Cookie("nameA0","A0"),
            new Cookie("nameA1","A1"),
            new Cookie("nameB0","B0"),
            new Cookie("nameB1","B1"),
        });

        Cookie[] cookiesX = cutter.getCookies();
        assertThat(cookiesX.length,is(4));
        assertThat(cookiesX[0].getName(),is("nameA0"));
        assertThat(cookiesX[3].getValue(),is("B1"));
        
        cutter.reset();

        assertThat(cutter.getCookies().length,is(0));
    }

}
