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

package org.eclipse.jetty.util.component;

import java.util.ArrayList;
import java.util.Collection;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;

public class DumpableTest
{
    @Test
    public void testNullDumpableCollection() throws Exception
    {
        DumpableCollection dc = new DumpableCollection("null test", null);
        String dump = dc.dump();
        assertThat(dump, Matchers.containsString("size=0"));
    }

    @Test
    public void testNonNullDumpableCollection() throws Exception
    {
        Collection<String> collection = new ArrayList<>();
        collection.add("one");
        collection.add("two");
        collection.add("three");

        DumpableCollection dc = new DumpableCollection("non null test", collection);
        String dump = dc.dump();
        assertThat(dump, Matchers.containsString("one"));
        assertThat(dump, Matchers.containsString("two"));
        assertThat(dump, Matchers.containsString("three"));
    }
}
