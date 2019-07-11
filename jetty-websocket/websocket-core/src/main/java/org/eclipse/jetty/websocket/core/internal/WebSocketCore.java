//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import org.eclipse.jetty.websocket.core.WebSocketConstants;

public final class WebSocketCore
{

    /**
     * Concatenate the provided key with the Magic GUID and return the Base64 encoded form.
     *
     * @param key the key to hash
     * @return the {@code Sec-WebSocket-Accept} header response (per opening handshake spec)
     */
    public static String hashKey(String key)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA1");
            md.update(key.getBytes(StandardCharsets.UTF_8));
            md.update(WebSocketConstants.MAGIC);
            return Base64.getEncoder().encodeToString(md.digest());
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}

