//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.util.List;
import java.util.ListIterator;

import org.eclipse.jetty.http.CookieCache;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

public class CookieCacheTest
{
    CookieCache _cache;
    HttpFields.Mutable _fields;

    @BeforeEach
    public void prepare() throws Exception
    {
        _cache = new CookieCache();
        _fields = HttpFields.build();
    }

    @Test
    public void testNoFields() throws Exception
    {
        List<HttpCookie> cookies = _cache.getCookies(_fields.asImmutable());
        assertThat(cookies, empty());
        cookies = _cache.getCookies(_fields.asImmutable());
        assertThat(cookies, empty());
    }

    @Test
    public void testNoCookies() throws Exception
    {
        _fields.put("Some", "Header");
        _fields.put("Other", "header");
        List<HttpCookie> cookies = _cache.getCookies(_fields.asImmutable());
        assertThat(cookies, empty());
        cookies = _cache.getCookies(_fields.asImmutable());
        assertThat(cookies, empty());
    }

    @Test
    public void testOneCookie() throws Exception
    {
        _fields.put("Some", "Header");
        _fields.put("Cookie", "name=value");
        _fields.put("Other", "header");
        List<HttpCookie> cookies0 = _cache.getCookies(_fields.asImmutable());
        assertThat(cookies0, hasSize(1));
        HttpCookie cookie = cookies0.get(0);
        assertThat(cookie.getName(), is("name"));
        assertThat(cookie.getValue(), is("value"));

        List<HttpCookie> cookies1 = _cache.getCookies(_fields.asImmutable());
        assertThat(cookies1, hasSize(1));
        assertThat(cookies1, sameInstance(cookies0));
    }

    @Test
    public void testDeleteCookie() throws Exception
    {
        _fields.put("Some", "Header");
        _fields.put("Cookie", "name=value");
        _fields.put("Other", "header");
        List<HttpCookie> cookies0 = _cache.getCookies(_fields.asImmutable());
        assertThat(cookies0, hasSize(1));
        HttpCookie cookie = cookies0.get(0);
        assertThat(cookie.getName(), is("name"));
        assertThat(cookie.getValue(), is("value"));

        _fields.remove(HttpHeader.COOKIE);
        List<HttpCookie> cookies1 = _cache.getCookies(_fields.asImmutable());
        assertThat(cookies1, empty());
        assertThat(cookies1, not(sameInstance(cookies0)));
    }

    @Test
    public void testChangedCookie() throws Exception
    {
        _fields.put("Some", "Header");
        _fields.put("Cookie", "name=value");
        _fields.put("Other", "header");
        List<HttpCookie> cookies0 = _cache.getCookies(_fields.asImmutable());
        assertThat(cookies0, hasSize(1));
        HttpCookie cookie = cookies0.get(0);
        assertThat(cookie.getName(), is("name"));
        assertThat(cookie.getValue(), is("value"));

        _fields.put(HttpHeader.COOKIE, "name=different");
        List<HttpCookie> cookies1 = _cache.getCookies(_fields.asImmutable());
        assertThat(cookies1, hasSize(1));
        assertThat(cookies1, not(sameInstance(cookies0)));
        cookie = cookies1.get(0);
        assertThat(cookie.getName(), is("name"));
        assertThat(cookie.getValue(), is("different"));
    }

    @Test
    public void testChangedFirstCookie() throws Exception
    {
        _fields.put("Some", "Header");
        _fields.put("Cookie", "name=value");
        _fields.put("Other", "header");
        _fields.add(HttpHeader.COOKIE, "other=different");
        List<HttpCookie> cookies0 = _cache.getCookies(_fields.asImmutable());
        assertThat(cookies0, hasSize(2));
        HttpCookie cookie = cookies0.get(0);
        assertThat(cookie.getName(), is("name"));
        assertThat(cookie.getValue(), is("value"));
        cookie = cookies0.get(1);
        assertThat(cookie.getName(), is("other"));
        assertThat(cookie.getValue(), is("different"));

        ListIterator<HttpField> i = _fields.listIterator();
        i.next();
        i.next();
        i.set(new HttpField("Cookie", "Name=Changed"));

        List<HttpCookie> cookies1 = _cache.getCookies(_fields.asImmutable());
        assertThat(cookies1, hasSize(2));
        assertThat(cookies1, not(sameInstance(cookies0)));
        cookie = cookies1.get(0);
        assertThat(cookie.getName(), is("Name"));
        assertThat(cookie.getValue(), is("Changed"));
        cookie = cookies0.get(1);
        assertThat(cookie.getName(), is("other"));
        assertThat(cookie.getValue(), is("different"));
    }

    @Test
    public void testAddCookie() throws Exception
    {
        _fields.put("Some", "Header");
        _fields.put("Cookie", "name=value");
        _fields.put("Other", "header");
        List<HttpCookie> cookies0 = _cache.getCookies(_fields.asImmutable());
        assertThat(cookies0, hasSize(1));
        HttpCookie cookie = cookies0.get(0);
        assertThat(cookie.getName(), is("name"));
        assertThat(cookie.getValue(), is("value"));

        _fields.add(HttpHeader.COOKIE, "other=different");
        List<HttpCookie> cookies1 = _cache.getCookies(_fields.asImmutable());
        assertThat(cookies1, hasSize(2));
        assertThat(cookies1, not(sameInstance(cookies0)));
        cookie = cookies0.get(0);
        assertThat(cookie.getName(), is("name"));
        assertThat(cookie.getValue(), is("value"));
        cookie = cookies1.get(1);
        assertThat(cookie.getName(), is("other"));
        assertThat(cookie.getValue(), is("different"));
    }

}
