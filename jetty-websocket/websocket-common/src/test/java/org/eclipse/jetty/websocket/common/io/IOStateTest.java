//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import static org.junit.Assert.assertThat;

import java.util.LinkedList;

import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.ConnectionState;
import org.junit.Test;

public class IOStateTest
{
    public static class StateTracker implements IOState.ConnectionStateListener
    {
        private LinkedList<ConnectionState> transitions = new LinkedList<>();

        public void assertTransitions(ConnectionState ...states)
        {
            assertThat("Transitions.count",transitions.size(),is(states.length));
            if (states.length > 0)
            {
                int len = states.length;
                for (int i = 0; i < len; i++)
                {
                    assertThat("Transitions[" + i + "]",transitions.get(i),is(states[i]));
                }
            }
        }

        public LinkedList<ConnectionState> getTransitions()
        {
            return transitions;
        }

        @Override
        public void onConnectionStateChange(ConnectionState state)
        {
            transitions.add(state);
        }
    }

    private void assertCleanClose(IOState state, boolean expected)
    {
        assertThat("State.cleanClose",state.wasCleanClose(),is(expected));
    }

    private void assertInputAvailable(IOState state, boolean available)
    {
        assertThat("State.inputAvailable",state.isInputAvailable(),is(available));
    }

    private void assertLocalInitiated(IOState state, boolean expected)
    {
        assertThat("State.localCloseInitiated",state.wasLocalCloseInitiated(),is(expected));
    }

    private void assertOutputAvailable(IOState state, boolean available)
    {
        assertThat("State.outputAvailable",state.isOutputAvailable(),is(available));
    }

    private void assertRemoteInitiated(IOState state, boolean expected)
    {
        assertThat("State.remoteCloseInitiated",state.wasRemoteCloseInitiated(),is(expected));
    }

    private void assertState(IOState state, ConnectionState expectedState)
    {
        assertThat("State",state.getConnectionState(),is(expectedState));
    }

    @Test
    public void testConnectAbnormalClose()
    {
        IOState state = new IOState();
        StateTracker tracker = new StateTracker();
        state.addListener(tracker);
        assertState(state,ConnectionState.CONNECTING);

        // connect
        state.onConnected();
        assertInputAvailable(state,false);
        assertOutputAvailable(state,true);

        // open
        state.onOpened();
        assertInputAvailable(state,true);
        assertOutputAvailable(state,true);

        // disconnect
        state.onAbnormalClose(new CloseInfo(StatusCode.NO_CLOSE,"Oops"));

        assertInputAvailable(state,false);
        assertOutputAvailable(state,false);
        tracker.assertTransitions(ConnectionState.CONNECTED,ConnectionState.OPEN,ConnectionState.CLOSED);
        assertState(state,ConnectionState.CLOSED);

        // not clean
        assertCleanClose(state,false);
        assertLocalInitiated(state,false);
        assertRemoteInitiated(state,false);
    }

    @Test
    public void testConnectCloseLocalInitiated()
    {
        IOState state = new IOState();
        StateTracker tracker = new StateTracker();
        state.addListener(tracker);
        assertState(state,ConnectionState.CONNECTING);

        // connect
        state.onConnected();
        assertInputAvailable(state,false);
        assertOutputAvailable(state,true);

        // open
        state.onOpened();
        assertInputAvailable(state,true);
        assertOutputAvailable(state,true);

        // close (local initiated)
        state.onCloseLocal(new CloseInfo(StatusCode.NORMAL,"Hi"));
        assertInputAvailable(state,true);
        assertOutputAvailable(state,false);
        assertState(state,ConnectionState.CLOSING);

        // close (remote response)
        state.onCloseRemote(new CloseInfo(StatusCode.NORMAL,"Hi"));

        assertInputAvailable(state,false);
        assertOutputAvailable(state,false);
        tracker.assertTransitions(ConnectionState.CONNECTED,ConnectionState.OPEN,ConnectionState.CLOSING,ConnectionState.CLOSED);
        assertState(state,ConnectionState.CLOSED);

        // not clean
        assertCleanClose(state,true);
        assertLocalInitiated(state,true);
        assertRemoteInitiated(state,false);
    }

    @Test
    public void testConnectCloseRemoteInitiated()
    {
        IOState state = new IOState();
        StateTracker tracker = new StateTracker();
        state.addListener(tracker);
        assertState(state,ConnectionState.CONNECTING);

        // connect
        state.onConnected();
        assertInputAvailable(state,false);
        assertOutputAvailable(state,true);

        // open
        state.onOpened();
        assertInputAvailable(state,true);
        assertOutputAvailable(state,true);

        // close (remote initiated)
        state.onCloseRemote(new CloseInfo(StatusCode.NORMAL,"Hi"));
        assertInputAvailable(state,false);
        assertOutputAvailable(state,true);
        assertState(state,ConnectionState.CLOSING);

        // close (local response)
        state.onCloseLocal(new CloseInfo(StatusCode.NORMAL,"Hi"));

        assertInputAvailable(state,false);
        assertOutputAvailable(state,false);
        tracker.assertTransitions(ConnectionState.CONNECTED,ConnectionState.OPEN,ConnectionState.CLOSING,ConnectionState.CLOSED);
        assertState(state,ConnectionState.CLOSED);

        // not clean
        assertCleanClose(state,true);
        assertLocalInitiated(state,false);
        assertRemoteInitiated(state,true);
    }

    @Test
    public void testConnectFailure()
    {
        IOState state = new IOState();
        StateTracker tracker = new StateTracker();
        state.addListener(tracker);
        assertState(state,ConnectionState.CONNECTING);

        // fail upgrade
        state.onFailedUpgrade();

        tracker.assertTransitions(ConnectionState.CLOSED);
        assertState(state,ConnectionState.CLOSED);

        assertInputAvailable(state,false);
        assertOutputAvailable(state,false);

        // not clean
        assertCleanClose(state,false);
        assertLocalInitiated(state,false);
        assertRemoteInitiated(state,false);
    }

    @Test
    public void testInit()
    {
        IOState state = new IOState();
        StateTracker tracker = new StateTracker();
        state.addListener(tracker);
        assertState(state,ConnectionState.CONNECTING);

        // do nothing

        tracker.assertTransitions();
        assertState(state,ConnectionState.CONNECTING);

        // not connected yet
        assertInputAvailable(state,false);
        assertOutputAvailable(state,false);

        // no close yet
        assertCleanClose(state,false);
        assertLocalInitiated(state,false);
        assertRemoteInitiated(state,false);
    }
}
