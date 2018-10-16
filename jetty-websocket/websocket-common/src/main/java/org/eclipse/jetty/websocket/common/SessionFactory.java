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

import java.net.URI;

/**
 * Interface for creating jetty {@link WebSocketSession} objects.
 */
public interface SessionFactory
{
    /**
     * Does this implementation support this object type
     * @param websocket the object instance
     * @return true if this SessionFactory supports this object type
     */
    boolean supports(Object websocket);
    
    /**
     * Create a new WebSocketSession from the provided information
     *
     * @param requestURI
     * @param websocket
     * @param connection
     * @return
     */
    WebSocketSession createSession(URI requestURI, Object websocket, LogicalConnection connection);
}
