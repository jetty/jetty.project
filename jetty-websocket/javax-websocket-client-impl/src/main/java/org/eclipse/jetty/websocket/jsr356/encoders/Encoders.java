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

package org.eclipse.jetty.websocket.jsr356.encoders;

import java.util.ArrayList;
import java.util.List;

import javax.websocket.Encoder;

public class Encoders
{
    private static final List<Class<? extends Encoder>> DEFAULTS;

    static
    {
        DEFAULTS = new ArrayList<>();
        DEFAULTS.add(BooleanEncoder.class);
        DEFAULTS.add(ByteEncoder.class);
        DEFAULTS.add(CharacterEncoder.class);
        DEFAULTS.add(DoubleEncoder.class);
        DEFAULTS.add(FloatEncoder.class);
        DEFAULTS.add(IntegerEncoder.class);
        DEFAULTS.add(LongEncoder.class);
        DEFAULTS.add(ShortEncoder.class);
        DEFAULTS.add(StringEncoder.class);
    }
}
