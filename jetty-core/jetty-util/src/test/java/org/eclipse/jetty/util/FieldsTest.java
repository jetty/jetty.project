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

package org.eclipse.jetty.util;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class FieldsTest
{
    @Test
    public void testAddByArgs()
    {
        Fields fields = new Fields();
        fields.add("x", "1");
        fields.add("y", "1");
        fields.add("y", "2");
        fields.add("z", "1");
        fields.add("z", "2");
        fields.add("z", "3");

        assertThat(fields.getSize(), equalTo(3));
        assertThat(fields.getValues("x"), contains("1"));
        assertThat(fields.getValues("y"), contains("1", "2"));
        assertThat(fields.getValues("z"), contains("1", "2", "3"));
    }

    @Test
    public void testAddByField()
    {
        Fields fields = new Fields();
        fields.add(new Fields.Field("x", "1"));
        fields.add(new Fields.Field("y", "1"));
        fields.add(new Fields.Field("y", "2"));
        fields.add(new Fields.Field("z", "1"));
        fields.add(new Fields.Field("z", "2"));
        fields.add(new Fields.Field("z", "3"));

        System.err.println(fields);

        assertThat(fields.getSize(), equalTo(3));
        assertThat(fields.getValues("x"), contains("1"));
        assertThat(fields.getValues("y"), contains("1", "2"));
        assertThat(fields.getValues("z"), contains("1", "2", "3"));
    }

    @Test
    public void testIterator()
    {
        Fields fields = new Fields();

        assertFalse(fields.iterator().hasNext());

        fields.add("x", "1");
        fields.add("y", "1");
        fields.add("y", "2");
        fields.add("z", "1");
        fields.add("z", "2");
        fields.add("z", "3");

        Set<String> set = new HashSet<>();
        for (Fields.Field f : fields)
            set.add(f.getName());

        assertThat(set, containsInAnyOrder("x", "y", "z"));
    }
}
