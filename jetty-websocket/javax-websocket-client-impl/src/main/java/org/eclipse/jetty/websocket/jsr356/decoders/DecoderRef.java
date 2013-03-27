//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.decoders;

import javax.websocket.Decoder;

/**
 * A reference to a Decoder.
 * <p>
 * This represents a potential decoder, no instance exists (yet)
 */
public class DecoderRef
{
    private Class<?> type;
    private Class<? extends Decoder> decoder;

    public DecoderRef(Class<?> type, Class<? extends Decoder> decoder)
    {
        this.type = type;
        this.decoder = decoder;
    }

    public Class<? extends Decoder> getDecoder()
    {
        return decoder;
    }

    public Class<?> getType()
    {
        return type;
    }
}