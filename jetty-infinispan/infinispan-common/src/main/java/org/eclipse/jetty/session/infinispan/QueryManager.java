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

package org.eclipse.jetty.session.infinispan;

import java.util.Set;

import org.eclipse.jetty.server.session.SessionContext;

public interface QueryManager
{
    Set<String> queryExpiredSessions(SessionContext sessionContext, long currentTime);

    public void deleteOrphanSessions(long time);
    
    public boolean exists(SessionContext sessionContext, String id);
}
