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

package org.eclipse.jetty.websocket.core;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.io.WSConnection;

public class DummyLocalEndpoint implements WSLocalEndpoint
{
    @Override
    public Logger getLog()
    {
        return null;
    }

    @Override
    public void onOpen(WSConnection connection)
    {

    }

    @Override
    public void onClose(CloseStatus close)
    {

    }

    @Override
    public void onFrame(Frame frame)
    {

    }

    @Override
    public void onError(Throwable cause)
    {

    }

    @Override
    public void onText(Frame frame, Callback callback)
    {

    }

    @Override
    public void onBinary(Frame frame, Callback callback)
    {

    }

    @Override
    public void onContinuation(Frame frame, Callback callback)
    {

    }

    @Override
    public void onPing(ByteBuffer payload)
    {

    }

    @Override
    public void onPong(ByteBuffer payload)
    {

    }

    @Override
    public void start() throws Exception
    {

    }

    @Override
    public void stop() throws Exception
    {

    }

    @Override
    public boolean isRunning()
    {
        return false;
    }

    @Override
    public boolean isStarted()
    {
        return false;
    }

    @Override
    public boolean isStarting()
    {
        return false;
    }

    @Override
    public boolean isStopping()
    {
        return false;
    }

    @Override
    public boolean isStopped()
    {
        return false;
    }

    @Override
    public boolean isFailed()
    {
        return false;
    }

    @Override
    public void addLifeCycleListener(Listener listener)
    {

    }

    @Override
    public void removeLifeCycleListener(Listener listener)
    {

    }
}
