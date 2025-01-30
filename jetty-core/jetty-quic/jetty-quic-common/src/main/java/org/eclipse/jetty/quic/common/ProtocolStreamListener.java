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
    public boolean onIdleTimeout(Stream stream, TimeoutException failure)
    {
        return endPoint.get().onIdleTimeout(failure);
    }

    @Override
    public void onFailure(Stream stream, Throwable failure)
    {
        endPoint.get().onFailure(failure);
    }
}
