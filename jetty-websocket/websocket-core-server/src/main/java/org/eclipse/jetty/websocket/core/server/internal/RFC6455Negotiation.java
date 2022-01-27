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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiation;

public class RFC6455Negotiation extends WebSocketNegotiation
{
    private boolean successful;
    private String key;

    public RFC6455Negotiation(Request baseRequest, HttpServletRequest request, HttpServletResponse response, WebSocketComponents components) throws BadMessageException
    {
        super(baseRequest, request, response, components);
    }

    @Override
    protected void negotiateHeaders(Request baseRequest)
    {
        super.negotiateHeaders(baseRequest);

        boolean upgrade = false;
        QuotedCSV connectionCSVs = null;
        for (HttpField field : baseRequest.getHttpFields())
        {
            HttpHeader header = field.getHeader();
            if (header != null)
            {
                switch (header)
                {
                    case UPGRADE:
                        upgrade = "websocket".equalsIgnoreCase(field.getValue());
                        break;

                    case CONNECTION:
                        if (connectionCSVs == null)
                            connectionCSVs = new QuotedCSV();
                        connectionCSVs.addValue(field.getValue());
                        break;

                    case SEC_WEBSOCKET_KEY:
                        key = field.getValue();
                        break;

                    default:
                        break;
                }
            }
        }

        successful = upgrade && connectionCSVs != null &&
            connectionCSVs.getValues().stream().anyMatch(s -> s.equalsIgnoreCase("upgrade"));
    }

    @Override
    public boolean validateHeaders()
    {
        return successful;
    }

    public String getKey()
    {
        return key;
    }
}
