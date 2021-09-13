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

package org.eclipse.jetty.http3.server.internal;

import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.internal.HTTP3Session;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP3SessionServer extends HTTP3Session implements Session.Server
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3SessionServer.class);

    public HTTP3SessionServer(ServerHTTP3Session session, Session.Server.Listener listener)
    {
        super(session, listener);
    }

    @Override
    public ServerHTTP3Session getProtocolSession()
    {
        return (ServerHTTP3Session)super.getProtocolSession();
    }

    @Override
    protected void writeFrame(long streamId, Frame frame, Callback callback)
    {
        getProtocolSession().writeFrame(streamId, frame, callback);
    }
}
