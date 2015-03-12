//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

/**
 * Producer of {@link javax.websocket.Session} instances
 */
public class JavaWebSocketSessionProducer
{
    private ThreadLocal<Session> sessionInstance;

    public JavaWebSocketSessionProducer()
    {
        sessionInstance = new ThreadLocal<Session>();
    }

    public void setSession(Session sess)
    {
        sessionInstance.set(sess);
    }

    @Produces
    public Session getSession(InjectionPoint injectionPoint)
    {
        return this.sessionInstance.get();
    }
}
