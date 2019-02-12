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

import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.jupiter.api.Test;

public class SuspenderTest {

    @Test
    public void testNotSuspended()
    {
        Suspender suspender = new Suspender();
        assertThat("Initially normal", suspender.isSuspended(), is(false));

        assertThat("No pending suspend to activate", suspender.activateRequestedSuspend(), is(false));
        assertThat("No pending suspend to activate", suspender.isSuspended(), is(false));

        assertThat("No pending suspend to resume", suspender.requestResume(), is(false));
        assertThat("No pending suspend to resume", suspender.isSuspended(), is(false));
    }

    @Test
    public void testSuspendThenResume()
    {
        Suspender suspender = new Suspender();
        assertThat("Initially suspended", suspender.isSuspended(), is(false));

        suspender.requestSuspend();
        assertThat("Requesting suspend doesn't take effect immediately", suspender.isSuspended(), is(false));

        assertThat("Resume from pending requires no followup", suspender.requestResume(), is(false));
        assertThat("Resume from pending requires no followup", suspender.isSuspended(), is(false));

        assertThat("Requested suspend was discarded", suspender.activateRequestedSuspend(), is(false));
        assertThat("Requested suspend was discarded", suspender.isSuspended(), is(false));
    }

    @Test
    public void testSuspendThenActivateThenResume()
    {
        Suspender suspender = new Suspender();
        assertThat("Initially suspended", suspender.isSuspended(), is(false));

        suspender.requestSuspend();
        assertThat("Requesting suspend doesn't take effect immediately", suspender.isSuspended(), is(false));

        assertThat("Suspend activated", suspender.activateRequestedSuspend(), is(true));
        assertThat("Suspend activated", suspender.isSuspended(), is(true));

        assertThat("Resumed", suspender.requestResume(), is(true));
        assertThat("Resumed", suspender.isSuspended(), is(false));
    }

    @Test
    public void testEof()
    {
        Suspender suspender = new Suspender();
        suspender.eof();
        assertThat(suspender.isSuspended(), is(true));

        assertThat(suspender.activateRequestedSuspend(), is(true));

        suspender.requestSuspend();
        assertThat(suspender.isSuspended(), is(true));

        assertThat(suspender.activateRequestedSuspend(), is(true));
        assertThat(suspender.isSuspended(), is(true));

        assertThat(suspender.requestResume(), is(false));
        assertThat(suspender.isSuspended(), is(true));
    }
}
