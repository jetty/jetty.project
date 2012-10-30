//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.util.List;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class HttpCookieParserTest
{
    @Rule
    public final TestTracker tracker = new TestTracker();

    @Test
    public void testParseCookie1() throws Exception
    {
        String value = "wd=deleted; expires=Thu, 01-Jan-1970 00:00:01 GMT; path=/; domain=.domain.com; httponly; secure";
        List<HttpCookie> cookies = HttpCookieParser.parseCookies(value);
        Assert.assertEquals(1, cookies.size());
        HttpCookie cookie = cookies.get(0);
        Assert.assertEquals("wd", cookie.getName());
        Assert.assertEquals("deleted", cookie.getValue());
        Assert.assertEquals(".domain.com", cookie.getDomain());
        Assert.assertEquals("/", cookie.getPath());
        Assert.assertTrue(cookie.isHttpOnly());
        Assert.assertTrue(cookie.isSecure());
        Assert.assertTrue(cookie.isExpired(System.nanoTime()));
    }

    @Test
    public void testParseCookie2() throws Exception
    {
        String value = "wd=deleted; max-Age=0; path=/; domain=.domain.com; httponly; version=3";
        List<HttpCookie> cookies = HttpCookieParser.parseCookies(value);
        Assert.assertEquals(1, cookies.size());
        HttpCookie cookie = cookies.get(0);
        Assert.assertEquals("wd", cookie.getName());
        Assert.assertEquals("deleted", cookie.getValue());
        Assert.assertEquals(".domain.com", cookie.getDomain());
        Assert.assertEquals("/", cookie.getPath());
        Assert.assertTrue(cookie.isHttpOnly());
        Assert.assertEquals(3, cookie.getVersion());
        Assert.assertTrue(cookie.isExpired(System.nanoTime()));
    }
}
