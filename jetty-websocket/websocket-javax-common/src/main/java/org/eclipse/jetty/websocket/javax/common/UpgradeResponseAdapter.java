//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
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
