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

package org.eclipse.jetty.websocket.core.server.internal;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.server.Negotiation;

public class RFC6544Negotiation extends Negotiation
{
    private boolean successful;
    private String key;

    public RFC6544Negotiation(Request baseRequest, HttpServletRequest request, HttpServletResponse response, WebSocketComponents components) throws BadMessageException
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
    public boolean isSuccessful()
    {
        return successful;
    }

    public String getKey()
    {
        return key;
    }
}
