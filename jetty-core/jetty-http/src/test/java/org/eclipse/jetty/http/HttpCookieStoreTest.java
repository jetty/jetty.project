//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpCookieStoreTest
{
    @Test
    public void testRejectCookieForTopDomain()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI uri = URI.create("http://example.com");
        assertFalse(store.add(uri, HttpCookie.build("n", "v").domain("com").build()));
        assertFalse(store.add(uri, HttpCookie.build("n", "v").domain(".com").build()));
    }

    @Test
    public void testRejectExpiredCookie()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI uri = URI.create("http://example.com");
        assertFalse(store.add(uri, HttpCookie.build("n", "v").maxAge(0).build()));
        assertFalse(store.add(uri, HttpCookie.build("n", "v").expires(Instant.now().minusSeconds(1)).build()));
    }

    @Test
    public void testRejectCookieForNonMatchingDomain()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI uri = URI.create("http://example.com");
        assertFalse(store.add(uri, HttpCookie.build("n", "v").domain("sub.example.com").build()));
        assertFalse(store.add(uri, HttpCookie.build("n", "v").domain("foo.com").build()));
    }

    @Test
    public void testAcceptCookieForMatchingDomain()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI uri = URI.create("http://sub.example.com");
        assertTrue(store.add(uri, HttpCookie.build("n", "v").domain("sub.example.com").build()));
        assertTrue(store.add(uri, HttpCookie.build("n", "v").domain("example.com").build()));
    }

    @Test
    public void testAcceptCookieForLocalhost()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI uri = URI.create("http://localhost");
        assertTrue(store.add(uri, HttpCookie.build("n", "v").domain("localhost").build()));
    }

    @Test
    public void testReplaceCookie()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI uri = URI.create("http://example.com");
        assertTrue(store.add(uri, HttpCookie.from("n", "v1")));
        // Replace the cookie with another that has a different value.
        assertTrue(store.add(uri, HttpCookie.from("n", "v2")));
        List<HttpCookie> matches = store.match(uri);
        assertEquals(1, matches.size());
        assertEquals("v2", matches.get(0).getValue());
    }

    @Test
    public void testReplaceCookieWithDomain()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI uri = URI.create("http://example.com");
        assertTrue(store.add(uri, HttpCookie.build("n", "v1").domain("example.com").build()));
        // Replace the cookie with another that has a different value.
        // Domain comparison must be case-insensitive.
        assertTrue(store.add(uri, HttpCookie.build("n", "v2").domain("EXAMPLE.COM").build()));
        List<HttpCookie> matches = store.match(uri);
        assertEquals(1, matches.size());
        assertEquals("v2", matches.get(0).getValue());
    }

    @Test
    public void testReplaceCookieWithPath()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI uri = URI.create("http://example.com/path");
        assertTrue(store.add(uri, HttpCookie.build("n", "v1").path("/path").build()));
        // Replace the cookie with another that has a different value.
        // Path comparison must be case-sensitive.
        assertTrue(store.add(uri, HttpCookie.build("n", "v2").path("/path").build()));
        List<HttpCookie> matches = store.match(uri);
        assertEquals(1, matches.size());
        assertEquals("v2", matches.get(0).getValue());
        // Same path but different case should generate another cookie.
        assertTrue(store.add(uri, HttpCookie.build("n", "v3").path("/PATH").build()));
        matches = store.all();
        assertEquals(2, matches.size());
    }

    @Test
    public void testMatch()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI cookieURI = URI.create("http://example.com");
        assertTrue(store.add(cookieURI, HttpCookie.from("n", "v1")));

        // Same domain with a path must match.
        URI uri = URI.create("http://example.com/path");
        List<HttpCookie> matches = store.match(uri);
        assertEquals(1, matches.size());

        // Subdomain must not match because the cookie was added without
        // Domain attribute, so must be sent only to the origin domain.
        uri = URI.create("http://sub.example.com");
        matches = store.match(uri);
        assertEquals(0, matches.size());

        // Different domain must not match.
        uri = URI.create("http://foo.com");
        matches = store.match(uri);
        assertEquals(0, matches.size());
    }

    @Test
    public void testMatchWithDomain()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI cookieURI = URI.create("http://sub.example.com");
        assertTrue(store.add(cookieURI, HttpCookie.build("n", "v1").domain("example.com").build()));

        // Same domain with a path must match.
        URI uri = URI.create("http://sub.example.com/path");
        List<HttpCookie> matches = store.match(uri);
        assertEquals(1, matches.size());

        // Parent domain must match.
        uri = URI.create("http://example.com");
        matches = store.match(uri);
        assertEquals(1, matches.size());

        // Different subdomain must match due to the Domain attribute.
        uri = URI.create("http://bar.example.com");
        matches = store.match(uri);
        assertEquals(1, matches.size());
    }

    @Test
    public void testMatchManyWithDomain()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI cookieURI = URI.create("http://sub.example.com");
        assertTrue(store.add(cookieURI, HttpCookie.build("n1", "v1").domain("example.com").build()));
        cookieURI = URI.create("http://example.com");
        assertTrue(store.add(cookieURI, HttpCookie.from("n2", "v2")));

        URI uri = URI.create("http://sub.example.com/path");
        List<HttpCookie> matches = store.match(uri);
        assertEquals(1, matches.size());

        // Parent domain must match.
        uri = URI.create("http://example.com");
        matches = store.match(uri);
        assertEquals(2, matches.size());

        // Different subdomain must match due to the Domain attribute.
        uri = URI.create("http://bar.example.com");
        matches = store.match(uri);
        assertEquals(1, matches.size());
    }

    @Test
    public void testMatchManyWithPath()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI cookieURI = URI.create("http://example.com");
        assertTrue(store.add(cookieURI, HttpCookie.build("n1", "v1").path("/path").build()));
        cookieURI = URI.create("http://example.com");
        assertTrue(store.add(cookieURI, HttpCookie.from("n2", "v2")));

        URI uri = URI.create("http://example.com");
        List<HttpCookie> matches = store.match(uri);
        assertEquals(1, matches.size());

        uri = URI.create("http://example.com/");
        matches = store.match(uri);
        assertEquals(1, matches.size());

        uri = URI.create("http://example.com/other");
        matches = store.match(uri);
        assertEquals(1, matches.size());

        uri = URI.create("http://example.com/path");
        matches = store.match(uri);
        assertEquals(2, matches.size());

        uri = URI.create("http://example.com/path/");
        matches = store.match(uri);
        assertEquals(2, matches.size());

        uri = URI.create("http://example.com/path/more");
        matches = store.match(uri);
        assertEquals(2, matches.size());
    }

    @Test
    public void testExpiredCookieDoesNotMatch() throws Exception
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI cookieURI = URI.create("http://example.com");
        long expireSeconds = 1;
        assertTrue(store.add(cookieURI, HttpCookie.build("n1", "v1").maxAge(expireSeconds).build()));

        TimeUnit.SECONDS.sleep(2 * expireSeconds);

        List<HttpCookie> matches = store.match(cookieURI);
        assertEquals(0, matches.size());
    }

    @Test
    public void testRemove()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI cookieURI = URI.create("http://example.com/path");
        assertTrue(store.add(cookieURI, HttpCookie.from("n1", "v1")));

        URI removeURI = URI.create("http://example.com");
        // Cookie value should not matter.
        assertTrue(store.remove(removeURI, HttpCookie.from("n1", "n2")));
        assertFalse(store.remove(removeURI, HttpCookie.from("n1", "n2")));
    }

    @Test
    public void testSecureCookie()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI uri = URI.create("http://example.com");
        // Dumb server sending a secure cookie on clear-text scheme.
        assertFalse(store.add(uri, HttpCookie.build("n1", "v1").secure(true).build()));
        URI secureURI = URI.create("https://example.com");
        assertTrue(store.add(secureURI, HttpCookie.build("n2", "v2").secure(true).build()));
        assertTrue(store.add(secureURI, HttpCookie.from("n3", "v3")));

        List<HttpCookie> matches = store.match(uri);
        assertEquals(0, matches.size());

        matches = store.match(secureURI);
        assertEquals(2, matches.size());
    }

    @Test
    public void testClear()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI cookieURI = URI.create("http://example.com");
        assertTrue(store.add(cookieURI, HttpCookie.from("n1", "v1")));

        assertTrue(store.clear());
        assertFalse(store.clear());
    }

    @Test
    public void testDifferentScheme()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI cookieURI = URI.create("http://example.com");
        assertTrue(store.add(cookieURI, HttpCookie.from("n1", "v1")));

        URI matchURI = URI.create(cookieURI.toString().replaceFirst("^http", "ws"));
        List<HttpCookie> matches = store.match(matchURI);
        assertEquals(1, matches.size());

        cookieURI = URI.create("wss://example.com");
        assertTrue(store.add(cookieURI, HttpCookie.from("n2", "v2")));

        matchURI = URI.create("https://example.com");
        matches = store.match(matchURI);
        assertEquals(1, matches.size());
        assertEquals("n2", matches.get(0).getName());
    }
}
