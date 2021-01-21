//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.session;

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

    @BeforeEach
    public void setup() throws Exception
    {
        _testSupport = new InfinispanTestSupport();
        _testSupport.setUseFileStore(true);
        _testSupport.setup(workDir.getEmptyPathDir());
    }
}
