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

package org.eclipse.jetty.websocket.core.server.internal;

import java.util.Collections;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;

public class WebSocketHttpFieldsWrapper extends HttpFields.Mutable.Wrapper
{
    private final ServerUpgradeResponse _response;

    public WebSocketHttpFieldsWrapper(Mutable fields, ServerUpgradeResponse response, WebSocketNegotiation negotiation)
    {
        super(fields);
        _response = response;
    }

    @Override
    public HttpField onAddField(HttpField field)
    {
        if (field.getHeader() != null)
        {
            return switch (field.getHeader())
            {
                case SEC_WEBSOCKET_SUBPROTOCOL ->
                {
                    _response.setAcceptedSubProtocol(field.getValue());
                    yield field;
                }

                case SEC_WEBSOCKET_EXTENSIONS ->
                {
                    _response.addExtensions(ExtensionConfig.parseList(field.getValue()));
                    yield field;
                }

                default -> super.onAddField(field);
            };
        }
        return super.onAddField(field);
    }

    @Override
    public boolean onRemoveField(HttpField field)
    {
        if (field.getHeader() != null)
        {
            return switch (field.getHeader())
            {
                case SEC_WEBSOCKET_SUBPROTOCOL ->
                {
                    _response.setAcceptedSubProtocol(null);
                    yield false;
                }

                case SEC_WEBSOCKET_EXTENSIONS ->
                {
                    // TODO: why add extensions??
                    _response.addExtensions(Collections.emptyList());
                    yield false;
                }

                default -> super.onRemoveField(field);
            };
        }
        return super.onRemoveField(field);
    }
}
