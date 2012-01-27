package org.eclipse.jetty.spdy;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.frames.ControlFrame;

public interface ISession extends Session
{
    public void control(IStream stream, ControlFrame frame) throws StreamException;

    public void data(IStream stream, DataInfo dataInfo);

    public interface Controller
    {
        public int write(ByteBuffer buffer, Handler handler);

        public void close(boolean onlyOutput);

        public interface Handler
        {
            public void complete();
        }
    }
}
