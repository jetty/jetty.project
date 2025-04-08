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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpFieldsMapTest
{
    @Test
    public void testPutField()
    {
        HttpFields.Mutable fields = HttpFields.build().put("foo", "bar");
        Map<String, List<String>> map = HttpFields.asMap(fields);

        map.put("baz", List.of("qux"));

        assertEquals(2, map.size());
        assertEquals(2, fields.size());
        assertThat(map.get("foo"), equalTo(List.of("bar")));
        assertThat(map.get("baz"), equalTo(List.of("qux")));
        assertThat(map, hasEntry("baz", List.of("qux")));
        assertTrue(fields.contains("foo", "bar"));
        assertTrue(fields.contains("baz", "qux"));
    }

    @Test
    public void testPutReplaceField()
    {
        HttpFields.Mutable fields = HttpFields.build().put("foo", "bar");
        Map<String, List<String>> map = HttpFields.asMap(fields);

        List<String> put = map.put("foo", List.of("baz"));

        assertNotNull(put);
        assertEquals(1, map.size());
        assertEquals(1, fields.size());
        assertThat(put, equalTo(List.of("bar")));
        assertThat(map.get("foo"), equalTo(List.of("baz")));
        assertFalse(fields.contains("foo", "bar"));
        assertTrue(fields.contains("foo", "baz"));
    }

    @Test
    public void testRemoveField()
    {
        HttpFields.Mutable fields = HttpFields.build().put("foo", "bar");
        Map<String, List<String>> map = HttpFields.asMap(fields);

        List<String> values = map.remove("foo");

        assertThat(values, equalTo(List.of("bar")));
        assertTrue(map.isEmpty());
        assertEquals(0, fields.size());

        // Adding to the values is unsupported.
        assertThrows(UnsupportedOperationException.class, () -> values.add("baz"));
    }

    @Test
    public void testAddValue()
    {
        HttpFields.Mutable fields = HttpFields.build().put("foo", List.of("bar"));
        Map<String, List<String>> map = HttpFields.asMap(fields);

        List<String> values = map.get("foo");
        boolean added = values.add("baz");

        assertTrue(added);
        assertThat(values, equalTo(List.of("bar", "baz")));
        assertThat(map.get("foo"), equalTo(values));
        assertTrue(fields.contains("foo", "bar"));
        assertTrue(fields.contains("foo", "baz"));
        assertThat(fields.getValuesList("foo"), equalTo(values));
    }

    @Test
    public void testRemoveValue()
    {
        HttpFields.Mutable fields = HttpFields.build()
            .add("foo", "bar")
            .add("foo", "baz");
        Map<String, List<String>> map = HttpFields.asMap(fields);

        List<String> values = map.get("foo");
        assertEquals(2, values.size());

        // Removing from the values is unsupported.
        assertThrows(UnsupportedOperationException.class, () -> values.remove("bar"));
    }
}
