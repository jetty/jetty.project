package org.eclipse.jetty.websocket.api;

import java.util.Iterator;

import org.eclipse.jetty.websocket.protocol.ExtensionConfig;

public interface ExtensionRegistry extends Iterable<Class<? extends Extension>>
{
    public boolean isAvailable(String name);

    @Override
    public Iterator<Class<? extends Extension>> iterator();

    public Extension newInstance(ExtensionConfig config);

    public void register(String name, Class<? extends Extension> extension);

    public void unregister(String name);
}
