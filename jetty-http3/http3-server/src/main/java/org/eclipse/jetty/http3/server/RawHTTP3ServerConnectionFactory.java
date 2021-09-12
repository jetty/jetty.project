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

package org.eclipse.jetty.http3.server;

import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.server.HttpConfiguration;

public class RawHTTP3ServerConnectionFactory extends AbstractHTTP3ServerConnectionFactory
{
    public RawHTTP3ServerConnectionFactory(Session.Server.Listener listener)
    {
        this(new HttpConfiguration(), listener);
    }

    public RawHTTP3ServerConnectionFactory(HttpConfiguration httpConfiguration, Session.Server.Listener listener)
    {
        super(httpConfiguration, listener);
    }
}
