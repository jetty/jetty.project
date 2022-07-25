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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MultiExceptionTest
{
    @Test
    public void testEmpty() throws Exception
    {
        MultiException me = new MultiException();

        me.ifExceptionThrow();
        me.ifExceptionThrowMulti();
        me.ifExceptionThrowRuntime();
    }

    @Test
    public void testSingleIOException()
    {
        final MultiException me = new MultiException();
        IOException io1 = new IOException("one");
        me.add(io1);

        IOException ioe = assertThrows(IOException.class, me::ifExceptionThrow);
        assertEquals(io1, ioe);

        assertThrows(MultiException.class, me::ifExceptionThrowMulti);

        RuntimeException re = assertThrows(RuntimeException.class, me::ifExceptionThrowRuntime);
        assertEquals(io1, re.getCause());
    }

    @Test
    public void testSingleRuntimeException()
    {
        final MultiException me = new MultiException();
        RuntimeException run = new RuntimeException("one");
        me.add(run);

        RuntimeException re1 = assertThrows(RuntimeException.class, me::ifExceptionThrow);
        assertEquals(run, re1);

        MultiException mex = assertThrows(MultiException.class, me::ifExceptionThrowMulti);
        assertEquals(me, mex);

        RuntimeException re2 = assertThrows(RuntimeException.class, me::ifExceptionThrowRuntime);
        assertEquals(run, re2);
    }

    private MultiException multiExceptionWithRtIo()
    {
        MultiException me = new MultiException();
        RuntimeException run = new RuntimeException("one");
        IOException io = new IOException("two");
        me.add(run);
        me.add(io);
        return me;
    }

    @Test
    public void testTwoSuppressedIOThenRuntime()
    {
        final MultiException me = new MultiException();
        IOException io = new IOException("one");
        RuntimeException run = new RuntimeException("two");
        me.add(io);
        me.add(run);

        MultiException mex1 = assertThrows(MultiException.class, me::ifExceptionThrow);
        assertEquals(me, mex1);

        MultiException mex2 = assertThrows(MultiException.class, me::ifExceptionThrowMulti);
        assertEquals(me, mex2);

        RuntimeException re1 = assertThrows(RuntimeException.class, me::ifExceptionThrowRuntime);
        assertEquals(me, re1.getCause());
    }

    @Test
    public void testTwoSuppressedRuntimeThenIO() throws Exception
    {
        final MultiException me = new MultiException();
        RuntimeException run = new RuntimeException("one");
        IOException io = new IOException("two");
        me.add(run);
        me.add(io);

        MultiException mex1 = assertThrows(MultiException.class, me::ifExceptionThrow);
        assertEquals(me, mex1);

        MultiException mex2 = assertThrows(MultiException.class, me::ifExceptionThrowMulti);
        assertEquals(me, mex2);

        RuntimeException re1 = assertThrows(RuntimeException.class, me::ifExceptionThrowRuntime);
        assertEquals(me, re1.getCause());
    }
}
