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

package org.eclipse.jetty.websocket.common;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import org.junit.Test;

public class AtomicCloseTest
{
    @Test
    public void testCloseLocalNormal()
    {
        AtomicClose state = new AtomicClose();
        
        assertThat("State", state.get(), is(AtomicClose.State.NONE));
        assertThat("Local 1st", state.onLocal(), is(true));
        assertThat("State", state.get(), is(AtomicClose.State.LOCAL));
        assertThat("Local 2nd", state.onLocal(), is(false));
        assertThat("State", state.get(), is(AtomicClose.State.LOCAL));
        assertThat("Remote 1st", state.onRemote(), is(false));
        assertThat("State", state.get(), is(AtomicClose.State.LOCAL));
    }
    
    @Test
    public void testCloseRemoteNormal()
    {
        AtomicClose state = new AtomicClose();
        
        assertThat("State", state.get(), is(AtomicClose.State.NONE));
        assertThat("Remote 1st", state.onRemote(), is(true));
        assertThat("State", state.get(), is(AtomicClose.State.REMOTE));
        assertThat("Local 1st", state.onRemote(), is(false));
        assertThat("State", state.get(), is(AtomicClose.State.REMOTE));
        assertThat("Remote 2nd", state.onRemote(), is(false));
        assertThat("State", state.get(), is(AtomicClose.State.REMOTE));
    }
}
