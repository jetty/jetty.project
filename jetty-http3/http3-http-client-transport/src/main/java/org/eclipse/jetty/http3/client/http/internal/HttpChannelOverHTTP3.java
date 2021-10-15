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

package org.eclipse.jetty.http3.client.http.internal;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpReceiver;
import org.eclipse.jetty.client.HttpSender;

public class HttpChannelOverHTTP3 extends HttpChannel
{
    public HttpChannelOverHTTP3(HttpDestination destination)
    {
        super(destination);
    }

    @Override
    protected HttpSender getHttpSender()
    {
        return null;
    }

    @Override
    protected HttpReceiver getHttpReceiver()
    {
        return null;
    }

    @Override
    public void send(HttpExchange exchange)
    {

    }

    @Override
    public void release()
    {

    }
}
