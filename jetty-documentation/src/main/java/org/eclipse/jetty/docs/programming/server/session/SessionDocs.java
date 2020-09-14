//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.docs.programming.server.session;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.session.DefaultSessionIdManager;
import org.eclipse.jetty.server.session.HouseKeeper;

public class SessionDocs
{
    public void minimumDefaultSessionIdManager()
    {
        //tag::default[]
        Server server = new Server();
        DefaultSessionIdManager idMgr = new DefaultSessionIdManager(server);
        //you must set the workerName unless you set the env viable JETTY_WORKER_NAME
        idMgr.setWorkerName("3");
        server.setSessionIdManager(idMgr);
        //end::default[]
    }

    public void defaultSessionIdManagerWithHouseKeeper()
    {
        try
        {
            //tag::housekeeper[]
            Server server = new Server();
            DefaultSessionIdManager idMgr = new DefaultSessionIdManager(server);
            idMgr.setWorkerName("7");
            server.setSessionIdManager(idMgr);

            HouseKeeper houseKeeper = new HouseKeeper();
            houseKeeper.setSessionIdManager(idMgr);
            //set the frequency of scavenge cycles
            houseKeeper.setIntervalSec(600L);
            idMgr.setSessionHouseKeeper(houseKeeper);
            //end::housekeeper[]
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}
