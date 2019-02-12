//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReadStateTest
{
    @Test
    public void testReading()
    {
        ReadState readState = new ReadState();
        assertThat("Initially reading", readState.isReading(), is(true));

        assertThat("No prior suspending", readState.suspend(), is(false));
        assertThat("No prior suspending", readState.isSuspended(), is(false));

        assertThrows(IllegalStateException.class, readState::resume, "No suspending to resume");
        assertThat("No suspending to resume", readState.isSuspended(), is(false));
    }

    @Test
    public void testSuspendingThenResume()
    {
        ReadState readState = new ReadState();
        assertThat("Initially reading", readState.isReading(), is(true));

        assertTrue(readState.suspending());
        assertThat("Suspending doesn't take effect immediately", readState.isSuspended(), is(false));

        assertThat("Resume from suspending requires no followup", readState.resume(), is(false));
        assertThat("Resume from suspending requires no followup", readState.isSuspended(), is(false));

        assertThat("Suspending was discarded", readState.suspend(), is(false));
        assertThat("Suspending was discarded", readState.isSuspended(), is(false));
    }

    @Test
    public void testSuspendingThenSuspendThenResume()
    {
        ReadState readState = new ReadState();
        assertThat("Initially reading", readState.isReading(), is(true));

        assertThat(readState.suspending(), is(true));
        assertThat("Suspending doesn't take effect immediately", readState.isSuspended(), is(false));

        assertThat("Suspended", readState.suspend(), is(true));
        assertThat("Suspended", readState.isSuspended(), is(true));

        assertThat("Resumed", readState.resume(), is(true));
        assertThat("Resumed", readState.isSuspended(), is(false));
    }

    @Test
    public void testEof()
    {
        ReadState readState = new ReadState();
        readState.eof();

        assertThat(readState.isReading(), is(false));
        assertThat(readState.isSuspended(), is(true));
        assertThat(readState.suspend(), is(true));

        assertThat(readState.suspending(), is(false));
        assertThat(readState.isSuspended(), is(true));

        assertThat(readState.suspend(), is(true));
        assertThat(readState.isSuspended(), is(true));

        assertThat(readState.resume(), is(false));
        assertThat(readState.isSuspended(), is(true));
    }
}
