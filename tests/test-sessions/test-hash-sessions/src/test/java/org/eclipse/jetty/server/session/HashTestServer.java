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

package org.eclipse.jetty.server.session;

import org.eclipse.jetty.server.SessionIdManager;


/**
 * @version $Revision$ $Date$
 */
public class HashTestServer extends AbstractTestServer
{
    static int __workers=0;
    
    public HashTestServer(int port)
    {
        super(port, 30, 10, 2);
    }

    public HashTestServer(int port, int maxInactivePeriod, int scavengePeriod, int idlePassivatePeriod)
    {
        super(port, maxInactivePeriod, scavengePeriod, idlePassivatePeriod);
    }



    public SessionHandler newSessionHandler()
    {
        SessionHandler handler = new SessionHandler();
        DefaultSessionCache ss = new DefaultSessionCache(handler);
        handler.setSessionStore(ss);
        ss.setSessionStore(new NullSessionStore());
        return handler;
    }

}
