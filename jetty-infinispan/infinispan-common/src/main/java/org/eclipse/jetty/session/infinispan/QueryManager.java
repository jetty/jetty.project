package org.eclipse.jetty.session.infinispan;

import java.util.Set;

public interface QueryManager
{
    Set<String> queryExpiredSessions();
    Set<String> queryExpiredSessions(long currentTime);
}
