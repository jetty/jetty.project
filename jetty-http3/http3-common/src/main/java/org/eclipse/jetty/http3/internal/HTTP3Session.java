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

import java.util.Map;

import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.internal.parser.ParserListener;
import org.eclipse.jetty.quic.common.ProtocolSession;

public class HTTP3Session implements Session, ParserListener
{
    private final ProtocolSession session;
    private final Listener listener;

    public HTTP3Session(ProtocolSession session, Listener listener)
    {
        this.session = session;
        this.listener = listener;
    }

    public ProtocolSession getProtocolSession()
    {
        return session;
    }

    public Map<Long, Long> onPreface()
    {
        return listener.onPreface(this);
    }
}
