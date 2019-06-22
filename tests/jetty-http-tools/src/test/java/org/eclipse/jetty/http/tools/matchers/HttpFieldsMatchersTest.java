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

package org.eclipse.jetty.http.tools.matchers;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class HttpFieldsMatchersTest
{
    @Test
    public void testContainsHeader()
    {
        HttpFields fields = new HttpFields();
        fields.put("a", "foo");
        fields.put("b", "bar");
        fields.put("c", "fizz");

        MatcherAssert.assertThat(fields, HttpFieldsMatchers.containsHeader("a"));
    }

    @Test
    public void testNotContainsHeader()
    {
        HttpFields fields = new HttpFields();
        fields.put("a", "foo");
        fields.put("b", "bar");
        fields.put("c", "fizz");

        AssertionError x = Assertions.assertThrows(AssertionError.class, () ->
        {
            MatcherAssert.assertThat(fields, Matchers.not(HttpFieldsMatchers.containsHeader("a")));
        });

        MatcherAssert.assertThat(x.getMessage(), Matchers.containsString("not expecting http field name \"a\""));
    }

    @Test
    public void testContainsHeader_MisMatch()
    {
        HttpFields fields = new HttpFields();
        fields.put("a", "foo");
        fields.put("b", "bar");
        fields.put("c", "fizz");

        AssertionError x = Assertions.assertThrows(AssertionError.class, () ->
        {
            MatcherAssert.assertThat(fields, HttpFieldsMatchers.containsHeader("z"));
        });

        MatcherAssert.assertThat(x.getMessage(), Matchers.containsString("expecting http field name \"z\""));
    }

    @Test
    public void testContainsHeaderValue_MisMatch_NoSuchHeader()
    {
        HttpFields fields = new HttpFields();
        fields.put("a", "foo");
        fields.put("b", "bar");
        fields.put("c", "fizz");

        AssertionError x = Assertions.assertThrows(AssertionError.class, () ->
        {
            MatcherAssert.assertThat(fields, HttpFieldsMatchers.containsHeaderValue("z", "floom"));
        });

        MatcherAssert.assertThat(x.getMessage(), Matchers.containsString("expecting http header \"z\" with value \"floom\""));
    }

    @Test
    public void testContainsHeaderValue_MisMatch_NoSuchValue()
    {
        HttpFields fields = new HttpFields();
        fields.put("a", "foo");
        fields.put("b", "bar");
        fields.put("c", "fizz");

        AssertionError x = Assertions.assertThrows(AssertionError.class, () ->
        {
            MatcherAssert.assertThat(fields, HttpFieldsMatchers.containsHeaderValue("a", "floom"));
        });

        MatcherAssert.assertThat(x.getMessage(), Matchers.containsString("expecting http header \"a\" with value \"floom\""));
    }

    @Test
    public void testContainsHeaderValue()
    {
        HttpFields fields = new HttpFields();
        fields.put("a", "foo");
        fields.put("b", "bar");
        fields.put("c", "fizz");
        fields.put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");

        MatcherAssert.assertThat(fields, HttpFieldsMatchers.containsHeaderValue("b", "bar"));
        MatcherAssert.assertThat(fields, HttpFieldsMatchers.containsHeaderValue("content-type", "text/plain"));
        MatcherAssert.assertThat(fields, HttpFieldsMatchers.containsHeaderValue("content-type", "charset=UTF-8"));
    }
}
