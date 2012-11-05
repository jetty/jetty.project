//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.api.extensions;

/**
 * Interface for WebSocket Extensions.
 * <p>
 * That work is performed by the two {@link FrameHandler} implementations for incoming and outgoing frame handling.
 */
public interface Extension
{
    /**
     * Create an instance of a Incoming {@link FrameHandler} for working with frames destined for the End User WebSocket Object.
     * 
     * @return the frame handler for incoming frames.
     */
    public FrameHandler createIncomingFrameHandler();

    /**
     * Create an instance of a Outgoing {@link FrameHandler} for working with frames destined for the Network Bytes Layer.
     * 
     * @return the frame handler for outgoing frames.
     */
    public FrameHandler createOutgoingFrameHandler();

    /**
     * The active configuration for this extension.
     * 
     * @return the configuration for this extension. never null.
     */
    public ExtensionConfig getConfig();

    /**
     * The <code>Sec-WebSocket-Extensions</code> name for this extension.
     * <p>
     * Also known as the <a href="https://tools.ietf.org/html/rfc6455#section-9.1"><code>extension-token</code> per Section 9.1. Negotiating Extensions</a>.
     */
    public String getName();
}