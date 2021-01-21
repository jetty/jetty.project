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

package org.eclipse.jetty.websocket.common.extensions;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Extension;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.common.extensions.identity.IdentityExtension;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ExtensionStackTest
{
    private static final Logger LOG = Log.getLogger(ExtensionStackTest.class);

    public ByteBufferPool bufferPool = new MappedByteBufferPool();

    @SuppressWarnings("unchecked")
    private <T> T assertIsExtension(String msg, Object obj, Class<T> clazz)
    {
        if (clazz.isAssignableFrom(obj.getClass()))
        {
            return (T)obj;
        }
        assertEquals(clazz.getName(), obj.getClass().getName(), "Expected " + msg + " class");
        return null;
    }

    private ExtensionStack createExtensionStack()
    {
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
        WebSocketContainerScope container = new SimpleContainerScope(policy, bufferPool);

        WebSocketExtensionFactory factory = new WebSocketExtensionFactory(container);
        return new ExtensionStack(factory);
    }

    @Test
    public void testStartIdentity() throws Exception
    {
        ExtensionStack stack = createExtensionStack();
        try
        {
            // 1 extension
            List<ExtensionConfig> configs = new ArrayList<>();
            configs.add(ExtensionConfig.parse("identity"));
            stack.negotiate(configs);

            // Setup Listeners
            DummyIncomingFrames session = new DummyIncomingFrames("Session");
            DummyOutgoingFrames connection = new DummyOutgoingFrames("Connection");
            stack.setNextOutgoing(connection);
            stack.setNextIncoming(session);

            // Start
            stack.start();

            // Dump
            LOG.debug("{}", stack.dump());

            // Should be no change to handlers
            Extension actualIncomingExtension = assertIsExtension("Incoming", stack.getNextIncoming(), IdentityExtension.class);
            Extension actualOutgoingExtension = assertIsExtension("Outgoing", stack.getNextOutgoing(), IdentityExtension.class);
            assertEquals(actualIncomingExtension, actualOutgoingExtension);
        }
        finally
        {
            stack.stop();
        }
    }

    @Test
    public void testStartIdentityTwice() throws Exception
    {
        ExtensionStack stack = createExtensionStack();
        try
        {
            // 1 extension
            List<ExtensionConfig> configs = new ArrayList<>();
            configs.add(ExtensionConfig.parse("identity; id=A"));
            configs.add(ExtensionConfig.parse("identity; id=B"));
            stack.negotiate(configs);

            // Setup Listeners
            DummyIncomingFrames session = new DummyIncomingFrames("Session");
            DummyOutgoingFrames connection = new DummyOutgoingFrames("Connection");
            stack.setNextOutgoing(connection);
            stack.setNextIncoming(session);

            // Start
            stack.start();

            // Dump
            LOG.debug("{}", stack.dump());

            // Should be no change to handlers
            IdentityExtension actualIncomingExtension = assertIsExtension("Incoming", stack.getNextIncoming(), IdentityExtension.class);
            IdentityExtension actualOutgoingExtension = assertIsExtension("Outgoing", stack.getNextOutgoing(), IdentityExtension.class);

            assertThat("Incoming[identity].id", actualIncomingExtension.getParam("id"), is("A"));
            assertThat("Outgoing[identity].id", actualOutgoingExtension.getParam("id"), is("B"));
        }
        finally
        {
            stack.stop();
        }
    }

    @Test
    public void testStartNothing() throws Exception
    {
        ExtensionStack stack = createExtensionStack();
        try
        {
            // intentionally empty
            List<ExtensionConfig> configs = new ArrayList<>();
            stack.negotiate(configs);

            // Setup Listeners
            DummyIncomingFrames session = new DummyIncomingFrames("Session");
            DummyOutgoingFrames connection = new DummyOutgoingFrames("Connection");
            stack.setNextOutgoing(connection);
            stack.setNextIncoming(session);

            // Start
            stack.start();

            // Dump
            LOG.debug("{}", stack.dump());

            // Should be no change to handlers
            assertEquals(stack.getNextIncoming(), session, "Incoming Handler");
            assertEquals(stack.getNextOutgoing(), connection, "Outgoing Handler");
        }
        finally
        {
            stack.stop();
        }
    }

    @Test
    public void testToString()
    {
        ExtensionStack stack = createExtensionStack();
        // Shouldn't cause a NPE.
        LOG.debug("Shouldn't cause a NPE: {}", stack.toString());
    }

    @Test
    public void testNegotiateChrome32()
    {
        ExtensionStack stack = createExtensionStack();

        String chromeRequest = "permessage-deflate; client_max_window_bits, x-webkit-deflate-frame";
        List<ExtensionConfig> requestedConfigs = ExtensionConfig.parseList(chromeRequest);
        stack.negotiate(requestedConfigs);

        List<ExtensionConfig> negotiated = stack.getNegotiatedExtensions();
        String response = ExtensionConfig.toHeaderValue(negotiated);

        assertThat("Negotiated Extensions", response, is("permessage-deflate"));
        LOG.debug("Shouldn't cause a NPE: {}", stack.toString());
    }
}
