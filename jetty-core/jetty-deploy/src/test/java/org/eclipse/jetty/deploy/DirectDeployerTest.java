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

package org.eclipse.jetty.deploy;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DirectDeployerTest extends AbstractCleanEnvironmentTest
{
    private final Server server = new Server();
    private final ContextHandlerCollection contexts = new ContextHandlerCollection();
    private final DirectDeployer deployer = new DirectDeployer(contexts);
    private final ContextHandler context = new ContextHandler();

    @BeforeEach
    public void beforeEach()
    {
        server.setHandler(contexts);
        server.addBean(deployer);
    }

    @Test
    public void testWhenStopped() throws Exception
    {
        deployer.deploy(context);
        assertThat(contexts.getHandlers(), contains(context));
        assertFalse(context.isStarted());

        deployer.undeploy(context);
        assertThat(contexts.getHandlers(), empty());
        assertFalse(context.isStarted());
    }

    @Test
    public void testWhenStoppedThenStart() throws Exception
    {
        deployer.deploy(context);
        assertThat(contexts.getHandlers(), contains(context));
        assertFalse(context.isStarted());

        server.start();
        assertThat(contexts.getHandlers(), contains(context));
        assertTrue(context.isStarted());

        server.stop();
        assertThat(contexts.getHandlers(), contains(context));
        assertFalse(context.isStarted());

        deployer.undeploy(context);
        assertThat(contexts.getHandlers(), empty());
        assertFalse(context.isStarted());
    }

    @Test
    public void testWhenStartingStopping() throws Exception
    {
        deployer.addEventListener(new LifeCycle.Listener()
        {
            @Override
            public void lifeCycleStarting(LifeCycle event)
            {
                deployer.deploy(context);
                assertThat(contexts.getHandlers(), contains(context));
                assertTrue(context.isStarted());
            }

            @Override
            public void lifeCycleStopping(LifeCycle event)
            {
                deployer.undeploy(context);
                assertThat(contexts.getHandlers(), empty());
                assertFalse(context.isStarted());
            }
        });

        server.start();
        assertThat(contexts.getHandlers(), contains(context));
        assertTrue(context.isStarted());

        server.stop();
        assertThat(contexts.getHandlers(), empty());
        assertFalse(context.isStarted());
    }

    @Test
    public void testWhenStarted() throws Exception
    {
        server.start();
        deployer.deploy(context);
        assertThat(contexts.getHandlers(), contains(context));
        assertTrue(context.isStarted());

        deployer.undeploy(context);
        assertThat(contexts.getHandlers(), empty());
        assertFalse(context.isStarted());

        server.stop();
        assertThat(contexts.getHandlers(), empty());
        assertFalse(context.isStarted());
    }

}
