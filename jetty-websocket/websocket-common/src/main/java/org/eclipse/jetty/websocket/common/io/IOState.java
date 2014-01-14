//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.ConnectionState;

/**
 * Simple state tracker for Input / Output and {@link ConnectionState}.
 * <p>
 * Use the various known .on*() methods to trigger a state change.
 * <ul>
 * <li>{@link #onOpened()} - connection has been opened</li>
 * </ul>
 */
public class IOState
{
    /**
     * The source of a close handshake. (ie: who initiated it).
     */
    private static enum CloseHandshakeSource
    {
        /** No close handshake initiated (yet) */
        NONE,
        /** Local side initiated the close handshake */
        LOCAL,
        /** Remote side initiated the close handshake */
        REMOTE,
        /** An abnormal close situation (disconnect, timeout, etc...) */
        ABNORMAL;
    }

    public static interface ConnectionStateListener
    {
        public void onConnectionStateChange(ConnectionState state);
    }

    private static final Logger LOG = Log.getLogger(IOState.class);
    private ConnectionState state;
    private final List<ConnectionStateListener> listeners = new CopyOnWriteArrayList<>();

    private boolean inputAvailable;
    private boolean outputAvailable;
    private CloseHandshakeSource closeHandshakeSource;
    private CloseInfo closeInfo;
    private boolean cleanClose;

    /**
     * Create a new IOState, initialized to {@link ConnectionState#CONNECTING}
     */
    public IOState()
    {
        this.state = ConnectionState.CONNECTING;
        this.inputAvailable = false;
        this.outputAvailable = false;
        this.closeHandshakeSource = CloseHandshakeSource.NONE;
        this.closeInfo = null;
        this.cleanClose = false;
    }

    public void addListener(ConnectionStateListener listener)
    {
        listeners.add(listener);
    }

    public void assertInputOpen() throws IOException
    {
        if (!isInputAvailable())
        {
            throw new IOException("Connection input is closed");
        }
    }

    public void assertOutputOpen() throws IOException
    {
        if (!isOutputAvailable())
        {
            throw new IOException("Connection output is closed");
        }
    }

    public CloseInfo getCloseInfo()
    {
        return closeInfo;
    }

    public ConnectionState getConnectionState()
    {
        return state;
    }

    public boolean isClosed()
    {
        synchronized (state)
        {
            return (state == ConnectionState.CLOSED);
        }
    }

    public boolean isInputAvailable()
    {
        return inputAvailable;
    }

    public boolean isOpen()
    {
        return (getConnectionState() != ConnectionState.CLOSED);
    }

    public boolean isOutputAvailable()
    {
        return outputAvailable;
    }

    private void notifyStateListeners(ConnectionState state)
    {
        for (ConnectionStateListener listener : listeners)
        {
            listener.onConnectionStateChange(state);
        }
    }

    /**
     * A websocket connection has been disconnected for abnormal close reasons.
     * <p>
     * This is the low level disconnect of the socket. It could be the result of a normal close operation, from an IO error, or even from a timeout.
     */
    public void onAbnormalClose(CloseInfo close)
    {
        LOG.debug("onAbnormalClose({})",close);
        ConnectionState event = null;
        synchronized (this)
        {
            if (this.state == ConnectionState.CLOSED)
            {
                // already closed
                return;
            }

            if (this.state == ConnectionState.OPEN)
            {
                this.cleanClose = false;
            }

            this.state = ConnectionState.CLOSED;
            if (closeInfo == null)
                this.closeInfo = close;
            this.inputAvailable = false;
            this.outputAvailable = false;
            this.closeHandshakeSource = CloseHandshakeSource.ABNORMAL;
            event = this.state;
        }
        notifyStateListeners(event);
    }

    /**
     * A close handshake has been issued from the local endpoint
     */
    public void onCloseLocal(CloseInfo close)
    {
        ConnectionState event = null;
        ConnectionState initialState = this.state;
        LOG.debug("onCloseLocal({}) : {}",close,initialState);
        if (initialState == ConnectionState.CLOSED)
        {
            // already closed
            LOG.debug("already closed");
            return;
        }

        if (initialState == ConnectionState.CONNECTED)
        {
            // fast close. a local close request from end-user onConnected() method
            LOG.debug("FastClose in CONNECTED detected");
            // Force the state open (to allow read/write to endpoint)
            onOpened();
        }

        synchronized (this)
        {
            if (closeInfo == null)
                closeInfo = close;

            boolean in = inputAvailable;
            boolean out = outputAvailable;
            if (closeHandshakeSource == CloseHandshakeSource.NONE)
            {
                closeHandshakeSource = CloseHandshakeSource.LOCAL;
            }
            out = false;
            outputAvailable = false;

            LOG.debug("onCloseLocal(), input={}, output={}",in,out);

            if (!in && !out)
            {
                LOG.debug("Close Handshake satisfied, disconnecting");
                cleanClose = true;
                this.state = ConnectionState.CLOSED;
                event = this.state;
            }
            else if (this.state == ConnectionState.OPEN)
            {
                // We are now entering CLOSING (or half-closed)
                this.state = ConnectionState.CLOSING;
                event = this.state;
            }
        }

        // Only notify on state change events
        if (event != null)
        {
            LOG.debug("notifying state listeners: {}",event);
            notifyStateListeners(event);

            /*
            // if abnormal, we don't expect an answer.
            if (close.isAbnormal())
            {
                LOG.debug("Abnormal close, disconnecting");
                synchronized (this)
                {
                    state = ConnectionState.CLOSED;
                    cleanClose = false;
                    outputAvailable = false;
                    inputAvailable = false;
                    closeHandshakeSource = CloseHandshakeSource.ABNORMAL;
                    event = this.state;
                }
                notifyStateListeners(event);
                return;
            }
            */
        }
    }

    /**
     * A close handshake has been received from the remote endpoint
     */
    public void onCloseRemote(CloseInfo close)
    {
        LOG.debug("onCloseRemote({})",close);
        ConnectionState event = null;
        synchronized (this)
        {
            if (this.state == ConnectionState.CLOSED)
            {
                // already closed
                return;
            }

            if (closeInfo == null)
                closeInfo = close;

            boolean in = inputAvailable;
            boolean out = outputAvailable;
            if (closeHandshakeSource == CloseHandshakeSource.NONE)
            {
                closeHandshakeSource = CloseHandshakeSource.REMOTE;
            }
            in = false;
            inputAvailable = false;

            LOG.debug("onCloseRemote(), input={}, output={}",in,out);

            if (!in && !out)
            {
                LOG.debug("Close Handshake satisfied, disconnecting");
                cleanClose = true;
                state = ConnectionState.CLOSED;
                event = this.state;
            }
            else if (this.state == ConnectionState.OPEN)
            {
                // We are now entering CLOSING (or half-closed)
                this.state = ConnectionState.CLOSING;
                event = this.state;
            }
        }

        // Only notify on state change events
        if (event != null)
        {
            notifyStateListeners(event);
        }
    }

    /**
     * WebSocket has successfully upgraded, but the end-user onOpen call hasn't run yet.
     * <p>
     * This is an intermediate state between the RFC's {@link ConnectionState#CONNECTING} and {@link ConnectionState#OPEN}
     */
    public void onConnected()
    {
        if (this.state != ConnectionState.CONNECTING)
        {
            LOG.debug("Unable to set to connected, not in CONNECTING state: {}",this.state);
            return;
        }

        ConnectionState event = null;
        synchronized (this)
        {
            this.state = ConnectionState.CONNECTED;
            inputAvailable = false; // cannot read (yet)
            outputAvailable = true; // write allowed
            event = this.state;
        }
        notifyStateListeners(event);
    }

    /**
     * A websocket connection has failed its upgrade handshake, and is now closed.
     */
    public void onFailedUpgrade()
    {
        assert (this.state == ConnectionState.CONNECTING);
        ConnectionState event = null;
        synchronized (this)
        {
            this.state = ConnectionState.CLOSED;
            cleanClose = false;
            inputAvailable = false;
            outputAvailable = false;
            event = this.state;
        }
        notifyStateListeners(event);
    }

    /**
     * A websocket connection has finished its upgrade handshake, and is now open.
     */
    public void onOpened()
    {
        if (this.state == ConnectionState.OPEN)
        {
            // already opened
            return;
        }
        
        if (this.state != ConnectionState.CONNECTED)
        {
            LOG.debug("Unable to open, not in CONNECTED state: {}",this.state);
            return;
        }

        ConnectionState event = null;
        synchronized (this)
        {
            this.state = ConnectionState.OPEN;
            this.inputAvailable = true;
            this.outputAvailable = true;
            event = this.state;
        }
        notifyStateListeners(event);
    }

    /**
     * The local endpoint has reached a read EOF.
     * <p>
     * This could be a normal result after a proper close handshake, or even a premature close due to a connection disconnect.
     */
    public void onReadEOF()
    {
        ConnectionState event = null;
        synchronized (this)
        {
            if (this.state == ConnectionState.CLOSED)
            {
                // already closed
                return;
            }

            CloseInfo close = new CloseInfo(StatusCode.NO_CLOSE,"Read EOF");

            this.cleanClose = false;
            this.state = ConnectionState.CLOSED;
            if (closeInfo == null)
                this.closeInfo = close;
            this.inputAvailable = false;
            this.outputAvailable = false;
            this.closeHandshakeSource = CloseHandshakeSource.ABNORMAL;
            event = this.state;
        }
        notifyStateListeners(event);
    }

    public boolean wasAbnormalClose()
    {
        return closeHandshakeSource == CloseHandshakeSource.ABNORMAL;
    }

    public boolean wasCleanClose()
    {
        return cleanClose;
    }

    public boolean wasLocalCloseInitiated()
    {
        return closeHandshakeSource == CloseHandshakeSource.LOCAL;
    }

    public boolean wasRemoteCloseInitiated()
    {
        return closeHandshakeSource == CloseHandshakeSource.REMOTE;
    }
}
