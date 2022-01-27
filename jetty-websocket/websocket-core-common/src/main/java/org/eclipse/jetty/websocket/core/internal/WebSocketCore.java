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

