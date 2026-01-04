package org.eclipse.jetty.tls;

import java.util.List;

import org.eclipse.jetty.tls.ext.Extension;

public final class EncryptedExtensions implements Message
{
    private final List<Extension> extensions;

    public EncryptedExtensions(List<Extension> extensions)
    {
        this.extensions = extensions;
    }

    @Override
    public Type getType()
    {
        return Type.ENCRYPTED_EXTENSIONS;
    }

    public List<Extension> getExtensions()
    {
        return extensions;
    }
}
