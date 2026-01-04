package org.eclipse.jetty.tls;

import java.util.List;

import org.eclipse.jetty.tls.ext.Extension;

public record EncryptedExtensionsMessage(List<Extension> extensions) implements Message
{
    @Override
    public Type type()
    {
        return Type.ENCRYPTED_EXTENSIONS;
    }
}
