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
        HttpFields fields = HttpFields.build()
            .put("a", "foo")
            .put("b", "bar")
            .put("c", "fizz")
            .asImmutable();

        MatcherAssert.assertThat(fields, HttpFieldsMatchers.containsHeader("a"));
    }

    @Test
    public void testNotContainsHeader()
    {
        HttpFields.Mutable fields = HttpFields.build()
            .put("a", "foo")
            .put("b", "bar")
            .put("c", "fizz");

        AssertionError x = Assertions.assertThrows(AssertionError.class, () ->
        {
            MatcherAssert.assertThat(fields, Matchers.not(HttpFieldsMatchers.containsHeader("a")));
        });

        MatcherAssert.assertThat(x.getMessage(), Matchers.containsString("not expecting http field name \"a\""));
    }

    @Test
    public void testContainsHeaderMisMatch()
    {
        HttpFields.Mutable fields = HttpFields.build();
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
    public void testContainsHeaderValueMisMatchNoSuchHeader()
    {
        HttpFields.Mutable fields = HttpFields.build();
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
    public void testContainsHeaderValueMisMatchNoSuchValue()
    {
        HttpFields.Mutable fields = HttpFields.build();
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
        HttpFields.Mutable fields = HttpFields.build();
        fields.put("a", "foo");
        fields.put("b", "bar");
        fields.put("c", "fizz");
        fields.put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");

        MatcherAssert.assertThat(fields, HttpFieldsMatchers.containsHeaderValue("b", "bar"));
        MatcherAssert.assertThat(fields, HttpFieldsMatchers.containsHeaderValue("content-type", "text/plain"));
        MatcherAssert.assertThat(fields, HttpFieldsMatchers.containsHeaderValue("content-type", "charset=UTF-8"));
    }
}
