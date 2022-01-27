//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.session.infinispan.EmbeddedQueryManager;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStoreFactory;
import org.eclipse.jetty.session.infinispan.QueryManager;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * HotInitInfinispanSessionDataStoreTest
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
    public void setup() throws Exception
    {
        _testSupport = new InfinispanTestSupport();
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
