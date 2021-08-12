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

package org.eclipse.jetty.tests.distribution.session;

import java.util.Collections;
import java.util.List;

@Testcontainers(disabledWithoutDocker = false)
public class FileSessionDistributionTests extends AbstractSessionDistributionTests
{

    @Override
    public void startExternalSessionStorage() throws Exception
    {
        // no op
    }
    
    @Override
    public void stopExternalSessionStorage() throws Exception
    {
        // no op
    }

    @Override
    public List<String> getFirstStartExtraArgs()
    {
        return Collections.emptyList();
    }

    @Override
    public String getFirstStartExtraModules()
    {
        return "session-store-file";
    }

    @Override
    public List<String> getSecondStartExtraArgs()
    {
        return Collections.emptyList();
    }

}
