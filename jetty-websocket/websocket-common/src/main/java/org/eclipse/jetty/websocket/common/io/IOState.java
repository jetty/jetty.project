//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.ConnectionState;

/**
 * Simple state tracker for Input / Output and {@link ConnectionState}
 */
public class IOState
{
    private static final Logger LOG = Log.getLogger(IOState.class);
    private ConnectionState state;
    private final AtomicBoolean inputClosed;
    private final AtomicBoolean outputClosed;

    private final AtomicBoolean cleanClose;
    private final AtomicBoolean remoteCloseInitiated;
    private final AtomicBoolean localCloseInitiated;

    public IOState()
    {
        this.state = ConnectionState.CONNECTING;
        this.inputClosed = new AtomicBoolean(false);
        this.outputClosed = new AtomicBoolean(false);
        this.remoteCloseInitiated = new AtomicBoolean(false);
        this.localCloseInitiated = new AtomicBoolean(false);
        this.cleanClose = new AtomicBoolean(false);
    }

    public void assertInputOpen() throws IOException
    {
        if (isInputClosed())
        {
            throw new IOException("Connection input is closed");
        }
    }

    public void assertOutputOpen() throws IOException
    {
        if (isOutputClosed())
        {
            throw new IOException("Connection output is closed");
        }
    }

    public boolean awaitClosed(long duration)
    {
        return (isInputClosed() && isOutputClosed());
    }

    public ConnectionState getConnectionState()
    {
        return state;
    }

    public ConnectionState getState()
    {
        return state;
    }

    public boolean isClosed()
    {
        return (isInputClosed() && isOutputClosed());
    }

    public boolean isCloseInitiated()
    {
        return remoteCloseInitiated.get() || localCloseInitiated.get();
    }

    public boolean isInputClosed()
    {
        return inputClosed.get();
    }

    public boolean isOpen()
    {
        return (getState() != ConnectionState.CLOSED);
    }

    public boolean isOutputClosed()
    {
        return outputClosed.get();
    }

    /**
     * Test for if connection should disconnect or response on a close handshake.
     * 
     * @param incoming
     *            true if incoming close
     * @param close
     *            the close details.
     * @return true if connection should be disconnected now, or false if response to close should be issued.
     */
    public boolean onCloseHandshake(boolean incoming)
    {
        boolean in = inputClosed.get();
        boolean out = outputClosed.get();
        if (incoming)
        {
            in = true;
            this.inputClosed.set(true);

            if (!localCloseInitiated.get())
            {
                remoteCloseInitiated.set(true);
            }
        }
        else
        {
            out = true;
            this.outputClosed.set(true);

            if ( !remoteCloseInitiated.get() )
            {
                localCloseInitiated.set(true);
            }
        }

        LOG.debug("onCloseHandshake({}), input={}, output={}",incoming,in,out);

        if (in && out)
        {
            LOG.debug("Close Handshake satisfied, disconnecting");
            cleanClose.set(true);
            return true;
        }

        return false;
    }

    public void setConnectionState(ConnectionState connectionState)
    {
        this.state = connectionState;
    }

    public void setState(ConnectionState state)
    {
        this.state = state;
    }

    public boolean wasCleanClose()
    {
        return cleanClose.get();
    }

    public boolean wasLocalCloseInitiated()
    {
        return localCloseInitiated.get();
    }

    public boolean wasRemoteCloseInitiated()
    {
        return remoteCloseInitiated.get();
    }
}
