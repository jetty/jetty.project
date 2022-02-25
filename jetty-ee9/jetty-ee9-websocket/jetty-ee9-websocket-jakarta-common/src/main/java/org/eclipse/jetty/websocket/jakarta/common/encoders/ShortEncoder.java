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

package org.eclipse.jetty.websocket.jakarta.common.encoders;

import jakarta.websocket.EncodeException;
import jakarta.websocket.Encoder;

/**
 * Default encoder for {@link Short} to {@link jakarta.websocket.Encoder.Text} Message encoder
 */
public class ShortEncoder extends AbstractEncoder implements Encoder.Text<Short>
{
    @Override
    public String encode(Short object) throws EncodeException
    {
        if (object == null)
        {
            return null;
        }
        return object.toString();
    }
}
