//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.server.internal;

import java.util.List;

import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.javax.common.UpgradeResponse;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;

public class UpgradeResponseAdapter implements UpgradeResponse
{
    private final ServletUpgradeResponse servletResponse;

    public UpgradeResponseAdapter(ServletUpgradeResponse servletResponse)
    {
        this.servletResponse = servletResponse;
    }

    @Override
    public String getAcceptedSubProtocol()
    {
        return this.servletResponse.getAcceptedSubProtocol();
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return this.servletResponse.getExtensions();
    }
}
