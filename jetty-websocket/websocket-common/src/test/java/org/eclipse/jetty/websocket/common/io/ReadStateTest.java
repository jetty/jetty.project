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

package org.eclipse.jetty.websocket.common.io;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ReadStateTest
{
    @Test
    public void testReading()
    {
        ReadState readState = new ReadState();
        assertThat("Initially reading", readState.isReading(), is(true));

        assertThat("Action is reading", readState.getAction(BufferUtil.toBuffer("content")), is(ReadState.Action.PARSE));
        assertThat("No prior suspending", readState.isSuspended(), is(false));

        assertThrows(IllegalStateException.class, readState::resume, "No suspending to resume");
        assertThat("No suspending to resume", readState.isSuspended(), is(false));
    }

    @Test
    public void testSuspendingThenResume()
    {
        ReadState readState = new ReadState();
        assertThat("Initially reading", readState.isReading(), is(true));

        readState.suspending();
        assertThat("Suspending doesn't take effect immediately", readState.isSuspended(), is(false));

        assertNull(readState.resume());
        assertThat("Action is reading", readState.getAction(BufferUtil.toBuffer("content")), is(ReadState.Action.PARSE));
        assertThat("Suspending was discarded", readState.isSuspended(), is(false));
    }

    @Test
    public void testSuspendingThenSuspendThenResume()
    {
        ReadState readState = new ReadState();
        assertThat("Initially reading", readState.isReading(), is(true));

        readState.suspending();
        assertThat("Suspending doesn't take effect immediately", readState.isSuspended(), is(false));

        ByteBuffer content = BufferUtil.toBuffer("content");
        assertThat(readState.getAction(content), is(ReadState.Action.SUSPEND));
        assertThat("Suspended", readState.isSuspended(), is(true));

        assertThat(readState.resume(), is(content));
        assertThat("Resumed", readState.isSuspended(), is(false));
    }

    @Test
    public void testEof()
    {
        ReadState readState = new ReadState();
        ByteBuffer content = BufferUtil.toBuffer("content");
        readState.eof();

        assertThat(readState.isReading(), is(false));
        assertThat(readState.isSuspended(), is(true));
        assertThrows(IllegalStateException.class, readState::suspending);
        assertThat(readState.getAction(content), is(ReadState.Action.EOF));
        assertThrows(IllegalStateException.class, readState::resume);
    }
}
