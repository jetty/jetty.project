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
