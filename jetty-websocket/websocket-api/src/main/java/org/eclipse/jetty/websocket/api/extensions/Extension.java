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

import javax.net.websocket.extensions.FrameHandler;

/**
 * Interface for WebSocket Extensions.
 * <p>
 * That work is performed by the two {@link FrameHandler} implementations for incoming and outgoing frame handling.
 */
public interface Extension extends javax.net.websocket.extensions.Extension
{
    /**
     * Create an instance of a Incoming {@link FrameHandler} for working with frames destined for the End User WebSocket Object.
     * 
     * @param the
     *            incoming {@link FrameHandler} to wrap
     * @return the frame handler for incoming frames.
     */
    @Override
    public FrameHandler createIncomingFrameHandler(FrameHandler incoming);

    /**
     * Create an instance of a Outgoing {@link FrameHandler} for working with frames destined for the Network Bytes Layer.
     * 
     * @param the
     *            outgoing {@link FrameHandler} to wrap
     * @return the frame handler for outgoing frames.
     */
    @Override
    public FrameHandler createOutgoingFrameHandler(FrameHandler outgoing);

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
    @Override
    public String getName();

    /**
     * Used to indicate that the extension makes use of the RSV1 bit of the base websocket framing.
     * <p>
     * This is used to adjust validation during parsing, as well as a checkpoint against 2 or more extensions all simultaneously claiming ownership of RSV1.
     * 
     * @return true if extension uses RSV1 for its own purposes.
     */
    public abstract boolean isRsv1User();

    /**
     * Used to indicate that the extension makes use of the RSV2 bit of the base websocket framing.
     * <p>
     * This is used to adjust validation during parsing, as well as a checkpoint against 2 or more extensions all simultaneously claiming ownership of RSV2.
     * 
     * @return true if extension uses RSV2 for its own purposes.
     */
    public abstract boolean isRsv2User();

    /**
     * Used to indicate that the extension makes use of the RSV3 bit of the base websocket framing.
     * <p>
     * This is used to adjust validation during parsing, as well as a checkpoint against 2 or more extensions all simultaneously claiming ownership of RSV3.
     * 
     * @return true if extension uses RSV3 for its own purposes.
     */
    public abstract boolean isRsv3User();

    /**
     * Used to indicate that the extension works as a decoder of TEXT Data Frames.
     * <p>
     * This is used to adjust validation during parsing/generating, as per spec TEXT Data Frames can only contain UTF8 encoded String data.
     * <p>
     * Example: a compression extension will process a compressed set of text data, the parser/generator should no longer be concerned about the validity of the
     * TEXT Data Frames as this is now the responsibility of the extension.
     * 
     * @return true if extension will process TEXT Data Frames, false if extension makes no modifications of TEXT Data Frames. If false, the parser/generator is
     *         now free to validate the conformance to spec of TEXT Data Frames.
     */
    public abstract boolean isTextDataDecoder();
}