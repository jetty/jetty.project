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

package org.eclipse.jetty.tls;

import java.util.HashMap;
import java.util.Map;

public sealed interface Message permits ClientHello, ServerHello, EncryptedExtensions, CertificateRequest
{
    Type getType();

    enum Type
    {
        CLIENT_HELLO(1),
        SERVER_HELLO(2),
        NEW_SESSION_TICKET(4),
        END_OF_EARLY_DATA(5),
        ENCRYPTED_EXTENSIONS(8),
        CERTIFICATE(11),
        CERTIFICATE_REQUEST(13),
        CERTIFICATE_VERIFY(15),
        FINISHED(20),
        KEY_UPDATE(24),
        MESSAGE_HASH(254);

        private final int type;

        Type(int type)
        {
            this.type = type;
            Types.TYPES.put(type, this);
        }

        public int type()
        {
            return type;
        }

        public static Type from(int type)
        {
            return Types.TYPES.get(type);
        }

        private static class Types
        {
            private static final Map<Integer, Type> TYPES = new HashMap<>();
        }
    }
}
