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

package org.eclipse.jetty.server;

import java.io.IOException;

class MockConnector extends AbstractConnector
{
    public MockConnector()
    {
        super(new Server(), null, null, null, 0);
    }

    @Override
    protected void accept(int acceptorID) throws IOException, InterruptedException
    {
    }

    @Override
    public Object getTransport()
    {
        return null;
    }

    @Override
    public String dumpSelf()
    {
        return null;
    }
}
