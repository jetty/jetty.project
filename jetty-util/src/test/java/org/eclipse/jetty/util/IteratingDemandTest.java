//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IteratingDemandTest
{
    TestDemand test;

    @BeforeEach
    public void prepare() throws Exception
    {
        test = new TestDemand();
    }

    @AfterEach
    public void dispose() throws Exception
    {
    }

    @Test
    public void testSimple() throws Exception
    {
        assertFalse(test.takeDemand());

        test.demand(0);
        assertFalse(test.takeDemand());

        test.demand(1);
        assertTrue(test.takeDemand());
        assertThat(test._output, empty());

        test.onInput(new ArrayDeque<>(List.of("One", "Two", "Three")));
        assertFalse(test.takeDemand());
        assertThat(test._output, contains("One"));

        test.demand(2);
        assertFalse(test.takeDemand());
        assertThat(test._output, contains("One", "Two", "Three"));

        test.demand(1);
        assertTrue(test.takeDemand());
    }

    @Test
    public void testProduceNoneComplete() throws Exception
    {
        assertFalse(test.takeDemand());
        test.demand(1);
        assertTrue(test.takeDemand());
        assertThat(test._output, empty());

        test.onInput(new ArrayDeque<>(List.of("One", "None")));
        assertFalse(test.takeDemand());
        assertThat(test._output, contains("One"));

        test.demand(1);
        assertTrue(test.takeDemand());
        assertThat(test._output, contains("One"));
    }

    @Test
    public void testProduceNoneInComplete() throws Exception
    {
        assertFalse(test.takeDemand());
        test.demand(1);
        assertTrue(test.takeDemand());
        assertThat(test._output, empty());

        test.onInput(new ArrayDeque<>(List.of("One", "None", "Three")));
        assertFalse(test.takeDemand());
        assertThat(test._output, contains("One"));

        test.demand(1);
        assertFalse(test.takeDemand());
        assertThat(test._output, contains("One", "Three"));

        test.demand(1);
        assertTrue(test.takeDemand());
    }

    @Test
    public void testProduceMultipleComplete() throws Exception
    {
        assertFalse(test.takeDemand());
        test.demand(1);
        assertTrue(test.takeDemand());
        assertThat(test._output, empty());

        test.onInput(new ArrayDeque<>(List.of("One", "ECHO")));
        assertFalse(test.takeDemand());
        assertThat(test._output, contains("One"));

        test.demand(2);
        assertFalse(test.takeDemand());
        assertThat(test._output, contains("One", "ECHO", "Echo"));

        test.demand(1);
        assertFalse(test.takeDemand());
        assertThat(test._output, contains("One", "ECHO", "Echo", "echo"));

        test.demand(1);
        assertTrue(test.takeDemand());
    }

    @Test
    public void testProduceMultipleIncomplete() throws Exception
    {
        assertFalse(test.takeDemand());
        test.demand(1);
        assertTrue(test.takeDemand());
        assertThat(test._output, empty());

        test.onInput(new ArrayDeque<>(List.of("One", "ECHO", "Three")));
        assertFalse(test.takeDemand());
        assertThat(test._output, contains("One"));

        test.demand(2);
        assertFalse(test.takeDemand());
        assertThat(test._output, contains("One", "ECHO", "Echo"));

        test.demand(1);
        assertFalse(test.takeDemand());
        assertThat(test._output, contains("One", "ECHO", "Echo", "echo"));

        test.demand(1);
        assertFalse(test.takeDemand());
        assertThat(test._output, contains("One", "ECHO", "Echo", "echo", "Three"));

        test.demand(1);
        assertTrue(test.takeDemand());
    }

    class TestDemand extends IteratingDemand<Queue<String>, String>
    {
        final AtomicBoolean _demand = new AtomicBoolean();
        final ArrayList<String> _output = new ArrayList<>();
        final AtomicReference<String> _echo = new AtomicReference<>();

        boolean takeDemand()
        {
            return _demand.getAndSet(false);
        }

        @Override
        protected void demandInput()
        {
            _demand.set(true);
        }

        @Override
        protected String produce(Queue<String> input, Runnable release) throws Throwable
        {
            String next = _echo.getAndSet(null);
            if (next == null && input != null)
                next = input.poll();
            if (input != null && input.isEmpty() && release != null)
                release.run();
            if (next != null)
            {
                switch (next)
                {
                    case "ECHO":
                        _echo.set("Echo");
                        break;
                    case "Echo":
                        _echo.set("echo");
                        break;
                    case "None":
                        return null;
                    default:
                        break;
                }
            }
            return next;
        }

        @Override
        protected void onOutput(String out)
        {
            _output.add(out);
        }
    }
}
