//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server;

import java.net.URISyntaxException;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;

/**
 * @deprecated use {@link org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest} instead
 */
@Deprecated
public class ServletWebSocketRequest extends ServletUpgradeRequest
{
    public ServletWebSocketRequest(HttpServletRequest request) throws URISyntaxException
    {
        super(request);
    }
}
