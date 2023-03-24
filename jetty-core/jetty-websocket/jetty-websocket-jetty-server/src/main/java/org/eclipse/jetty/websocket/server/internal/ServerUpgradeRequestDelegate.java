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

package org.eclipse.jetty.websocket.server.internal;

import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.websocket.api.ExtensionConfig;
import org.eclipse.jetty.websocket.common.JettyExtensionConfig;
import org.eclipse.jetty.websocket.server.ServerUpgradeRequest;

public class ServerUpgradeRequestDelegate extends Request.Wrapper implements ServerUpgradeRequest
{
    public ServerUpgradeRequestDelegate(org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest wrapped)
    {
        super(wrapped);
    }

    @Override
    public org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest getWrapped()
    {
        return (org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest)super.getWrapped();
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return getWrapped().getExtensions().stream()
            .map(JettyExtensionConfig::new)
            .collect(Collectors.toList());
    }

    @Override
    public List<String> getSubProtocols()
    {
        return getWrapped().getSubProtocols();
    }

    @Override
    public boolean hasSubProtocol(String subProtocol)
    {
        return getWrapped().hasSubProtocol(subProtocol);
    }
}
