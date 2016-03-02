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

package org.eclipse.jetty.cdi.websocket;

import javax.enterprise.inject.Produces;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.websocket.Session;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Producer of {@link javax.websocket.Session} instances
 */
public class JavaWebSocketSessionProducer
{
    private static final Logger LOG = Log.getLogger(JavaWebSocketSessionProducer.class);

    @Produces
    public Session getSession(InjectionPoint injectionPoint)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("getSession({})",injectionPoint);
        }
        org.eclipse.jetty.websocket.api.Session sess = WebSocketScopeContext.current().getSession();
        if (sess == null)
        {
            throw new IllegalStateException("No Session Available");
        }

        if (sess instanceof javax.websocket.Session)
        {
            return (Session)sess;
        }

        throw new IllegalStateException("Incompatible Session, expected <" + javax.websocket.Session.class.getName() + ">, but got <"
                + sess.getClass().getName() + "> instead");
    }
}
