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

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.rmi.ssl.SslRMIClientSocketFactory;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.jmx.ConnectorServer;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.ssl.SslContextFactory;

@SuppressWarnings("unused")
public class JMXDocs
{
    public void server()
    {
        // tag::server[]
        Server server = new Server();

        // Create an MBeanContainer with the platform MBeanServer.
        MBeanContainer mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());

        // Add MBeanContainer to the root component.
        server.addBean(mbeanContainer);
        // end::server[]
    }

    public void client()
    {
        // tag::client[]
        HttpClient httpClient = new HttpClient();

        // Create an MBeanContainer with the platform MBeanServer.
        MBeanContainer mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());

        // Add MBeanContainer to the root component.
        httpClient.addBean(mbeanContainer);
        // end::client[]
    }

    public void remote() throws Exception
    {
        // tag::remote[]
        Server server = new Server();

        // Setup Jetty JMX.
        MBeanContainer mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbeanContainer);

        // Setup ConnectorServer.

        // Bind the RMI server to the wildcard address and port 1999.
        // Bind the RMI registry to the wildcard address and port 1099.
        JMXServiceURL jmxURL = new JMXServiceURL("rmi", null, 1999, "/jndi/rmi:///jmxrmi");
        ConnectorServer jmxServer = new ConnectorServer(jmxURL, "org.eclipse.jetty.jmx:name=rmiconnectorserver");

        // Add ConnectorServer as a bean, so it is started
        // with the Server and also exported as MBean.
        server.addBean(jmxServer);

        server.start();
        // end::remote[]
    }

    public static void main(String[] args) throws Exception
    {
        new JMXDocs().remote();
    }

    public void remoteAuthorization() throws Exception
    {
        // tag::remoteAuthorization[]
        Server server = new Server();

        // Setup Jetty JMX.
        MBeanContainer mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbeanContainer);

        // Setup ConnectorServer.
        JMXServiceURL jmxURL = new JMXServiceURL("rmi", null, 1099, "/jndi/rmi:///jmxrmi");
        Map<String, Object> env = new HashMap<>();
        env.put("com.sun.management.jmxremote.access.file", "/path/to/users.access");
        env.put("com.sun.management.jmxremote.password.file", "/path/to/users.password");
        ConnectorServer jmxServer = new ConnectorServer(jmxURL, env, "org.eclipse.jetty.jmx:name=rmiconnectorserver");
        server.addBean(jmxServer);

        server.start();
        // end::remoteAuthorization[]
    }

    public void tlsRemote() throws Exception
    {
        // tag::tlsRemote[]
        Server server = new Server();

        // Setup Jetty JMX.
        MBeanContainer mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbeanContainer);

        // Setup SslContextFactory.
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("/path/to/keystore");
        sslContextFactory.setKeyStorePassword("secret");

        // Setup ConnectorServer with SslContextFactory.
        JMXServiceURL jmxURL = new JMXServiceURL("rmi", null, 1099, "/jndi/rmi:///jmxrmi");
        ConnectorServer jmxServer = new ConnectorServer(jmxURL, null, "org.eclipse.jetty.jmx:name=rmiconnectorserver", sslContextFactory);
        server.addBean(jmxServer);

        server.start();
        // end::tlsRemote[]
    }

    public void tlsJMXConnector() throws Exception
    {
        // tag::tlsJMXConnector[]
        // System properties necessary for an RMI client to trust a self-signed certificate.
        System.setProperty("javax.net.ssl.trustStore", "/path/to/trustStore");
        System.setProperty("javax.net.ssl.trustStorePassword", "secret");

        JMXServiceURL jmxURL = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://domain.com:1100/jmxrmi");

        Map<String, Object> clientEnv = new HashMap<>();
        // Required to connect to the RMI registry via TLS.
        clientEnv.put(ConnectorServer.RMI_REGISTRY_CLIENT_SOCKET_FACTORY_ATTRIBUTE, new SslRMIClientSocketFactory());

        try (JMXConnector client = JMXConnectorFactory.connect(jmxURL, clientEnv))
        {
            Set<ObjectName> names = client.getMBeanServerConnection().queryNames(null, null);
        }
        // end::tlsJMXConnector[]
    }

    public void jmxAnnotation() throws Exception
    {
        // tag::jmxAnnotation[]
        // Annotate the class with @ManagedObject and provide a description.
        @ManagedObject("Services that provide useful features")
        class Services
        {
            private final Map<String, Object> services = new ConcurrentHashMap<>();
            private boolean enabled = true;

            // A read-only attribute with description.
            @ManagedAttribute(value = "The number of services", readonly = true)
            public int getServiceCount()
            {
                return services.size();
            }

            // A read-write attribute with description.
            // Only the getter is annotated.
            @ManagedAttribute(value = "Whether the services are enabled")
            public boolean isEnabled()
            {
                return enabled;
            }

            // There is no need to annotate the setter.
            public void setEnabled(boolean enabled)
            {
                this.enabled = enabled;
            }

            // An operation with description and impact.
            // The @Name annotation is used to annotate parameters
            // for example to display meaningful parameter names.
            @ManagedOperation(value = "Retrieves the service with the given name", impact = "INFO")
            public Object getService(@Name(value = "serviceName") String n)
            {
                return services.get(n);
            }
        }
        // end::jmxAnnotation[]
    }

    public void jmxCustomMBean()
    {
        // tag::jmxCustomMBean[]
        //package com.acme;
        @ManagedObject
        class Service
        {
        }

        //package com.acme.jmx;
        class ServiceMBean extends ObjectMBean
        {
            ServiceMBean(Object service)
            {
                super(service);
            }
        }
        // end::jmxCustomMBean[]
    }

    public void jmxCustomMBeanOverride()
    {
        // tag::jmxCustomMBeanOverride[]
        //package com.acme;
        // No Jetty JMX annotations.
        class CountService
        {
            private int count;

            public int getCount()
            {
                return count;
            }

            public void addCount(int value)
            {
                count += value;
            }
        }

        //package com.acme.jmx;
        @ManagedObject("the count service")
        class CountServiceMBean extends ObjectMBean
        {
            public CountServiceMBean(Object service)
            {
                super(service);
            }

            private CountService getCountService()
            {
                return (CountService)super.getManagedObject();
            }

            @ManagedAttribute("the current service count")
            public int getCount()
            {
                return getCountService().getCount();
            }

            @ManagedOperation(value = "adds the given value to the service count", impact = "ACTION")
            public void addCount(@Name("count delta") int value)
            {
                getCountService().addCount(value);
            }
        }
        // end::jmxCustomMBeanOverride[]
    }
}
