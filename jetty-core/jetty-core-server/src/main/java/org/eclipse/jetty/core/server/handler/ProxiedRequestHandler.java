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

package org.eclipse.jetty.core.server.handler;

import java.net.SocketAddress;

import org.eclipse.jetty.core.server.ConnectionMetaData;
import org.eclipse.jetty.core.server.Handler;
import org.eclipse.jetty.core.server.Request;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.HostPort;

public class ProxiedRequestHandler extends Handler.Wrapper
{
    @Override
    public Processor offer(Request request) throws Exception
    {
        ConnectionMetaData proxiedFor = new ConnectionMetaData.Wrapper(request.getConnectionMetaData())
        {
            @Override
            public boolean isSecure()
            {
                // TODO replace with value determined from headers
                return super.isSecure();
            }

            @Override
            public SocketAddress getRemoteAddress()
            {
                // TODO replace with value determined from headers
                return super.getRemoteAddress();
            }

            @Override
            public SocketAddress getLocalAddress()
            {
                // TODO replace with value determined from headers
                return super.getLocalAddress();
            }

            @Override
            public HostPort getServerAuthority()
            {
                // TODO replace with value determined from headers
                return super.getServerAuthority();
            }
        };

        Request.Wrapper wrapper = new Request.Wrapper(request)
        {
            @Override
            public HttpURI getHttpURI()
            {
                // TODO replace with any change in authority
                return super.getHttpURI();
            }

            @Override
            public ConnectionMetaData getConnectionMetaData()
            {
                return proxiedFor;
            }
        };
        return Processor.wrap(super.offer(wrapper), wrapper);
    }
}
