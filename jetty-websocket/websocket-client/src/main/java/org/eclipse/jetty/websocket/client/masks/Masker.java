//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client.masks;

import org.eclipse.jetty.websocket.common.WebSocketFrame;

/**
 * Interface for various Masker implementations.
 */
public interface Masker
{
    /**
     * Set the mask on the provided {@link WebSocketFrame}.
     * <p>
     * Implementations MUST set the mask on the frame.
     * 
     * @param frame
     *            the frame to set the mask on.
     */
    void setMask(WebSocketFrame frame);
}
