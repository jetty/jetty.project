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

package org.eclipse.jetty.docs.programming;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;

import static java.lang.System.Logger.Level.INFO;

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

    public void getBeans() throws Exception
    {
        // tag::getBeans[]
        class Root extends ContainerLifeCycle
        {
        }

        class Service extends ContainerLifeCycle
        {
            private ScheduledExecutorService scheduler;

            @Override
            protected void doStart() throws Exception
            {
                scheduler = Executors.newSingleThreadScheduledExecutor();
                addBean(scheduler);
                super.doStart();
            }

            @Override
            protected void doStop() throws Exception
            {
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

        // Find all the direct children of root.
        Collection<Object> children = root.getBeans();
        // children contains only service

        // Find all descendants of root that are instance of a particular class.
        Collection<ScheduledExecutorService> schedulers = root.getContainedBeans(ScheduledExecutorService.class);
        // schedulers contains the service scheduler.
        // end::getBeans[]
    }

    public void lifecycleListener()
    {
        // tag::lifecycleListener[]
        Server server = new Server();

        // Add an event listener of type LifeCycle.Listener.
        server.addEventListener(new LifeCycle.Listener()
        {
            @Override
            public void lifeCycleStarted(LifeCycle lifeCycle)
            {
                System.getLogger("server").log(INFO, "Server {0} has been started", lifeCycle);
            }

            @Override
            public void lifeCycleFailure(LifeCycle lifeCycle, Throwable failure)
            {
                System.getLogger("server").log(INFO, "Server {0} failed to start", lifeCycle, failure);
            }

            @Override
            public void lifeCycleStopped(LifeCycle lifeCycle)
            {
                System.getLogger("server").log(INFO, "Server {0} has been stopped", lifeCycle);
            }
        });
        // end::lifecycleListener[]
    }

    public void containerListener()
    {
        // tag::containerListener[]
        Server server = new Server();

        // Add an event listener of type LifeCycle.Listener.
        server.addEventListener(new Container.Listener()
        {
            @Override
            public void beanAdded(Container parent, Object child)
            {
                System.getLogger("server").log(INFO, "Added bean {1} to {0}", parent, child);
            }

            @Override
            public void beanRemoved(Container parent, Object child)
            {
                System.getLogger("server").log(INFO, "Removed bean {1} from {0}", parent, child);
            }
        });
        // end::containerListener[]
    }

    public void containerSiblings()
    {
        // tag::containerSiblings[]
        class Parent extends ContainerLifeCycle
        {
        }

        class Child
        {
        }

        // The older child takes care of its siblings.
        class OlderChild extends Child implements Container.Listener
        {
            private Set<Object> siblings = new HashSet<>();

            @Override
            public void beanAdded(Container parent, Object child)
            {
                siblings.add(child);
            }

            @Override
            public void beanRemoved(Container parent, Object child)
            {
                siblings.remove(child);
            }
        }

        Parent parent = new Parent();

        Child older = new OlderChild();
        // The older child is a child bean _and_ a listener.
        parent.addBean(older);

        Child younger = new Child();
        // Adding a younger child will notify the older child.
        parent.addBean(younger);
        // end::containerSiblings[]
    }
}
