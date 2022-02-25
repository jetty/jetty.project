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

import java.io.IOException;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultiExceptionTest
{
    @Test
    public void testEmpty() throws Exception
    {
        MultiException me = new MultiException();

        assertEquals(0, me.size());
        me.ifExceptionThrow();
        me.ifExceptionThrowMulti();
        me.ifExceptionThrowRuntime();
        me.ifExceptionThrowSuppressed();

        assertEquals(0, me.getStackTrace().length, "Stack trace should not be filled out");
    }

    @Test
    public void testOne() throws Exception
    {
        MultiException me = new MultiException();
        IOException io = new IOException("one");
        me.add(io);

        assertEquals(1, me.size());

        // TODO: convert to assertThrows chain
        try
        {
            me.ifExceptionThrow();
            assertTrue(false);
        }
        catch (IOException e)
        {
            assertTrue(e == io);
        }

        try
        {
            me.ifExceptionThrowMulti();
            assertTrue(false);
        }
        catch (MultiException e)
        {
            assertTrue(e instanceof MultiException);
        }

        try
        {
            me.ifExceptionThrowRuntime();
            assertTrue(false);
        }
        catch (RuntimeException e)
        {
            assertTrue(e.getCause() == io);
        }

        try
        {
            me.ifExceptionThrowSuppressed();
            assertTrue(false);
        }
        catch (IOException e)
        {
            assertTrue(e == io);
        }

        me = new MultiException();
        RuntimeException run = new RuntimeException("one");
        me.add(run);

        try
        {
            me.ifExceptionThrowRuntime();
            assertTrue(false);
        }
        catch (RuntimeException e)
        {
            assertTrue(run == e);
        }

        assertEquals(0, me.getStackTrace().length, "Stack trace should not be filled out");
    }

    private MultiException multiExceptionWithIoRt()
    {
        MultiException me = new MultiException();
        IOException io = new IOException("one");
        RuntimeException run = new RuntimeException("two");
        me.add(io);
        me.add(run);
        assertEquals(2, me.size());

        assertEquals(0, me.getStackTrace().length, "Stack trace should not be filled out");
        return me;
    }

    private MultiException multiExceptionWithRtIo()
    {
        MultiException me = new MultiException();
        RuntimeException run = new RuntimeException("one");
        IOException io = new IOException("two");
        me.add(run);
        me.add(io);
        assertEquals(2, me.size());

        assertEquals(0, me.getStackTrace().length, "Stack trace should not be filled out");
        return me;
    }

    @Test
    public void testTwo() throws Exception
    {
        MultiException me = multiExceptionWithIoRt();
        try
        {
            me.ifExceptionThrow();
            assertTrue(false);
        }
        catch (MultiException e)
        {
            assertTrue(e instanceof MultiException);
            assertTrue(e.getStackTrace().length > 0);
        }

        me = multiExceptionWithIoRt();
        try
        {
            me.ifExceptionThrowMulti();
            assertTrue(false);
        }
        catch (MultiException e)
        {
            assertTrue(e instanceof MultiException);
            assertTrue(e.getStackTrace().length > 0);
        }

        me = multiExceptionWithIoRt();
        try
        {
            me.ifExceptionThrowRuntime();
            assertTrue(false);
        }
        catch (RuntimeException e)
        {
            assertTrue(e.getCause() instanceof MultiException);
            assertTrue(e.getStackTrace().length > 0);
        }

        me = multiExceptionWithRtIo();
        try
        {
            me.ifExceptionThrowRuntime();
            assertTrue(false);
        }
        catch (RuntimeException e)
        {
            assertThat(e.getCause(), instanceOf(MultiException.class));
            assertTrue(e.getStackTrace().length > 0);
        }

        me = multiExceptionWithRtIo();
        try
        {
            me.ifExceptionThrowSuppressed();
            assertTrue(false);
        }
        catch (RuntimeException e)
        {
            assertThat(e.getCause(), is(nullValue()));
            assertEquals(1, e.getSuppressed().length, 1);
            assertEquals(IOException.class, e.getSuppressed()[0].getClass());
        }
    }

    @Test
    public void testCause() throws Exception
    {
        MultiException me = new MultiException();
        IOException io = new IOException("one");
        RuntimeException run = new RuntimeException("two");
        me.add(io);
        me.add(run);

        try
        {
            me.ifExceptionThrow();
        }
        catch (MultiException e)
        {
            assertEquals(io, e.getCause());
            assertEquals(2, e.size());
        }
    }
}
