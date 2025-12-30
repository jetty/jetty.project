package org.eclipse.jetty.quic.tls.message;

import java.util.HashMap;
import java.util.Map;

public sealed interface Message permits ClientHello
{
    Type type();

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
