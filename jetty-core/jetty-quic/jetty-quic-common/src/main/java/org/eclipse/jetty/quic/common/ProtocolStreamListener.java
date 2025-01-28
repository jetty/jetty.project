//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.common;

import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.ResetFrame;
import org.eclipse.jetty.quic.api.frames.StopSendingFrame;

public class ProtocolStreamListener implements Stream.Listener
{
    private final Supplier<StreamEndPoint> endPoint;

    public ProtocolStreamListener(Supplier<StreamEndPoint> endPoint)
    {
        this.endPoint = endPoint;
    }

    @Override
    public void onDataAvailable(Stream stream)
    {
        endPoint.get().fillable();
    }

    @Override
    public void onStopSending(Stream stream, StopSendingFrame frame)
    {
        endPoint.get().shutdownOutput(frame.getApplicationErrorCode());
    }

    @Override
    public void onReset(Stream stream, ResetFrame frame)
    {
        // TODO: I don't think this is necessary:
        //  the peer is informing that *it* won't
        //  send more, but *we* could still send.
        endPoint.get().shutdownInput(frame.getApplicationErrorCode());
    }

    @Override
    public void onClose(Stream stream)
    {
        // TODO
        Stream.Listener.super.onClose(stream);
    }

    @Override
    public boolean onIdleTimeout(Stream stream, TimeoutException failure)
    {
        return endPoint.get().onIdleTimeout(failure);
    }

    @Override
    public void onFailure(Stream stream, Throwable failure)
    {
        // TODO: we should change the state.
        endPoint.get().close(failure);
    }
}
