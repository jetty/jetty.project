package org.eclipse.jetty.quic.tls.internal.generator;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.tls.message.Extension;
import org.eclipse.jetty.quic.tls.message.KeyShare;
import org.eclipse.jetty.quic.tls.message.KeyShareExtension;

public class KeyShareExtensionGenerator implements ExtensionGenerator
{
    @Override
    public int getType()
    {
        return KeyShareExtension.TYPE;
    }

    @Override
    public int generate(RetainableByteBuffer.Mutable accumulator, Extension extension)
    {
        return generate(accumulator, (KeyShareExtension)extension);
    }

    private int generate(RetainableByteBuffer.Mutable accumulator, KeyShareExtension extension)
    {
        accumulator.putShort((short)extension.type());
        int listLength = extension.keyShares().stream()
            .mapToInt(keyShare -> 2 + 2 + keyShare.keyExchange().length)
            .sum();
        int totalLength = 2 + listLength;
        accumulator.putShort((short)totalLength);
        accumulator.putShort((short)listLength);
        for (KeyShare keyShare : extension.keyShares())
        {
            accumulator.putShort((short)keyShare.group().value());
            byte[] keyExchange = keyShare.keyExchange();
            accumulator.putShort((short)keyExchange.length);
            accumulator.put(keyExchange);
        }
        return totalLength;
    }
}
