package org.eclipse.jetty.tls;

import java.util.List;

import org.eclipse.jetty.tls.ext.Extension;

public final class EncryptedExtensions implements Message
{
    private List<Extension> extensions;

    @Override
    public Type type()
    {
        return Type.ENCRYPTED_EXTENSIONS;
    }

    public List<Extension> getExtensions()
    {
        return extensions;
    }

    public void setExtensions(List<Extension> extensions)
    {
        this.extensions = extensions;
    }
}
