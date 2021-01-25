//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http.client;

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
