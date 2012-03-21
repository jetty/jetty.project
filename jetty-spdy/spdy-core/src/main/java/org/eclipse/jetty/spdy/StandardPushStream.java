package org.eclipse.jetty.spdy;

import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.frames.SynStreamFrame;

//TODO: Javadoc
public class StandardPushStream extends StandardStream implements PushStream
{
    private Stream associatedStream;

    public StandardPushStream(SynStreamFrame frame, ISession session, int windowSize, Stream associatedStream)
    {
        super(frame,session,windowSize);
        this.associatedStream = associatedStream;
    }

    @Override
    public Stream getAssociatedStream()
    {
        // TODO Auto-generated method stub
        return associatedStream;
    }
}
