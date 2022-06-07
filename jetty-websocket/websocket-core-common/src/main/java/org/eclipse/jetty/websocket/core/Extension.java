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

package org.eclipse.jetty.websocket.core;

/**
 * Interface for WebSocket Extensions.
 * <p>
 * That {@link Frame}s are passed through the Extension via the {@link IncomingFrames} and {@link OutgoingFrames} interfaces
 */
public interface Extension extends IncomingFrames, OutgoingFrames
{

    void init(ExtensionConfig config, WebSocketComponents components);

    /**
     * Used to clean up any resources after connection close.
     */
    default void close()
    {
    }

    /**
     * The active configuration for this extension.
     *
     * @return the configuration for this extension. never null.
     */
    ExtensionConfig getConfig();

    /**
     * The {@code Sec-WebSocket-Extensions} name for this extension.
     * <p>
     * Also known as the <a href="https://tools.ietf.org/html/rfc6455#section-9.1">{@code extension-token} per Section 9.1. Negotiating Extensions</a>.
     *
     * @return the name of the extension
     */
    String getName();

    /**
     * Used to indicate that the extension makes use of the RSV1 bit of the base websocket framing.
     * <p>
     * This is used to adjust validation during parsing, as well as a checkpoint against 2 or more extensions all simultaneously claiming ownership of RSV1.
     *
     * @return true if extension uses RSV1 for its own purposes.
     */
    boolean isRsv1User();

    /**
     * Used to indicate that the extension makes use of the RSV2 bit of the base websocket framing.
     * <p>
     * This is used to adjust validation during parsing, as well as a checkpoint against 2 or more extensions all simultaneously claiming ownership of RSV2.
     *
     * @return true if extension uses RSV2 for its own purposes.
     */
    boolean isRsv2User();

    /**
     * Used to indicate that the extension makes use of the RSV3 bit of the base websocket framing.
     * <p>
     * This is used to adjust validation during parsing, as well as a checkpoint against 2 or more extensions all simultaneously claiming ownership of RSV3.
     *
     * @return true if extension uses RSV3 for its own purposes.
     */
    boolean isRsv3User();

    /**
     * Set the next {@link IncomingFrames} to call in the chain.
     *
     * @param nextIncoming the next incoming extension
     */
    void setNextIncomingFrames(IncomingFrames nextIncoming);

    /**
     * Set the next {@link OutgoingFrames} to call in the chain.
     *
     * @param nextOutgoing the next outgoing extension
     */
    void setNextOutgoingFrames(OutgoingFrames nextOutgoing);

    /**
     * @param coreSession the {@link CoreSession} for this Extension.
     */
    void setCoreSession(CoreSession coreSession);
}
