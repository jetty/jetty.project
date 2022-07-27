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
import java.util.Arrays;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ExceptionUtilTest
{
    @Test
    public void testAs()
    {
        assertThat(ExceptionUtil.as(IOException.class, null), nullValue());

        IOException iox = new IOException();
        assertThat(ExceptionUtil.as(IOException.class, iox), sameInstance(iox));

        IOException eiox = new IOException()
        {
            @Override
            public String toString()
            {
                return "Extended" + super.toString();
            }
        };
        assertThat(ExceptionUtil.as(IOException.class, eiox), sameInstance(eiox));

        Exception cause = new Exception("cause");
        IOException as = ExceptionUtil.as(IOException.class, cause);
        assertThat(as, notNullValue());
        assertThat(as.getCause(), sameInstance(cause));
    }

    @Test
    public void testThrow() throws Exception
    {
        ExceptionUtil.ifExceptionThrow(null);

        assertThrows(Error.class, () -> ExceptionUtil.ifExceptionThrow(new Error()));

        assertThrows(RuntimeException.class, () -> ExceptionUtil.ifExceptionThrow(new RuntimeException()));

        assertThrows(IOException.class, () -> ExceptionUtil.ifExceptionThrow(new IOException()));
    }

    @Test
    public void testThrowAs() throws IOException
    {
        ExceptionUtil.ifExceptionThrowAs(IOException.class, null);

        assertThrows(Error.class, () -> ExceptionUtil.ifExceptionThrowAs(IOException.class, new Error()));

        assertThrows(RuntimeException.class, () -> ExceptionUtil.ifExceptionThrowAs(IOException.class, new RuntimeException()));

        assertThrows(IOException.class, () -> ExceptionUtil.ifExceptionThrowAs(IOException.class, new IOException()));
        IOException ex = assertThrows(IOException.class, () -> ExceptionUtil.ifExceptionThrowAs(IOException.class, new Exception("cause")));
        assertThat(ex.getCause(), instanceOf(Exception.class));
        assertThat(ex.getCause().getMessage(), is("cause"));
    }

    @Test
    public void testThrowAllAs() throws IOException
    {
        ExceptionUtil.ifExceptionThrowAllAs(IOException.class, null);

        IOException ex = assertThrows(IOException.class, () -> ExceptionUtil.ifExceptionThrowAllAs(IOException.class, new Error()));
        assertThat(ex.getCause(), instanceOf(Error.class));

        ex = assertThrows(IOException.class, () -> ExceptionUtil.ifExceptionThrowAllAs(IOException.class, new RuntimeException()));
        assertThat(ex.getCause(), instanceOf(RuntimeException.class));

        ex = assertThrows(IOException.class, () -> ExceptionUtil.ifExceptionThrowAllAs(IOException.class, new IOException()));
        assertThat(ex.getCause(), nullValue());

        ex = assertThrows(IOException.class, () -> ExceptionUtil.ifExceptionThrowAllAs(IOException.class, new Exception("cause")));
        assertThat(ex.getCause(), instanceOf(Exception.class));
        assertThat(ex.getCause().getMessage(), is("cause"));
    }

    @Test
    public void testEmpty() throws Exception
    {
        ExceptionUtil.ifExceptionThrow(null);
        ExceptionUtil.ifExceptionThrowRuntime(null);
    }

    @Test
    public void testMultiSingleIOException()
    {
        final ExceptionUtil.MultiException me = new ExceptionUtil.MultiException();
        IOException io1 = new IOException("one");
        me.add(io1);

        IOException ioe = assertThrows(IOException.class, me::ifExceptionThrow);
        assertEquals(io1, ioe);

        RuntimeException re = assertThrows(RuntimeException.class, me::ifExceptionThrowRuntime);
        assertEquals(io1, re.getCause());
    }

    @Test
    public void testMultiSingleRuntimeException()
    {
        final ExceptionUtil.MultiException me = new ExceptionUtil.MultiException();
        RuntimeException run = new RuntimeException("one");
        me.add(run);

        RuntimeException re1 = assertThrows(RuntimeException.class, me::ifExceptionThrow);
        assertEquals(run, re1);

        RuntimeException re2 = assertThrows(RuntimeException.class, me::ifExceptionThrowRuntime);
        assertEquals(run, re2);
    }

    @Test
    public void testMultiTwoSuppressedIOThenRuntime()
    {
        final ExceptionUtil.MultiException me = new ExceptionUtil.MultiException();
        IOException io = new IOException("one");
        RuntimeException run = new RuntimeException("two");
        me.add(io);
        me.add(run);

        IOException io2 = assertThrows(IOException.class, me::ifExceptionThrow);
        assertThat(io, Matchers.sameInstance(io2));
        assertThat(Arrays.asList(io2.getSuppressed()), Matchers.contains(run));
    }

    @Test
    public void testMultiTwoSuppressedRuntimeThenIO() throws Exception
    {
        final ExceptionUtil.MultiException me = new ExceptionUtil.MultiException();
        IOException io = new IOException("one");
        RuntimeException run = new RuntimeException("two");
        me.add(io);
        me.add(run);

        IOException io2 = assertThrows(IOException.class, me::ifExceptionThrow);
        assertThat(io, Matchers.sameInstance(io2));
        assertThat(Arrays.asList(io.getSuppressed()), Matchers.contains(run));

        RuntimeException re1 = assertThrows(RuntimeException.class, me::ifExceptionThrowRuntime);
        assertThat(re1.getCause(), Matchers.sameInstance(io));
    }
}
