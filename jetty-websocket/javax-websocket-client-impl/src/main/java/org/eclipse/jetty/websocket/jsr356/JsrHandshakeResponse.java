//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.jsr356;

import java.util.List;
import java.util.Map;
import javax.websocket.HandshakeResponse;

import org.eclipse.jetty.websocket.api.UpgradeResponse;

public class JsrHandshakeResponse implements HandshakeResponse
{
    private final Map<String, List<String>> headers;

    public JsrHandshakeResponse(UpgradeResponse response)
    {
        this.headers = response.getHeaders();
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        return this.headers;
    }
}
