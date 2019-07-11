//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class HttpCookieTest
{

    @Test
    public void testConstructFromSetCookie()
    {
        HttpCookie cookie = new HttpCookie("everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly");
    }

    @Test
    public void testSetRFC2965Cookie() throws Exception
    {
        HttpCookie httpCookie;

        httpCookie = new HttpCookie("null", null, null, null, -1, false, false, null, -1);
        assertEquals("null=", httpCookie.getRFC2965SetCookie());

        httpCookie = new HttpCookie("minimal", "value", null, null, -1, false, false, null, -1);
        assertEquals("minimal=value", httpCookie.getRFC2965SetCookie());

        httpCookie = new HttpCookie("everything", "something", "domain", "path", 0, true, true, "noncomment", 0);
        assertEquals("everything=something;Version=1;Path=path;Domain=domain;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=noncomment", httpCookie.getRFC2965SetCookie());

        httpCookie = new HttpCookie("everything", "value", "domain", "path", 0, true, true, "comment", 0);
        assertEquals("everything=value;Version=1;Path=path;Domain=domain;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=comment", httpCookie.getRFC2965SetCookie());

        httpCookie = new HttpCookie("ev erything", "va lue", "do main", "pa th", 1, true, true, "co mment", 1);
        String setCookie = httpCookie.getRFC2965SetCookie();
        assertThat(setCookie, Matchers.startsWith("\"ev erything\"=\"va lue\";Version=1;Path=\"pa th\";Domain=\"do main\";Expires="));
        assertThat(setCookie, Matchers.endsWith(" GMT;Max-Age=1;Secure;HttpOnly;Comment=\"co mment\""));

        httpCookie = new HttpCookie("name", "value", null, null, -1, false, false, null, 0);
        setCookie = httpCookie.getRFC2965SetCookie();
        assertEquals(-1, setCookie.indexOf("Version="));
        httpCookie = new HttpCookie("name", "v a l u e", null, null, -1, false, false, null, 0);
        setCookie = httpCookie.getRFC2965SetCookie();

        httpCookie = new HttpCookie("json", "{\"services\":[\"cwa\",  \"aa\"]}", null, null, -1, false, false, null, -1);
        assertEquals("json=\"{\\\"services\\\":[\\\"cwa\\\",  \\\"aa\\\"]}\"", httpCookie.getRFC2965SetCookie());

        httpCookie = new HttpCookie("name", "value%=", null, null, -1, false, false, null, 0);
        setCookie = httpCookie.getRFC2965SetCookie();
        assertEquals("name=value%=", setCookie);
    }

    @Test
    public void testSetRFC6265Cookie() throws Exception
    {
        HttpCookie httpCookie;

        httpCookie = new HttpCookie("null", null, null, null, -1, false, false, null, -1);
        assertEquals("null=", httpCookie.getRFC6265SetCookie());

        httpCookie = new HttpCookie("minimal", "value", null, null, -1, false, false, null, -1);
        assertEquals("minimal=value", httpCookie.getRFC6265SetCookie());

        //test cookies with same name, domain and path
        httpCookie = new HttpCookie("everything", "something", "domain", "path", 0, true, true, null, -1);
        assertEquals("everything=something; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly", httpCookie.getRFC6265SetCookie());

        httpCookie = new HttpCookie("everything", "value", "domain", "path", 0, true, true, null, -1);
        assertEquals("everything=value; Path=path; Domain=domain; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly", httpCookie.getRFC6265SetCookie());

        String[] badNameExamples = {
            "\"name\"",
            "name\t",
            "na me",
            "name\u0082",
            "na\tme",
            "na;me",
            "{name}",
            "[name]",
            "\""
        };

        for (String badNameExample : badNameExamples)
        {
            try
            {
                httpCookie = new HttpCookie(badNameExample, "value", null, "/", 1, true, true, null, -1);
                httpCookie.getRFC6265SetCookie();
                fail(badNameExample);
            }
            catch (IllegalArgumentException ex)
            {
                // System.err.printf("%s: %s%n", ex.getClass().getSimpleName(), ex.getMessage());
                assertThat("Testing bad name: [" + badNameExample + "]", ex.getMessage(),
                    allOf(containsString("RFC6265"), containsString("RFC2616")));
            }
        }

        String[] badValueExamples = {
            "va\tlue",
            "\t",
            "value\u0000",
            "val\u0082ue",
            "va lue",
            "va;lue",
            "\"value",
            "value\"",
            "val\\ue",
            "val\"ue",
            "\""
        };

        for (String badValueExample : badValueExamples)
        {
            try
            {
                httpCookie = new HttpCookie("name", badValueExample, null, "/", 1, true, true, null, -1);
                httpCookie.getRFC6265SetCookie();
                fail();
            }
            catch (IllegalArgumentException ex)
            {
                // System.err.printf("%s: %s%n", ex.getClass().getSimpleName(), ex.getMessage());
                assertThat("Testing bad value [" + badValueExample + "]", ex.getMessage(), Matchers.containsString("RFC6265"));
            }
        }

        String[] goodNameExamples = {
            "name",
            "n.a.m.e",
            "na-me",
            "+name",
            "na*me",
            "na$me",
            "#name"
        };

        for (String goodNameExample : goodNameExamples)
        {
            httpCookie = new HttpCookie(goodNameExample, "value", null, "/", 1, true, true, null, -1);
            // should not throw an exception
        }

        String[] goodValueExamples = {
            "value",
            "",
            null,
            "val=ue",
            "val-ue",
            "val/ue",
            "v.a.l.u.e"
        };

        for (String goodValueExample : goodValueExamples)
        {
            httpCookie = new HttpCookie("name", goodValueExample, null, "/", 1, true, true, null, -1);
            // should not throw an exception
        }
    }
}
