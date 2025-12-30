package org.eclipse.jetty.quic.tls.message;

import java.util.List;

public record SupportedGroupsExtension(List<NamedGroup> namedGroups) implements Extension
{
    @Override
    public Type type()
    {
        return Type.SUPPORTED_GROUPS;
    }
}
