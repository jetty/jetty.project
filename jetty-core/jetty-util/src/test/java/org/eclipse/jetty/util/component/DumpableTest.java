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

package org.eclipse.jetty.util.component;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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

    @Test
    public void testDumpableCollectionWithCycle() throws Exception
    {
        final String EXPECTED = """
        A size=3
        +> B size=4
        |  +> si
        |  +> see
        |  +> sea
        |  +> C size=3
        |     +> ay
        |     +>@ A size=3
        |     +> ai
        +> be
        +> bee
        """;

        List<Object> listC = new ArrayList<>();
        DumpableCollection c = new DumpableCollection("C", listC);
        DumpableCollection b = new DumpableCollection("B", List.of("si", "see", "sea", c));
        DumpableCollection a = new DumpableCollection("A", List.of(b, "be", "bee"));
        listC.add("ay");
        listC.add(a);
        listC.add("ai");

        String dump = a.dump();
        assertThat(dump, Matchers.startsWith(EXPECTED));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        a.dump(new PrintStream(baos), "");
        assertThat(baos.toString(), Matchers.startsWith(EXPECTED));
    }
}
