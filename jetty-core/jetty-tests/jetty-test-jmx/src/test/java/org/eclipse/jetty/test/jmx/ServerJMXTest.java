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

package org.eclipse.jetty.test.jmx;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.Set;
import javax.management.Attribute;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.Invocable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class ServerJMXTest
{
    private MBeanServer mbeanServer;
    private MBeanContainer mbeanContainer;
    private Server server;

    private void start(Handler handler) throws Exception
    {
        mbeanServer = ManagementFactory.getPlatformMBeanServer();
        mbeanContainer = new MBeanContainer(mbeanServer);
        server = new Server();
        server.addBean(mbeanContainer);

        server.setHandler(handler);

        server.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
        server.removeBean(mbeanContainer);
    }

    @Test
    public void testAnonymousAbstractHandler() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        // Verify the Handler is there.
        // It is an anonymous class of this test class something like ServerJMXTest$1,
        // so its domain will be the package name of this test class.
        Set<ObjectName> objectNames =
            mbeanServer.queryNames(ObjectName.getInstance(getClass().getPackageName() + ":*"), null);
        assertNotNull(objectNames);
        assertEquals(1, objectNames.size());
        ObjectName objectName = objectNames.iterator().next();

        String invocationTypeValue = (String)mbeanServer.getAttribute(objectName, "invocationType");
        assertDoesNotThrow(() -> Invocable.InvocationType.valueOf(invocationTypeValue));

        Object serverValue = mbeanServer.getAttribute(objectName, "server");
        assertInstanceOf(ObjectName.class, serverValue);

        MBeanInfo mbeanInfo = mbeanServer.getMBeanInfo((ObjectName)serverValue);
        assertNotNull(mbeanInfo);
        assertEquals(server.getClass().getName(), mbeanInfo.getClassName());
    }

    @Test
    public void testContextHandlerGetAttributes() throws Exception
    {
        ContextHandler context = new ContextHandler("/ctx");
        context.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });
        start(context);

        Set<ObjectName> objectNames =
            mbeanServer.queryNames(ObjectName.getInstance(context.getClass().getPackageName() + ":*"), null);
        assertNotNull(objectNames);
        assertEquals(1, objectNames.size());
        ObjectName contextHandlerObjectName = objectNames.iterator().next();
        // The ContextHandler MBean should report the contextPath as an ObjectName property.
        String contextProperty = contextHandlerObjectName.getKeyProperty("context");
        assertNotNull(contextProperty);
        assertEquals(context.getContextPath(), "/" + contextProperty);

        objectNames = mbeanServer.queryNames(ObjectName.getInstance(getClass().getPackageName() + ":*"), null);
        assertNotNull(objectNames);
        assertEquals(1, objectNames.size());
        ObjectName handlerObjectName = objectNames.iterator().next();
        // Also the child Handler should have the contextPath as an ObjectName property.
        String handlerProperty = handlerObjectName.getKeyProperty("context");
        assertNotNull(handlerProperty);
        assertEquals(contextProperty, handlerProperty);

        // The child Handler should be available as ObjectName.
        Object handlerValue = mbeanServer.getAttribute(contextHandlerObjectName, "handler");
        assertInstanceOf(ObjectName.class, handlerValue);
        assertEquals(handlerObjectName, handlerValue);

        // The list of Handlers should be available as ObjectNames.
        Object handlersValue = mbeanServer.getAttribute(contextHandlerObjectName, "handlers");
        assertInstanceOf(ObjectName[].class, handlersValue);
        ObjectName[] childrenObjectNames = (ObjectName[])handlersValue;
        assertEquals(1, childrenObjectNames.length);
        assertEquals(handlerObjectName, childrenObjectNames[0]);
    }

    @Test
    public void testContextHandlerSetAttributes() throws Exception
    {
        ContextHandler context = new ContextHandler("/ctx");
        Handler.Abstract handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        };
        context.setHandler(handler);
        context.setErrorHandler(new ErrorHandler());
        start(context);

        Set<ObjectName> objectNames = mbeanServer.queryNames(
            ObjectName.getInstance(context.getClass().getPackageName() + ":type=contexthandler,*"), null);
        assertNotNull(objectNames);
        assertEquals(1, objectNames.size());
        ObjectName contextHandlerObjectName = objectNames.iterator().next();

        // Test simple setter.
        String displayName = "test-displayName";
        mbeanServer.setAttribute(contextHandlerObjectName, new Attribute("displayName", displayName));
        Object displayNameValue = mbeanServer.getAttribute(contextHandlerObjectName, "displayName");
        assertEquals(displayName, displayNameValue);

        objectNames = mbeanServer.queryNames(
            ObjectName.getInstance(context.getClass().getPackageName() + ":type=errorhandler,*"), null);
        assertEquals(1, objectNames.size());
        ObjectName errorHandlerObjectName = objectNames.iterator().next();

        Object errorHandlerValue = mbeanServer.getAttribute(contextHandlerObjectName, "errorHandler");
        assertInstanceOf(ObjectName.class, errorHandlerValue);
        assertEquals(errorHandlerObjectName, errorHandlerValue);

        Object handlerValue = mbeanServer.getAttribute(contextHandlerObjectName, "handler");
        assertInstanceOf(ObjectName.class, handlerValue);

        // Test setting a JMX-converted attribute.
        // Method setErrorHandler() should be able to take an ObjectName, lookup the
        // correspondent Object and perform the invocation with the object on the target.
        mbeanServer.setAttribute(contextHandlerObjectName, new Attribute("errorHandler", handlerValue));

        // Verify that the JMX invocation performed what expected on the actual objects.
        assertSame(handler, context.getErrorHandler());
    }

    @Test
    public void testContextHandlerOperations() throws Exception
    {
        ContextHandler context = new ContextHandler("/ctx");
        Handler.Abstract handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        };
        context.setHandler(handler);
        start(context);

        Set<ObjectName> objectNames =
            mbeanServer.queryNames(ObjectName.getInstance(context.getClass().getPackageName() + ":*"), null);
        assertNotNull(objectNames);
        assertEquals(1, objectNames.size());
        ObjectName contextHandlerObjectName = objectNames.iterator().next();

        // Stop and restart.
        mbeanServer.invoke(contextHandlerObjectName, "stop", null, null);
        mbeanServer.invoke(contextHandlerObjectName, "start", null, null);

        // Assert that the tree structure remained as before.
        assertSame(handler, context.getHandler());
        assertTrue(handler.isStarted());

        // The Handler MBean should have been unregistered and registered again.
        objectNames = mbeanServer.queryNames(ObjectName.getInstance(getClass().getPackageName() + ":*"), null);
        assertNotNull(objectNames);
        assertEquals(1, objectNames.size());
        ObjectName handlerObjectName = objectNames.iterator().next();

        String dump = (String)mbeanServer.invoke(handlerObjectName, "dump", null, null);
        assertNotNull(dump);
    }
}
