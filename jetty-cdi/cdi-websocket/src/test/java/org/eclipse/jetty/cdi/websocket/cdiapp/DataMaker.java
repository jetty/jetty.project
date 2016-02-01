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

package org.eclipse.jetty.cdi.websocket.cdiapp;

import javax.inject.Inject;

import org.eclipse.jetty.cdi.websocket.annotation.WebSocketScope;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;

public class DataMaker
{
    private static final Logger LOG = Log.getLogger(DataMaker.class);
    
    @Inject
    @WebSocketScope
    private Session session;

    public void processMessage(String msg) 
    {
        LOG.debug(".processMessage({})",msg);
        LOG.debug("session = {}",session);

        session.getRemote().sendStringByFuture("Hello there " + msg);
    }
}
