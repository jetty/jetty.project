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

package org.eclipse.jetty.websocket.tests.server;

import org.eclipse.jetty.websocket.api.StatusCode;

public class CloseInOnCloseEndpoint extends AbstractCloseEndpoint
{
    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        getSession().close(StatusCode.SERVER_ERROR, "this should be a noop");
        super.onWebSocketClose(statusCode, reason);
    }
}
