package org.eclipse.jetty.spdy.api.server;

import org.eclipse.jetty.spdy.api.Session;

public interface ServerSessionFrameListener extends Session.FrameListener
{
    public void onConnect(Session session);

    public static class Adapter extends Session.FrameListener.Adapter implements ServerSessionFrameListener
    {
        @Override
        public void onConnect(Session session)
        {
        }
    }
}
