//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package embedded;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

@SuppressWarnings("unused")
public class ComponentDocs
{
    public void start() throws Exception
    {
        // tag::start[]
        class Monitor extends AbstractLifeCycle
        {
        }

        class Root extends ContainerLifeCycle
        {
            // Monitor is an internal component.
            private final Monitor monitor = new Monitor();

            public Root()
            {
                // The Monitor life cycle is managed by Root.
                addManaged(monitor);
            }
        }

        class Service extends ContainerLifeCycle
        {
            // An instance of the Java scheduler service.
            private ScheduledExecutorService scheduler;

            @Override
            protected void doStart() throws Exception
            {
                // Java's schedulers cannot be restarted, so they must
                // be created anew every time their container is started.
                scheduler = Executors.newSingleThreadScheduledExecutor();
                // Even if Java scheduler does not implement
                // LifeCycle, make it part of the component tree.
                addBean(scheduler);
                // Start all the children beans.
                super.doStart();
            }

            @Override
            protected void doStop() throws Exception
            {
                // Perform the opposite operations that were
                // performed in doStart(), in reverse order.
                super.doStop();
                removeBean(scheduler);
                scheduler.shutdown();
            }
        }

        // Create a Root instance.
        Root root = new Root();

        // Create a Service instance.
        Service service = new Service();

        // Link the components.
        root.addBean(service);

        // Start the root component to
        // start the whole component tree.
        root.start();
        // end::start[]
    }

    public void restart() throws Exception
    {
        // tag::restart[]
        class Root extends ContainerLifeCycle
        {
        }

        class Service extends ContainerLifeCycle
        {
            // An instance of the Java scheduler service.
            private ScheduledExecutorService scheduler;

            @Override
            protected void doStart() throws Exception
            {
                // Java's schedulers cannot be restarted, so they must
                // be created anew every time their container is started.
                scheduler = Executors.newSingleThreadScheduledExecutor();
                // Even if Java scheduler does not implement
                // LifeCycle, make it part of the component tree.
                addBean(scheduler);
                // Start all the children beans.
                super.doStart();
            }

            @Override
            protected void doStop() throws Exception
            {
                // Perform the opposite operations that were
                // performed in doStart(), in reverse order.
                super.doStop();
                removeBean(scheduler);
                scheduler.shutdown();
            }
        }

        Root root = new Root();
        Service service = new Service();
        root.addBean(service);

        // Start the Root component.
        root.start();

        // Stop temporarily Service without stopping the Root.
        service.stop();

        // Restart Service.
        service.start();
        // end::restart[]
    }
}
