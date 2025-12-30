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

package org.eclipse.jetty.quic.tls.message;

import java.util.HashMap;
import java.util.Map;

public interface Extension
{
    Type type();

    enum Type
    {
        ALPN(0x0010),
        KEY_SHARE(0x001D),
        PRE_SHARED_KEY(0x0029),
        SERVER_NAME(0x0000),
        SIGNATURE_ALGORITHMS(0x000D),
        SUPPORTED_GROUPS(0x000A),
        SUPPORTED_VERSIONS(0x002B),
        QUIC_TRANSPORT_PARAMETERS(0x0039);

        private final int code;

        Type(int code)
        {
            this.code = code;
            Codes.CODES.put(code, this);
        }

        public int code()
        {
            return code;
        }

        public static Type from(int code)
        {
            return Codes.CODES.get(code);
        }

        private static class Codes
        {
            private static final Map<Integer, Type> CODES = new HashMap<>();
        }
    }
}
