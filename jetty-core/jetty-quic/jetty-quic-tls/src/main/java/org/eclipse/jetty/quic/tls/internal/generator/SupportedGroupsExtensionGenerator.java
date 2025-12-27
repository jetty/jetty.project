package org.eclipse.jetty.quic.tls.internal.generator;

import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.tls.message.Extension;
import org.eclipse.jetty.quic.tls.message.NamedGroup;
import org.eclipse.jetty.quic.tls.message.SupportedGroupsExtension;

public class SupportedGroupsExtensionGenerator implements ExtensionGenerator {
    @Override
    public int getType() {
        return SupportedGroupsExtension.TYPE;
    }

    @Override
    public int generate(RetainableByteBuffer.Mutable accumulator, Extension extension) {
        return generate(accumulator, (SupportedGroupsExtension)extension);
    }

    private int generate(RetainableByteBuffer.Mutable accumulator, SupportedGroupsExtension extension) {
        accumulator.putShort((short)extension.type());
        List<NamedGroup> groups = extension.namedGroups();
        int listLength = 2 * groups.size();
        int totalLength = 2 + listLength;
        accumulator.putShort((short)totalLength);
        accumulator.putShort((short)listLength);
        for (NamedGroup group : groups) {
            accumulator.putShort((short)group.value());
        }
        return totalLength;
    }
}
