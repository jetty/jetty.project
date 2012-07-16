package org.eclipse.jetty.websocket.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

public class WebSocketSession implements WebSocketConnection
{
    private static final Logger LOG = Log.getLogger(WebSocketSession.class);
    private RawConnection connection;
    private OutgoingFrames outgoing;
    private String subprotocol;
    private WebSocketPolicy policy;

    @Override
    public void close() throws IOException
    {
        connection.close();
    }

    @Override
    public void close(int statusCode, String reason) throws IOException
    {
        connection.close(statusCode,reason);
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return connection.getRemoteAddress();
    }

    @Override
    public String getSubProtocol()
    {
        return subprotocol;
    }

    @Override
    public boolean isOpen()
    {
        return connection.isOpen();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <C> void ping(C context, Callback<C> callback, byte[] payload) throws IOException
    {
        WebSocketFrame frame = new WebSocketFrame(OpCode.PING).setPayload(payload);
        frame.setFin(true);
        outgoing.output(context,callback,frame);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <C> void write(C context, Callback<C> callback, byte[] buf, int offset, int len) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("write(context,{},byte[],{},{})",callback,offset,len);
        }
        WebSocketFrame frame = WebSocketFrame.binary().setPayload(buf,offset,len);
        frame.setFin(true);
        outgoing.output(context,callback,frame);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <C> void write(C context, Callback<C> callback, ByteBuffer buffer) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("write(context,{},ByteBuffer->{})",callback,BufferUtil.toDetailString(buffer));
        }
        WebSocketFrame frame = WebSocketFrame.binary().setPayload(buffer);
        frame.setFin(true);
        outgoing.output(context,callback,frame);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <C> void write(C context, Callback<C> callback, String message) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("write(context,{},message.length:{})",callback,message.length());
        }
        WebSocketFrame frame = WebSocketFrame.text(message);
        frame.setFin(true);
        outgoing.output(context,callback,frame);
    }
}
