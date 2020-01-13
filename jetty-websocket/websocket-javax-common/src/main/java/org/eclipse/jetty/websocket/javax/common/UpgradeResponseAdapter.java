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

package org.eclipse.jetty.websocket.javax.common;

import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.websocket.core.ExtensionConfig;

public class UpgradeResponseAdapter implements UpgradeResponse
{
    private final String acceptedSubProtocol;
    private final List<ExtensionConfig> extensions;

    public UpgradeResponseAdapter()
    {
        this(null, Collections.emptyList());
    }

    public UpgradeResponseAdapter(String acceptedSubProtocol, List<ExtensionConfig> extensions)
    {
        this.acceptedSubProtocol = acceptedSubProtocol;
        this.extensions = extensions;
    }

    /**
     * Get the accepted WebSocket protocol.
     *
     * @return the accepted WebSocket protocol.
     */
    @Override
    public String getAcceptedSubProtocol()
    {
        return acceptedSubProtocol;
    }

    /**
     * Get the list of extensions that should be used for the websocket.
     *
     * @return the list of negotiated extensions to use.
     */
    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return extensions;
    }
}
