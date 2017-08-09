//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import org.eclipse.jetty.websocket.core.AtomicConnectionState;
import org.junit.Test;

public class AtomicConnectionStateTest
{
    @Test
    public void testNormalFlow()
    {
        AtomicConnectionState state = new AtomicConnectionState();
        assertThat("Connecting", state.onConnecting(), is(true));
        assertThat("Connected", state.onConnected(), is(true));
        assertThat("Open", state.onOpen(), is(true));
        assertThat("Closing", state.onClosing(), is(true));
        assertThat("Closed", state.onClosed(), is(true));
    }
    
    @Test
    public void testSkipOpen()
    {
        AtomicConnectionState state = new AtomicConnectionState();
        assertThat("Connecting", state.onConnecting(), is(true));
        assertThat("Connected", state.onConnected(), is(true));
        // SKIP assertThat("Open", state.onOpen(), is(true));
        assertThat("Closing", state.onClosing(), is(true));
        assertThat("Closed", state.onClosed(), is(true));
    }
    
    @Test
    public void testClosingTwice()
    {
        AtomicConnectionState state = new AtomicConnectionState();
        assertThat("Connecting", state.onConnecting(), is(true));
        assertThat("Connected", state.onConnected(), is(true));
        // SKIP assertThat("Open", state.onOpen(), is(true));
        assertThat("Closing", state.onClosing(), is(true));
        assertThat("Closing", state.onClosing(), is(false));
        assertThat("Closed", state.onClosed(), is(true));
    }
}
