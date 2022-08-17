//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.server.internal;

import java.util.Collections;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;

public class WebSocketHttpFieldsWrapper extends HttpFieldsWrapper
{
    private final WebSocketNegotiation _negotiation;
    private final ServerUpgradeResponse _response;

    public WebSocketHttpFieldsWrapper(Mutable fields, ServerUpgradeResponse response, WebSocketNegotiation negotiation)
    {
        super(fields);
        _negotiation = negotiation;
        _response = response;
    }

    @Override
    public boolean onPutField(String name, String value)
    {
        if (HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL.is(name))
        {
            _response.setAcceptedSubProtocol(value);
            return false;
        }

        if (HttpHeader.SEC_WEBSOCKET_EXTENSIONS.is(name))
        {
            _response.setExtensions(ExtensionConfig.parseList(value));
            return false;
        }

        return super.onPutField(name, value);
    }

    @Override
    public boolean onAddField(String name, String value)
    {
        if (HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL.is(name))
        {
            _response.setAcceptedSubProtocol(value);
            return false;
        }

        if (HttpHeader.SEC_WEBSOCKET_EXTENSIONS.is(name))
        {
            _response.addExtensions(ExtensionConfig.parseList(value));
            return false;
        }

        return super.onAddField(name, value);
    }

    @Override
    public boolean onRemoveField(String name)
    {
        if (HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL.is(name))
        {
            _response.setAcceptedSubProtocol(null);
            return false;
        }

        if (HttpHeader.SEC_WEBSOCKET_EXTENSIONS.is(name))
        {
            // TODO: why add extensions??
            _response.addExtensions(Collections.emptyList());
            return false;
        }

        return super.onRemoveField(name);
    }
}
