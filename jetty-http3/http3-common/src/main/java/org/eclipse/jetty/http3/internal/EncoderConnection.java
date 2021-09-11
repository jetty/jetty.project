//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.internal;

import java.util.concurrent.Executor;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.EndPoint;

public class EncoderConnection extends AbstractConnection
{
    public static final int QPACK_ENCODER_STREAM_TYPE = 0x02;

    public EncoderConnection(EndPoint endPoint, Executor executor)
    {
        super(endPoint, executor);
    }

    @Override
    public void onFillable()
    {
    }
}
