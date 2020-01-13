//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.common;

/**
 * Thrown when a duplicate coder is encountered when attempting to identify a Endpoint's metadata ({@link javax.websocket.Decoder} or {@link javax.websocket.Encoder})
 * TODO: is this still needed?
 */
public class DuplicateCoderException extends InvalidWebSocketException
{
    private static final long serialVersionUID = -3049181444035417170L;

    public DuplicateCoderException(String message)
    {
        super(message);
    }

    public DuplicateCoderException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
