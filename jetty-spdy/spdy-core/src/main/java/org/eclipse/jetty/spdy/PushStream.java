package org.eclipse.jetty.spdy;

import org.eclipse.jetty.spdy.api.Stream;

//TODO: javadoc
public interface PushStream extends IStream
{
    public Stream getAssociatedStream();
}
