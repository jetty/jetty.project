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

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.WebSocketComponents;

public class RFC8441Negotiation extends WebSocketNegotiation
{
    public RFC8441Negotiation(Request request, Response response, Callback callback, WebSocketComponents components) throws BadMessageException
    {
        super(request, response, callback, components);
    }

    @Override
    public boolean validateHeaders()
    {
        MetaData.Request metaData = null; // TODO: getRequest().getMetaData();
        if (metaData == null)
            return false;
        return "websocket".equals(metaData.getProtocol());
    }
}
