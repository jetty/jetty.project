package org.eclipse.jetty.quic.tls.message;

import java.util.List;

public record SupportedGroupsExtension(List<NamedGroup> namedGroups) implements Extension {
    public static final int TYPE = 0x000A;

    @Override
    public int type() {
        return TYPE;
    }
}
