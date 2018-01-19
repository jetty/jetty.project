//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common;

import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;

/**
 * The upper container for WebSocket.
 * <p>
 *     For client usage, this is usually a WebSocketClient equivalent.
 *     For Server usage, this is typically the ServletContextWebSocketContainer.
 * </p>
 */
public interface WebSocketContainerContext extends Container
{
    ByteBufferPool getBufferPool();

    ClassLoader getContextClassloader();

    Executor getExecutor();

    WebSocketExtensionRegistry getExtensionRegistry();

    DecoratedObjectFactory getObjectFactory();

    WebSocketPolicy getPolicy();
}
