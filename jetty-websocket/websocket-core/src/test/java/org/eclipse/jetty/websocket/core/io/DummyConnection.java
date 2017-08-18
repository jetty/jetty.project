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

package org.eclipse.jetty.websocket.core.io;

import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.core.WSPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.extensions.WSExtensionFactory;
import org.eclipse.jetty.websocket.core.handshake.DummyUpgradeRequest;
import org.eclipse.jetty.websocket.core.handshake.DummyUpgradeResponse;
import org.eclipse.jetty.websocket.core.handshake.UpgradeRequest;
import org.eclipse.jetty.websocket.core.handshake.UpgradeResponse;

public class DummyConnection extends WSConnection
{
    public static class Builder
    {
        private EndPoint endp;
        private Executor executor;
        private ByteBufferPool bufferPool;
        private DecoratedObjectFactory objectFactory;
        private WSPolicy policy;
        private WSExtensionFactory extensionFactory;
        private ExtensionStack extensionStack;
        private UpgradeRequest upgradeRequest;
        private UpgradeResponse upgradeResponse;

        public Builder ioEndpoint(EndPoint endp)
        {
            this.endp = endp;
            return this;
        }

        public Builder executor(Executor executor)
        {
            this.executor = executor;
            return this;
        }

        public Builder bufferPool(ByteBufferPool bufferPool)
        {
            this.bufferPool = bufferPool;
            return this;
        }

        public Builder objectFactory(DecoratedObjectFactory objectFactory)
        {
            this.objectFactory = objectFactory;
            return this;
        }

        public Builder policy(WSPolicy policy)
        {
            this.policy = policy;
            return this;
        }

        public Builder extensionStack(ExtensionStack extensionStack)
        {
            this.extensionStack = extensionStack;
            return this;
        }

        public Builder upgradeRequest(UpgradeRequest upgradeRequest)
        {
            this.upgradeRequest = upgradeRequest;
            return this;
        }

        public Builder upgradeResponse(UpgradeResponse upgradeResponse)
        {
            this.upgradeResponse = upgradeResponse;
            return this;
        }

        public DummyConnection build()
        {
            if (endp == null) endp = new ByteArrayEndPoint();
            if (executor == null) executor = new QueuedThreadPool();
            if (bufferPool == null) bufferPool = new MappedByteBufferPool();
            if (objectFactory == null) objectFactory = new DecoratedObjectFactory();
            if (policy == null) policy = WSPolicy.newServerPolicy();
            if (extensionStack == null)
                extensionStack = new ExtensionStack(new WSExtensionFactory());
            if (upgradeRequest == null) upgradeRequest = new DummyUpgradeRequest();
            if (upgradeResponse == null) upgradeResponse = new DummyUpgradeResponse();

            return new DummyConnection(endp, executor, bufferPool, objectFactory, policy, extensionStack, upgradeRequest, upgradeResponse);
        }
    }

    public DummyConnection(EndPoint endp, Executor executor, ByteBufferPool bufferPool,
                           DecoratedObjectFactory decoratedObjectFactory,
                           WSPolicy policy, ExtensionStack extensionStack,
                           UpgradeRequest upgradeRequest, UpgradeResponse upgradeResponse)
    {
        super(endp, executor, bufferPool, decoratedObjectFactory, policy, extensionStack, upgradeRequest, upgradeResponse);
    }
}
