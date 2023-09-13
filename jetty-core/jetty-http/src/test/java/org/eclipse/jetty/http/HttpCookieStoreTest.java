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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpCookieStoreTest
{
    @Test
    public void testRejectCookieForNoDomain()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI uri = URI.create("/path");
        assertFalse(store.add(uri, HttpCookie.from("n", "v")));
    }

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
        assertEquals(2, store.all().size());
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
        assertEquals(1, store.all().size());
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
        assertEquals(1, store.all().size());
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
        assertEquals(1, store.all().size());
        List<HttpCookie> matches = store.match(uri);
        assertEquals(1, matches.size());
        assertEquals("v2", matches.get(0).getValue());
        // Same path but different case should generate another cookie.
        assertTrue(store.add(uri, HttpCookie.build("n", "v3").path("/PATH").build()));
        assertEquals(2, store.all().size());
    }

    @Test
    public void testMatchNoDomain()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI cookieURI = URI.create("http://example.com");
        assertTrue(store.add(cookieURI, HttpCookie.from("n", "v1")));

        // No domain, no match.
        URI uri = URI.create("/path");
        List<HttpCookie> matches = store.match(uri);
        assertEquals(0, matches.size());
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
    public void testMatchWithEscapedURIPath()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI cookieURI = URI.create("http://example.com/foo%2Fbar/baz");
        assertTrue(store.add(cookieURI, HttpCookie.build("n1", "v1").build()));

        URI uri = URI.create("http://example.com/foo");
        List<HttpCookie> matches = store.match(uri);
        assertEquals(0, matches.size());

        uri = URI.create("http://example.com/foo/");
        matches = store.match(uri);
        assertEquals(0, matches.size());

        uri = URI.create("http://example.com/foo/bar");
        matches = store.match(uri);
        assertEquals(0, matches.size());

        uri = URI.create("http://example.com/foo/bar/");
        matches = store.match(uri);
        assertEquals(0, matches.size());

        uri = URI.create("http://example.com/foo/bar/baz");
        matches = store.match(uri);
        assertEquals(0, matches.size());

        uri = URI.create("http://example.com/foo%2Fbar");
        matches = store.match(uri);
        assertEquals(1, matches.size());

        uri = URI.create("http://example.com/foo%2Fbar/");
        matches = store.match(uri);
        assertEquals(1, matches.size());

        uri = URI.create("http://example.com/foo%2Fbar/qux");
        matches = store.match(uri);
        assertEquals(1, matches.size());
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
        // Note that the URI path is "/path/", but the cookie path "/remove" is used.
        HttpCookieStore store = new HttpCookieStore.Default();
        URI cookieURI = URI.create("http://example.com/path/");
        assertTrue(store.add(cookieURI, HttpCookie.build("n1", "v1").path("/remove").build()));

        // Path does not match.
        assertFalse(store.remove(URI.create("http://example.com"), HttpCookie.from("n1", "v2")));
        assertFalse(store.remove(URI.create("http://example.com/path/"), HttpCookie.from("n1", "v2")));
        assertFalse(store.remove(URI.create("http://example.com"), HttpCookie.build("n1", "v2").path("/path").build()));
        assertFalse(store.remove(URI.create("http://example.com/remove/"), HttpCookie.build("n1", "v2").path("/path").build()));

        // Domain does not match.
        assertFalse(store.remove(URI.create("http://domain.com/remove/"), HttpCookie.build("n1", "v2").build()));

        // Cookie value should not matter.
        // The URI path must be "/remove/" (end with slash) because URI paths
        // are chopped to the parent directory while cookie paths are not chopped.
        URI removeURI = URI.create("http://example.com/remove/");
        assertTrue(store.remove(removeURI, HttpCookie.from("n1", "v2")));
        // Try again, should not be there.
        assertFalse(store.remove(removeURI, HttpCookie.from("n1", "v2")));
    }

    @Test
    public void testRemoveWithSubDomains()
    {
        // Subdomains can set cookies on the parent domain.
        HttpCookieStore store = new HttpCookieStore.Default();
        URI cookieURI = URI.create("http://sub.example.com/path");
        assertTrue(store.add(cookieURI, HttpCookie.build("n1", "v1").domain("example.com").build()));

        // Cannot remove the cookie from the parent domain.
        assertFalse(store.remove(URI.create("http://example.com/path"), HttpCookie.from("n1", "v2")));
        assertFalse(store.remove(URI.create("http://example.com/path"), HttpCookie.build("n1", "v2").domain("example.com").build()));
        assertFalse(store.remove(URI.create("http://example.com/path"), HttpCookie.build("n1", "v2").domain("sub.example.com").build()));

        // Cannot remove the cookie from a sibling domain.
        assertFalse(store.remove(URI.create("http://foo.example.com/path"), HttpCookie.from("n1", "v2")));
        assertFalse(store.remove(URI.create("http://foo.example.com/path"), HttpCookie.build("n1", "v2").domain("sub.example.com").build()));

        // Remove the cookie.
        assertTrue(store.remove(cookieURI, HttpCookie.from("n1", "v2")));
    }

    @Test
    public void testSecureCookie()
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI uri = URI.create("http://example.com");
        // A secure cookie on clear-text scheme.
        assertTrue(store.add(uri, HttpCookie.build("n1", "v1").secure(true).build()));

        URI secureURI = URI.create("https://example.com");
        assertTrue(store.add(secureURI, HttpCookie.build("n2", "v2").secure(true).build()));
        assertTrue(store.add(secureURI, HttpCookie.from("n3", "v3")));

        List<HttpCookie> matches = store.match(uri);
        assertEquals(1, matches.size());
        assertEquals("n3", matches.get(0).getName());

        matches = store.match(secureURI);
        assertEquals(3, matches.size());
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

        // Cookie matching does not depend on the scheme,
        // not even with regard to non-secure vs secure.
        matchURI = URI.create("https://example.com");
        matches = store.match(matchURI);
        assertEquals(2, matches.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost.", "domain.com."})
    public void testCookieDomainEndingWithDotIsIgnored(String cookieDomain)
    {
        HttpCookieStore store = new HttpCookieStore.Default();
        URI cookieURI = URI.create("http://example.com");
        assertTrue(store.add(cookieURI, HttpCookie.build("n1", "v1").domain(cookieDomain).build()));

        List<HttpCookie> matches = store.match(cookieURI);
        assertEquals(1, matches.size());
    }
}
