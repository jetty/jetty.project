//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

    private final AtomicBoolean inputAvailable;
    private final AtomicBoolean outputAvailable;
    private final AtomicReference<CloseHandshakeSource> closeHandshakeSource;
    private final AtomicReference<CloseInfo> closeInfo;

    private final AtomicBoolean cleanClose;

    /**
     * Create a new IOState, initialized to {@link ConnectionState#CONNECTING}
     */
    public IOState()
    {
        this.state = ConnectionState.CONNECTING;
        this.inputAvailable = new AtomicBoolean(false);
        this.outputAvailable = new AtomicBoolean(false);
        this.closeHandshakeSource = new AtomicReference<>(CloseHandshakeSource.NONE);
        this.closeInfo = new AtomicReference<>();
        this.cleanClose = new AtomicBoolean(false);
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
        return closeInfo.get();
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
        return inputAvailable.get();
    }

    public boolean isOpen()
    {
        return (getConnectionState() != ConnectionState.CLOSED);
    }

    public boolean isOutputAvailable()
    {
        return outputAvailable.get();
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
        ConnectionState event = null;
        synchronized (this.state)
        {
            if (this.state == ConnectionState.CLOSED)
            {
                // already closed
                return;
            }

            if (this.state == ConnectionState.OPEN)
            {
                this.cleanClose.set(false);
            }

            this.state = ConnectionState.CLOSED;
            this.closeInfo.compareAndSet(null,close);
            this.inputAvailable.set(false);
            this.outputAvailable.set(false);
            this.closeHandshakeSource.set(CloseHandshakeSource.ABNORMAL);
            event = this.state;
        }
        notifyStateListeners(event);
    }

    /**
     * A close handshake has been issued from the local endpoint
     */
    public void onCloseLocal(CloseInfo close)
    {
        LOG.debug("onCloseLocal({})",close);
        ConnectionState event = null;
        ConnectionState initialState = this.state;
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

        synchronized (this.state)
        {
            closeInfo.compareAndSet(null,close);

            boolean in = inputAvailable.get();
            boolean out = outputAvailable.get();
            closeHandshakeSource.compareAndSet(CloseHandshakeSource.NONE,CloseHandshakeSource.LOCAL);
            out = false;
            outputAvailable.set(false);

            LOG.debug("onCloseLocal(), input={}, output={}",in,out);

            if (!in && !out)
            {
                LOG.debug("Close Handshake satisfied, disconnecting");
                cleanClose.set(true);
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
        
        LOG.debug("event = {}",event);

        // Only notify on state change events
        if (event != null)
        {
            LOG.debug("notifying state listeners: {}",event);
            notifyStateListeners(event);

            // if harsh, we don't expect an answer.
            if (close.isHarsh())
            {
                LOG.debug("Harsh close, disconnecting");
                synchronized (this.state)
                {
                    this.state = ConnectionState.CLOSED;
                    cleanClose.set(false);
                    outputAvailable.set(false);
                    inputAvailable.set(false);
                    this.closeHandshakeSource.set(CloseHandshakeSource.ABNORMAL);
                    event = this.state;
                }
                notifyStateListeners(event);
                return;
            }
        }
    }

    /**
     * A close handshake has been received from the remote endpoint
     */
    public void onCloseRemote(CloseInfo close)
    {
        LOG.debug("onCloseRemote({})",close);
        ConnectionState event = null;
        synchronized (this.state)
        {
            if (this.state == ConnectionState.CLOSED)
            {
                // already closed
                return;
            }

            closeInfo.compareAndSet(null,close);

            boolean in = inputAvailable.get();
            boolean out = outputAvailable.get();
            closeHandshakeSource.compareAndSet(CloseHandshakeSource.NONE,CloseHandshakeSource.REMOTE);
            in = false;
            inputAvailable.set(false);

            LOG.debug("onCloseRemote(), input={}, output={}",in,out);

            if (!in && !out)
            {
                LOG.debug("Close Handshake satisfied, disconnecting");
                cleanClose.set(true);
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
        synchronized (this.state)
        {
            this.state = ConnectionState.CONNECTED;
            this.inputAvailable.set(false); // cannot read (yet)
            this.outputAvailable.set(true); // write allowed
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
        synchronized (this.state)
        {
            this.state = ConnectionState.CLOSED;
            this.cleanClose.set(false);
            this.inputAvailable.set(false);
            this.outputAvailable.set(false);
            event = this.state;
        }
        notifyStateListeners(event);
    }

    /**
     * A websocket connection has finished its upgrade handshake, and is now open.
     */
    public void onOpened()
    {
        if (this.state != ConnectionState.CONNECTED)
        {
            LOG.debug("Unable to open, not in CONNECTED state: {}",this.state);
            return;
        }

        assert (this.state == ConnectionState.CONNECTED);

        ConnectionState event = null;
        synchronized (this.state)
        {
            this.state = ConnectionState.OPEN;
            this.inputAvailable.set(true);
            this.outputAvailable.set(true);
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
        synchronized (this.state)
        {
            if (this.state == ConnectionState.CLOSED)
            {
                // already closed
                return;
            }

            CloseInfo close = new CloseInfo(StatusCode.NO_CLOSE,"Read EOF");

            this.cleanClose.set(false);
            this.state = ConnectionState.CLOSED;
            this.closeInfo.compareAndSet(null,close);
            this.inputAvailable.set(false);
            this.outputAvailable.set(false);
            this.closeHandshakeSource.set(CloseHandshakeSource.ABNORMAL);
            event = this.state;
        }
        notifyStateListeners(event);
    }

    public boolean wasAbnormalClose()
    {
        return closeHandshakeSource.get() == CloseHandshakeSource.ABNORMAL;
    }

    public boolean wasCleanClose()
    {
        return cleanClose.get();
    }

    public boolean wasLocalCloseInitiated()
    {
        return closeHandshakeSource.get() == CloseHandshakeSource.LOCAL;
    }

    public boolean wasRemoteCloseInitiated()
    {
        return closeHandshakeSource.get() == CloseHandshakeSource.REMOTE;
    }
}
