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

package org.eclipse.jetty.http.matchers;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.http.HttpFieldsMatchers.containsHeader;
import static org.eclipse.jetty.http.HttpFieldsMatchers.containsHeaderValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class HttpFieldsMatchersTest
{
    @Test
    public void testContainsHeader()
    {
        HttpFields fields = new HttpFields();
        fields.put("a", "foo");
        fields.put("b", "bar");
        fields.put("c", "fizz");

        assertThat(fields, containsHeader("a"));
    }

    @Test
    public void testNotContainsHeader()
    {
        HttpFields fields = new HttpFields();
        fields.put("a", "foo");
        fields.put("b", "bar");
        fields.put("c", "fizz");

        AssertionError x = assertThrows(AssertionError.class, () ->
        {
            assertThat(fields, not(containsHeader("a")));
        });

        assertThat(x.getMessage(), containsString("not expecting http field name \"a\""));
    }

    @Test
    public void testContainsHeaderMisMatch()
    {
        HttpFields fields = new HttpFields();
        fields.put("a", "foo");
        fields.put("b", "bar");
        fields.put("c", "fizz");

        AssertionError x = assertThrows(AssertionError.class, () ->
        {
            assertThat(fields, containsHeader("z"));
        });

        assertThat(x.getMessage(), containsString("expecting http field name \"z\""));
    }

    @Test
    public void testContainsHeaderValueMisMatchNoSuchHeader()
    {
        HttpFields fields = new HttpFields();
        fields.put("a", "foo");
        fields.put("b", "bar");
        fields.put("c", "fizz");

        AssertionError x = assertThrows(AssertionError.class, () ->
        {
            assertThat(fields, containsHeaderValue("z", "floom"));
        });

        assertThat(x.getMessage(), containsString("expecting http header \"z\" with value \"floom\""));
    }

    @Test
    public void testContainsHeaderValueMisMatchNoSuchValue()
    {
        HttpFields fields = new HttpFields();
        fields.put("a", "foo");
        fields.put("b", "bar");
        fields.put("c", "fizz");

        AssertionError x = assertThrows(AssertionError.class, () ->
        {
            assertThat(fields, containsHeaderValue("a", "floom"));
        });

        assertThat(x.getMessage(), containsString("expecting http header \"a\" with value \"floom\""));
    }

    @Test
    public void testContainsHeaderValue()
    {
        HttpFields fields = new HttpFields();
        fields.put("a", "foo");
        fields.put("b", "bar");
        fields.put("c", "fizz");
        fields.put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");

        assertThat(fields, containsHeaderValue("b", "bar"));
        assertThat(fields, containsHeaderValue("content-type", "text/plain"));
        assertThat(fields, containsHeaderValue("content-type", "charset=UTF-8"));
    }
}
