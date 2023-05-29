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

package org.eclipse.jetty.ee10.session.infinispan;

import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.eclipse.jetty.session.infinispan.EmbeddedQueryManager;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStoreFactory;
import org.eclipse.jetty.session.infinispan.QueryManager;
import org.eclipse.jetty.session.test.tools.InfinispanTestSupport;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * InfinispanSessionDataStoreTest with file backing
 */
@ExtendWith(WorkDirExtension.class)
public class InfinispanFileSessionDataStoreTest extends InfinispanSessionDataStoreTest
{
    public WorkDir workDir;

    public InfinispanFileSessionDataStoreTest() throws Exception
    {
        super();
    }
    
    @BeforeEach
    public void configure() throws Exception
    {
        _testSupport = new InfinispanTestSupport(getClass().getSimpleName() + System.nanoTime());
        _testSupport.setUseFileStore(true);
        _testSupport.setup(workDir.getEmptyPathDir());
    }
    
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        InfinispanSessionDataStoreFactory factory = new InfinispanSessionDataStoreFactory();
        factory.setSerialization(true);
        factory.setCache(_testSupport.getCache());
        QueryManager qm = new EmbeddedQueryManager(_testSupport.getCache());
        factory.setQueryManager(qm);
        return factory;
    }
}
