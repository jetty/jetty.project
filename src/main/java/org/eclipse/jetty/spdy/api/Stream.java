package org.eclipse.jetty.spdy.api;

import java.util.EventListener;

public interface Stream
{
    public int getId();

    public short getVersion();

    public byte getPriority();

    public Session getSession();

    public void reply(ReplyInfo replyInfo);

    public void data(DataInfo dataInfo);

    public void headers(HeadersInfo headersInfo);

    public boolean isClosed();

    public boolean isHalfClosed();

    public interface FrameListener extends EventListener
    {
        public void onReply(Stream stream, ReplyInfo replyInfo);

        public void onHeaders(Stream stream, HeadersInfo headersInfo);

        public void onData(Stream stream, DataInfo dataInfo);

        public static class Adapter implements FrameListener
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
            }

            @Override
            public void onHeaders(Stream stream, HeadersInfo headersInfo)
            {
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
            }
        }
    }
}
