//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.internal;

import java.util.concurrent.ThreadLocalRandom;

/**
 * <p>A class to support GREASE (<a href="https://www.rfc-editor.org/rfc/rfc8701.txt">RFC 8701</a>) in HTTP/3.</p>
 * <p>HTTP/3 GREASE values have the form {@code 0x1F * N + 0x21} with non negative values of {@code N}.</p>
 */
public class Grease
{
    /**
     * @param value the value to test
     * @return whether the value is a GREASE value as defined by HTTP/3
     */
    public static boolean isGreaseValue(long value)
    {
        if (value < 0)
            return false;
        return (value - 0x21) % 0x1F == 0;
    }

    /**
     * @return a random grease value as defined by HTTP/3
     */
    public static long generateGreaseValue()
    {
        // This constant avoids to overflow VarLenInt.
        long n = ThreadLocalRandom.current().nextLong(0x210842108421084L);
        return 0x1F * n + 0x21;
    }

    private Grease()
    {
    }
}
