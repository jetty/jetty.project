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

import org.eclipse.jetty.client.api.CookieStore;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.http.HttpCookie;
import org.junit.Assert;
import org.junit.Test;

public class HttpCookieStoreTest
{
    private HttpClient client = new HttpClient();

    @Test
    public void testCookieStoredIsRetrieved() throws Exception
    {
        CookieStore cookies = new HttpCookieStore();
        Destination destination = new HttpDestination(client, "http", "localhost", 80);
        Assert.assertTrue(cookies.addCookie(destination, new HttpCookie("a", "1")));

        List<HttpCookie> result = cookies.findCookies(destination, "/");
        Assert.assertNotNull(result);
        Assert.assertEquals(1, result.size());
        HttpCookie cookie = result.get(0);
        Assert.assertEquals("a", cookie.getName());
        Assert.assertEquals("1", cookie.getValue());
    }

    @Test
    public void testCookieWithChildDomainIsStored() throws Exception
    {
        CookieStore cookies = new HttpCookieStore();
        Destination destination = new HttpDestination(client, "http", "localhost", 80);
        Assert.assertTrue(cookies.addCookie(destination, new HttpCookie("a", "1", "child.localhost", "/")));

        List<HttpCookie> result = cookies.findCookies(destination, "/");
        Assert.assertNotNull(result);
        Assert.assertEquals(1, result.size());
        HttpCookie cookie = result.get(0);
        Assert.assertEquals("a", cookie.getName());
        Assert.assertEquals("1", cookie.getValue());
    }

    @Test
    public void testCookieWithParentDomainIsNotStored() throws Exception
    {
        CookieStore cookies = new HttpCookieStore();
        Destination destination = new HttpDestination(client, "http", "child.localhost", 80);
        Assert.assertFalse(cookies.addCookie(destination, new HttpCookie("a", "1", "localhost", "/")));
    }

    @Test
    public void testCookieStoredWithPathIsNotRetrievedWithRootPath() throws Exception
    {
        CookieStore cookies = new HttpCookieStore();
        Destination destination = new HttpDestination(client, "http", "localhost", 80);
        Assert.assertTrue(cookies.addCookie(destination, new HttpCookie("a", "1", null, "/path")));

        List<HttpCookie> result = cookies.findCookies(destination, "/");
        Assert.assertNotNull(result);
        Assert.assertEquals(0, result.size());
    }

    @Test
    public void testCookieStoredWithRootPathIsRetrievedWithPath() throws Exception
    {
        CookieStore cookies = new HttpCookieStore();
        Destination destination = new HttpDestination(client, "http", "localhost", 80);
        Assert.assertTrue(cookies.addCookie(destination, new HttpCookie("a", "1", null, "/")));

        List<HttpCookie> result = cookies.findCookies(destination, "/path");
        Assert.assertNotNull(result);
        Assert.assertEquals(1, result.size());
        HttpCookie cookie = result.get(0);
        Assert.assertEquals("a", cookie.getName());
        Assert.assertEquals("1", cookie.getValue());
    }

    @Test
    public void testCookieStoredWithParentDomainIsRetrievedWithChildDomain() throws Exception
    {
        CookieStore cookies = new HttpCookieStore();
        Destination parentDestination = new HttpDestination(client, "http", "localhost.org", 80);
        Assert.assertTrue(cookies.addCookie(parentDestination, new HttpCookie("a", "1", null, "/")));

        Destination childDestination = new HttpDestination(client, "http", "child.localhost.org", 80);
        Assert.assertTrue(cookies.addCookie(childDestination, new HttpCookie("b", "2", null, "/")));

        Destination grandChildDestination = new HttpDestination(client, "http", "grand.child.localhost.org", 80);
        Assert.assertTrue(cookies.addCookie(grandChildDestination, new HttpCookie("b", "2", null, "/")));

        List<HttpCookie> result = cookies.findCookies(grandChildDestination, "/path");
        Assert.assertNotNull(result);
        Assert.assertEquals(2, result.size());
    }

    @Test
    public void testExpiredCookieIsNotRetrieved() throws Exception
    {
        CookieStore cookies = new HttpCookieStore();
        Destination destination = new HttpDestination(client, "http", "localhost.org", 80);
        Assert.assertTrue(cookies.addCookie(destination, new HttpCookie("a", "1", null, "/", 0, false, false)));

        List<HttpCookie> result = cookies.findCookies(destination, "/");
        Assert.assertNotNull(result);
        Assert.assertEquals(0, result.size());
    }

    @Test
    public void testSecureCookieIsNotRetrieved() throws Exception
    {
        CookieStore cookies = new HttpCookieStore();
        Destination destination = new HttpDestination(client, "http", "localhost.org", 80);
        Assert.assertTrue(cookies.addCookie(destination, new HttpCookie("a", "1", null, "/", -1, false, true)));

        List<HttpCookie> result = cookies.findCookies(destination, "/");
        Assert.assertNotNull(result);
        Assert.assertEquals(0, result.size());
    }

    @Test
    public void testCookiesAreCleared() throws Exception
    {
        CookieStore cookies = new HttpCookieStore();
        Destination destination = new HttpDestination(client, "http", "localhost.org", 80);
        Assert.assertTrue(cookies.addCookie(destination, new HttpCookie("a", "1", null, "/", -1, false, true)));

        cookies.clear();
        Assert.assertEquals(0, cookies.findCookies(destination, "/").size());
    }
}
