// ========================================================================
// Copyright 2004-2005 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.eclipse.jetty.server.session;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.SessionManager;

/**
 * @version $Revision$ $Date$
 */
public class HashTestServer extends AbstractTestServer
{

    public HashTestServer(int port)
    {
        super(port, 30, 10);
    }

    public HashTestServer(int port, int maxInactivePeriod, int scavengePeriod)
    {
        super(port, maxInactivePeriod, scavengePeriod);
    }


    public SessionIdManager newSessionIdManager()
    {
        return new HashSessionIdManager();
    }

    public AbstractSessionManager newSessionManager()
    {
        HashSessionManager manager = new HashSessionManager();
        manager.setScavengePeriod((int)TimeUnit.SECONDS.toMillis(_scavengePeriod));
        return manager;
    }

    public SessionHandler newSessionHandler(SessionManager sessionManager)
    {
        return new SessionHandler(sessionManager);
    }

}
