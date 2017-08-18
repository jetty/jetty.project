//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.extensions;

import static org.hamcrest.Matchers.is;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.WSPolicy;
import org.eclipse.jetty.websocket.core.IncomingFrames;
import org.eclipse.jetty.websocket.core.io.IncomingFramesCapture;
import org.eclipse.jetty.websocket.core.OutgoingFrames;
import org.eclipse.jetty.websocket.core.io.OutgoingFramesCapture;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class ExtensionStackTest
{
    private static final Logger LOG = Log.getLogger(ExtensionStackTest.class);

    private static DecoratedObjectFactory objectFactory;
    private static WSPolicy policy;
    private static ByteBufferPool bufferPool;
    private static ExtensionStack stack;

    @BeforeClass
    public static void init()
    {
        objectFactory = new DecoratedObjectFactory();
        policy = WSPolicy.newServerPolicy();
        bufferPool = new MappedByteBufferPool();
        stack = new ExtensionStack(new WSExtensionFactory());
    }

    @SuppressWarnings("unchecked")
    private <T> T assertIsExtension(String msg, Object obj, Class<T> clazz)
    {
        if (clazz.isAssignableFrom(obj.getClass()))
        {
            return (T)obj;
        }
        Assert.assertEquals("Expected " + msg + " class",clazz.getName(),obj.getClass().getName());
        return null;
    }

    @Test
    public void testStartIdentity() throws Exception
    {
        try
        {
            // 1 extension
            List<ExtensionConfig> configs = new ArrayList<>();
            configs.add(ExtensionConfig.parse("identity"));
            stack.negotiate(objectFactory, policy, bufferPool, configs);

            // Setup Listeners
            IncomingFrames session = new IncomingFramesCapture();
            OutgoingFrames connection = new OutgoingFramesCapture();
            stack.setNextOutgoing(connection);
            stack.setNextIncoming(session);

            // Start
            stack.start();

            // Dump
            LOG.debug("{}",stack.dump());

            // Should be no change to handlers
            Extension actualIncomingExtension = assertIsExtension("Incoming",stack.getNextIncoming(),IdentityExtension.class);
            Extension actualOutgoingExtension = assertIsExtension("Outgoing",stack.getNextOutgoing(),IdentityExtension.class);
            Assert.assertEquals(actualIncomingExtension,actualOutgoingExtension);
        }
        finally
        {
            stack.stop();
        }
    }

    @Test
    public void testStartIdentityTwice() throws Exception
    {
        try
        {
            // 1 extension
            List<ExtensionConfig> configs = new ArrayList<>();
            configs.add(ExtensionConfig.parse("identity; id=A"));
            configs.add(ExtensionConfig.parse("identity; id=B"));
            stack.negotiate(objectFactory, policy, bufferPool, configs);

            // Setup Listeners
            IncomingFrames session = new IncomingFramesCapture();
            OutgoingFrames connection = new OutgoingFramesCapture();
            stack.setNextOutgoing(connection);
            stack.setNextIncoming(session);

            // Start
            stack.start();

            // Dump
            LOG.debug("{}",stack.dump());

            // Should be no change to handlers
            IdentityExtension actualIncomingExtension = assertIsExtension("Incoming",stack.getNextIncoming(),IdentityExtension.class);
            IdentityExtension actualOutgoingExtension = assertIsExtension("Outgoing",stack.getNextOutgoing(),IdentityExtension.class);

            Assert.assertThat("Incoming[identity].id",actualIncomingExtension.getParam("id"),is("A"));
            Assert.assertThat("Outgoing[identity].id",actualOutgoingExtension.getParam("id"),is("B"));
        }
        finally
        {
            stack.stop();
        }
    }

    @Test
    public void testStartNothing() throws Exception
    {
        try
        {
            // intentionally empty
            List<ExtensionConfig> configs = new ArrayList<>();
            stack.negotiate(objectFactory, policy, bufferPool, configs);

            // Setup Listeners
            IncomingFrames session = new IncomingFramesCapture();
            OutgoingFrames connection = new OutgoingFramesCapture();
            stack.setNextOutgoing(connection);
            stack.setNextIncoming(session);

            // Start
            stack.start();

            // Dump
            LOG.debug("{}",stack.dump());

            // Should be no change to handlers
            Assert.assertEquals("Incoming Handler",stack.getNextIncoming(),session);
            Assert.assertEquals("Outgoing Handler",stack.getNextOutgoing(),connection);
        }
        finally
        {
            stack.stop();
        }
    }

    @Test
    public void testToString()
    {
        // Shouldn't cause a NPE.
        LOG.debug("Shouldn't cause a NPE: {}",stack.toString());
    }
    
    @Test
    public void testNegotiateChrome32()
    {
        String chromeRequest = "permessage-deflate; client_max_window_bits, x-webkit-deflate-frame";
        List<ExtensionConfig> requestedConfigs = ExtensionConfig.parseList(chromeRequest);
        stack.negotiate(objectFactory, policy, bufferPool, requestedConfigs);
        
        List<ExtensionConfig> negotiated = stack.getNegotiatedExtensions();
        String response = ExtensionConfig.toHeaderValue(negotiated);
        
        Assert.assertThat("Negotiated Extensions", response, is("permessage-deflate"));
        LOG.debug("Shouldn't cause a NPE: {}",stack.toString());
    }
}
