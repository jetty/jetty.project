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

package org.eclipse.jetty.quic.common.tls.ext;

import java.util.Objects;

import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.tls.ext.Extension;

public record QuicTransportParametersExtension(TransportParameters transportParameters) implements Extension
{
    public static final int CODE = 0x0039;

    public QuicTransportParametersExtension
    {
        Objects.requireNonNull(transportParameters);
    }

    @Override
    public int code()
    {
        return CODE;
    }
}
