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

package org.eclipse.jetty.ee10.test.client.transport;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;

public abstract class AbstractTest<T extends TransportScenario>
{
    protected T scenario;

    public abstract void init(Transport transport) throws IOException;

    public void setScenario(T scenario)
    {
        this.scenario = scenario;
    }

    @AfterEach
    public void stopScenario()
    {
        if (scenario != null)
            scenario.stop();
        scenario = null;
    }
}
