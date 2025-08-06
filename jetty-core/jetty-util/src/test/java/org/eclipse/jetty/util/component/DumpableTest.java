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
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.util.thread.Invocable;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    public void testDumpState() throws Exception
    {
        Invocable invocable0 = Invocable.from(Invocable.InvocationType.EITHER, Invocable.NOOP);
        Invocable invocable1 = Invocable.from(Invocable.InvocationType.NON_BLOCKING, Invocable.NOOP);
        Invocable invocable2 = Invocable.from(Invocable.InvocationType.BLOCKING, Invocable.NOOP);

        LifeCycle lifeCycle0 = new AbstractLifeCycle() {};
        LifeCycle lifeCycle1 = new AbstractLifeCycle() {};
        LifeCycle lifeCycle2 = new AbstractLifeCycle()
        {
            @Override
            protected void doStart() throws Exception
            {
                throw new IllegalStateException("start");
            }
        };
        lifeCycle1.start();
        assertThrows(IllegalStateException.class, lifeCycle2::start);

        InvocableContainer container0 = new InvocableContainer();
        container0.start();
        container0.addBean(invocable0);
        container0.addBean(lifeCycle0);
        container0.addBean(lifeCycle1);
        container0.addBean(lifeCycle2);

        InvocableContainer container1 = new InvocableContainer();
        container1.addBean(invocable1);
        container1.addBean(invocable2);
        container0.addBean(container1);

        List<String> dump = Arrays.asList(container0.dump().split("\n"));
        dump = dump.stream()
            .filter(s -> !s.startsWith("UTC:"))
            .filter(s -> !s.startsWith("JVM:"))
            .map(s -> s.replaceAll("@[a-z0-9]*", "@xxx"))
            .map(s -> s.replaceAll("0x[a-z0-9]*", "0x"))
            .toList();
        assertThat(dump, Matchers.contains(
            "oejuc.DumpableTest$InvocableContainer@xxx{STARTED} ~ BLOCKING - STARTED",
            "+- oejut.Invocable$ReadyTask@xxx[EITHER][org.eclipse.jetty.util.thread.Invocable$$Lambda/0x@xxx] ~ EITHER",
            "+~ oejuc.DumpableTest$@xxx{STOPPED} - STOPPED",
            "+~ oejuc.DumpableTest$@xxx{STARTED} - STARTED",
            "+~ oejuc.DumpableTest$@xxx{FAILED} - FAILED",
            "+~ oejuc.DumpableTest$InvocableContainer@xxx{STOPPED} ~ BLOCKING - STOPPED",
            "   +- oejut.Invocable$ReadyTask@xxx[NON_BLOCKING][org.eclipse.jetty.util.thread.Invocable$$Lambda/0x@xxx] ~ NON_BLOCKING",
            "   +- oejut.Invocable$ReadyTask@xxx[BLOCKING][org.eclipse.jetty.util.thread.Invocable$$Lambda/0x@xxx] ~ BLOCKING",
            "legend: +- bean, += managed, +~ unmanaged, +? auto, +: iterable, +] array, +} map, +> pojo; @xxx visited"
        ));
    }

    private static class InvocableContainer extends ContainerLifeCycle implements Invocable
    {
        @Override
        public InvocationType getInvocationType()
        {
            return Invocable.combineTypes(getBeans().stream().map(Invocable::getInvocationType).toArray(InvocationType[]::new));
        }
    }

}
