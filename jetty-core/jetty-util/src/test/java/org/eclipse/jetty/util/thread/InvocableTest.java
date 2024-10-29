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

package org.eclipse.jetty.util.thread;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.util.thread.Invocable.InvocationType.BLOCKING;
import static org.eclipse.jetty.util.thread.Invocable.InvocationType.EITHER;
import static org.eclipse.jetty.util.thread.Invocable.InvocationType.NON_BLOCKING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

public class InvocableTest
{
    @Test
    public void testCombineType()
    {
        assertThat(Invocable.combine(null, null), is(BLOCKING));
        assertThat(Invocable.combine(null, BLOCKING), is(BLOCKING));
        assertThat(Invocable.combine(null, NON_BLOCKING), is(BLOCKING));
        assertThat(Invocable.combine(null, EITHER), is(BLOCKING));

        assertThat(Invocable.combine(BLOCKING, null), is(BLOCKING));
        assertThat(Invocable.combine(BLOCKING, BLOCKING), is(BLOCKING));
        assertThat(Invocable.combine(BLOCKING, NON_BLOCKING), is(BLOCKING));
        assertThat(Invocable.combine(BLOCKING, EITHER), is(BLOCKING));

        assertThat(Invocable.combine(NON_BLOCKING, null), is(BLOCKING));
        assertThat(Invocable.combine(NON_BLOCKING, BLOCKING), is(BLOCKING));
        assertThat(Invocable.combine(NON_BLOCKING, NON_BLOCKING), is(NON_BLOCKING));
        assertThat(Invocable.combine(NON_BLOCKING, EITHER), is(NON_BLOCKING));

        assertThat(Invocable.combine(EITHER, null), is(BLOCKING));
        assertThat(Invocable.combine(EITHER, BLOCKING), is(BLOCKING));
        assertThat(Invocable.combine(EITHER, NON_BLOCKING), is(NON_BLOCKING));
        assertThat(Invocable.combine(EITHER, EITHER), is(EITHER));

        assertThat(Invocable.combineTypes(null, null), is(BLOCKING));
        assertThat(Invocable.combineTypes(null, BLOCKING), is(BLOCKING));
        assertThat(Invocable.combineTypes(null, NON_BLOCKING), is(BLOCKING));
        assertThat(Invocable.combineTypes(null, EITHER), is(BLOCKING));

        assertThat(Invocable.combineTypes(BLOCKING, null), is(BLOCKING));
        assertThat(Invocable.combineTypes(BLOCKING, BLOCKING), is(BLOCKING));
        assertThat(Invocable.combineTypes(BLOCKING, NON_BLOCKING), is(BLOCKING));
        assertThat(Invocable.combineTypes(BLOCKING, EITHER), is(BLOCKING));

        assertThat(Invocable.combineTypes(NON_BLOCKING, null), is(BLOCKING));
        assertThat(Invocable.combineTypes(NON_BLOCKING, BLOCKING), is(BLOCKING));
        assertThat(Invocable.combineTypes(NON_BLOCKING, NON_BLOCKING), is(NON_BLOCKING));
        assertThat(Invocable.combineTypes(NON_BLOCKING, EITHER), is(NON_BLOCKING));

        assertThat(Invocable.combineTypes(EITHER, null), is(BLOCKING));
        assertThat(Invocable.combineTypes(EITHER, BLOCKING), is(BLOCKING));
        assertThat(Invocable.combineTypes(EITHER, NON_BLOCKING), is(NON_BLOCKING));
        assertThat(Invocable.combineTypes(EITHER, EITHER), is(EITHER));

        assertThat(Invocable.combineTypes(EITHER, EITHER, null), is(BLOCKING));
        assertThat(Invocable.combineTypes(EITHER, EITHER, BLOCKING), is(BLOCKING));
        assertThat(Invocable.combineTypes(EITHER, EITHER, NON_BLOCKING), is(NON_BLOCKING));
        assertThat(Invocable.combineTypes(EITHER, EITHER, EITHER), is(EITHER));

        assertThat(Invocable.combineTypes(BLOCKING, EITHER, null), is(BLOCKING));
        assertThat(Invocable.combineTypes(BLOCKING, EITHER, BLOCKING), is(BLOCKING));
        assertThat(Invocable.combineTypes(BLOCKING, EITHER, NON_BLOCKING), is(BLOCKING));
        assertThat(Invocable.combineTypes(BLOCKING, EITHER, EITHER), is(BLOCKING));

        assertThat(Invocable.combineTypes(NON_BLOCKING, EITHER, null), is(BLOCKING));
        assertThat(Invocable.combineTypes(NON_BLOCKING, EITHER, BLOCKING), is(BLOCKING));
        assertThat(Invocable.combineTypes(NON_BLOCKING, EITHER, NON_BLOCKING), is(NON_BLOCKING));
        assertThat(Invocable.combineTypes(NON_BLOCKING, EITHER, EITHER), is(NON_BLOCKING));
    }

    @Test
    public void testCombineRunnable()
    {
        Queue<String> history = new ConcurrentLinkedQueue<>();

        assertThat(Invocable.combine(), nullValue());
        assertThat(Invocable.combine((Runnable)null), nullValue());
        assertThat(Invocable.combine(null, (Runnable)null), nullValue());

        Runnable r1 = () -> history.add("R1");
        Runnable r2 = () -> history.add("R2");
        Runnable r3 = () -> history.add("R3");

        assertThat(Invocable.combine(r1, null, null), sameInstance(r1));
        assertThat(Invocable.combine(null, r2, null), sameInstance(r2));
        assertThat(Invocable.combine(null, null, r3), sameInstance(r3));

        Runnable r13 = Invocable.combine(r1, null, r3);
        history.clear();
        r13.run();
        assertThat(history, contains("R1", "R3"));

        Runnable r123 = Invocable.combine(r1, r2, r3);
        history.clear();
        r123.run();
        assertThat(history, contains("R1", "R2", "R3"));
    }
}
