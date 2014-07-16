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

import java.io.EOFException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.CloseStatus;
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

    /** 
     * Is input on websocket available (for reading frames).
     * Used to determine close handshake completion, and track half-close states
     */
    private boolean inputAvailable;
    /** 
     * Is output on websocket available (for writing frames).
     * Used to determine close handshake completion, and track half-closed states.
     */
    private boolean outputAvailable;
    /** 
     * Initiator of the close handshake.
     * Used to determine who initiated a close handshake for reply reasons.
     */
    private CloseHandshakeSource closeHandshakeSource;
    /**
     * The close info for the initiator of the close handshake.
     * It is possible in abnormal close scenarios to have a different
     * final close info that is used to notify the WS-Endpoint's onClose()
     * events with.
     */
    private CloseInfo closeInfo;
    /**
     * Atomic reference to the final close info.
     * This can only be set once, and is used for the WS-Endpoint's onClose()
     * event.
     */
    private AtomicReference<CloseInfo> finalClose = new AtomicReference<>();
    /**
     * Tracker for if the close handshake was completed successfully by
     * both sides.  False if close was sudden or abnormal.
     */
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
        CloseInfo ci = finalClose.get();
        if (ci != null)
        {
            return ci;
        }
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
        if (LOG.isDebugEnabled())
            LOG.debug("Notify State Listeners: {}",state);
        for (ConnectionStateListener listener : listeners)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("{}.onConnectionStateChange({})",listener.getClass().getSimpleName(),state.name());
            }
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
        if (LOG.isDebugEnabled())
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
            finalClose.compareAndSet(null,close);
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
        ConnectionState abnormalEvent = null;
        ConnectionState initialState = this.state;
        if (LOG.isDebugEnabled())
            LOG.debug("onCloseLocal({}) : {}",close,initialState);
        if (initialState == ConnectionState.CLOSED)
        {
            // already closed
            LOG.debug("already closed");
            return;
        }

        if (initialState == ConnectionState.CONNECTED)
        {
            // fast close. a local close request from end-user onConnect/onOpen method
            LOG.debug("FastClose in CONNECTED detected");
            // Force the state open (to allow read/write to endpoint)
            onOpened();
            if (LOG.isDebugEnabled())
                LOG.debug("FastClose continuing with Closure");
        }

        synchronized (this)
        {
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
                finalClose.compareAndSet(null,close);
                event = this.state;
            }
            else if (this.state == ConnectionState.OPEN)
            {
                // We are now entering CLOSING (or half-closed)
                this.state = ConnectionState.CLOSING;
                event = this.state;
                
                // if abnormal, we don't expect an answer.
                if (close.isAbnormal())
                {
                    abnormalEvent = ConnectionState.CLOSED;
                    finalClose.compareAndSet(null,close);
                    cleanClose = false;
                    outputAvailable = false;
                    inputAvailable = false;
                    closeHandshakeSource = CloseHandshakeSource.ABNORMAL;
                }
            }
        }

        // Only notify on state change events
        if (event != null)
        {
            notifyStateListeners(event);
            
            if(abnormalEvent != null) {
                notifyStateListeners(abnormalEvent);
            }
        }
    }

    /**
     * A close handshake has been received from the remote endpoint
     */
    public void onCloseRemote(CloseInfo close)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onCloseRemote({})",close);
        ConnectionState event = null;
        synchronized (this)
        {
            if (this.state == ConnectionState.CLOSED)
            {
                // already closed
                return;
            }

            closeInfo = close;

            boolean in = inputAvailable;
            boolean out = outputAvailable;
            if (closeHandshakeSource == CloseHandshakeSource.NONE)
            {
                closeHandshakeSource = CloseHandshakeSource.REMOTE;
            }
            in = false;
            inputAvailable = false;

            if (LOG.isDebugEnabled())
                LOG.debug("onCloseRemote(), input={}, output={}",in,out);

            if (!in && !out)
            {
                LOG.debug("Close Handshake satisfied, disconnecting");
                cleanClose = true;
                state = ConnectionState.CLOSED;
                finalClose.compareAndSet(null,close);
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
        ConnectionState event = null;
        synchronized (this)
        {
            if (this.state != ConnectionState.CONNECTING)
            {
                LOG.debug("Unable to set to connected, not in CONNECTING state: {}",this.state);
                return;
            }

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
        ConnectionState event = null;
        synchronized (this)
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

            this.state = ConnectionState.OPEN;
            this.inputAvailable = true;
            this.outputAvailable = true;
            event = this.state;
        }
        notifyStateListeners(event);
    }

    /**
     * The local endpoint has reached a read failure.
     * <p>
     * This could be a normal result after a proper close handshake, or even a premature close due to a connection disconnect.
     */
    public void onReadFailure(Throwable t)
    {
        ConnectionState event = null;
        synchronized (this)
        {
            if (this.state == ConnectionState.CLOSED)
            {
                // already closed
                return;
            }

         // Build out Close Reason
            String reason = "WebSocket Read Failure";
            if (t instanceof EOFException)
            {
                reason = "WebSocket Read EOF";
                Throwable cause = t.getCause();
                if ((cause != null) && (StringUtil.isNotBlank(cause.getMessage())))
                {
                    reason = "EOF: " + cause.getMessage();
                }
            }
            else
            {
                if (StringUtil.isNotBlank(t.getMessage()))
                {
                    reason = t.getMessage();
                }
            }

            reason = CloseStatus.trimMaxReasonLength(reason);
            CloseInfo close = new CloseInfo(StatusCode.ABNORMAL,reason);

            finalClose.compareAndSet(null,close);

            this.cleanClose = false;
            this.state = ConnectionState.CLOSED;
            this.closeInfo = close;
            this.inputAvailable = false;
            this.outputAvailable = false;
            this.closeHandshakeSource = CloseHandshakeSource.ABNORMAL;
            event = this.state;
        }
        notifyStateListeners(event);
    }

    /**
     * The local endpoint has reached a write failure.
     * <p>
     * A low level I/O failure, or even a jetty side EndPoint close (from idle timeout) are a few scenarios
     */
    public void onWriteFailure(Throwable t)
    {
        ConnectionState event = null;
        synchronized (this)
        {
            if (this.state == ConnectionState.CLOSED)
            {
                // already closed
                return;
            }

            // Build out Close Reason
            String reason = "WebSocket Write Failure";
            if (t instanceof EOFException)
            {
                reason = "WebSocket Write EOF";
                Throwable cause = t.getCause();
                if ((cause != null) && (StringUtil.isNotBlank(cause.getMessage())))
                {
                    reason = "EOF: " + cause.getMessage();
                }
            }
            else
            {
                if (StringUtil.isNotBlank(t.getMessage()))
                {
                    reason = t.getMessage();
                }
            }

            reason = CloseStatus.trimMaxReasonLength(reason);
            CloseInfo close = new CloseInfo(StatusCode.ABNORMAL,reason);

            finalClose.compareAndSet(null,close);

            this.cleanClose = false;
            this.state = ConnectionState.CLOSED;
            this.inputAvailable = false;
            this.outputAvailable = false;
            this.closeHandshakeSource = CloseHandshakeSource.ABNORMAL;
            event = this.state;
        }
        notifyStateListeners(event);
    }

    public void onDisconnected()
    {
        ConnectionState event = null;
        synchronized (this)
        {
            if (this.state == ConnectionState.CLOSED)
            {
                // already closed
                return;
            }

            CloseInfo close = new CloseInfo(StatusCode.ABNORMAL,"Disconnected");

            this.cleanClose = false;
            this.state = ConnectionState.CLOSED;
            this.closeInfo = close;
            this.inputAvailable = false;
            this.outputAvailable = false;
            this.closeHandshakeSource = CloseHandshakeSource.ABNORMAL;
            event = this.state;
        }
        notifyStateListeners(event);
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append(this.getClass().getSimpleName());
        str.append("@").append(Integer.toHexString(hashCode()));
        str.append("[").append(state);
        str.append(',');
        if (!inputAvailable)
        {
            str.append('!');
        }
        str.append("in,");
        if (!outputAvailable)
        {
            str.append('!');
        }
        str.append("out");
        if ((state == ConnectionState.CLOSED) || (state == ConnectionState.CLOSING))
        {
            CloseInfo ci = finalClose.get();
            if (ci != null)
            {
                str.append(",finalClose=").append(ci);
            }
            else
            {
                str.append(",close=").append(closeInfo);
            }
            str.append(",clean=").append(cleanClose);
            str.append(",closeSource=").append(closeHandshakeSource);
        }
        str.append(']');
        return str.toString();
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
