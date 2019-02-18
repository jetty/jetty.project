package org.eclipse.jetty.websocket.core.proxy;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;

class BasicFrameHandler implements FrameHandler
{
    protected String name;
    protected CoreSession session;
    protected CountDownLatch opened = new CountDownLatch(1);
    protected CountDownLatch closed = new CountDownLatch(1);

    protected BlockingQueue<Frame> receivedFrames = new BlockingArrayQueue<>();


    public BasicFrameHandler(String name)
    {
        this.name = "[" + name + "]";
    }

    @Override
    public void onOpen(CoreSession coreSession, Callback callback)
    {
        session = coreSession;
        System.err.println(name + " onOpen(): " + session);
        opened.countDown();
        callback.succeeded();
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        System.err.println(name + " onFrame(): " + frame);
        receivedFrames.offer(Frame.copy(frame));
        callback.succeeded();
    }

    @Override
    public void onError(Throwable cause, Callback callback)
    {
        System.err.println(name + " onError(): " + cause);
        callback.succeeded();
    }

    @Override
    public void onClosed(CloseStatus closeStatus, Callback callback)
    {
        System.err.println(name + " onClosed(): " + closeStatus);
        closed.countDown();
        callback.succeeded();
    }

    public void sendText(String message)
    {
        System.err.println(name + " sendText(): " + message);
        Frame textFrame = new Frame(OpCode.TEXT, BufferUtil.toBuffer(message));
        session.sendFrame(textFrame, Callback.NOOP, false);
    }

    public void sendFrame(Frame frame)
    {
        System.err.println(name + " sendFrame(): " + frame);
        session.sendFrame(frame, Callback.NOOP, false);
    }

    public void close(String message) throws Exception
    {
        session.close(CloseStatus.NORMAL, message, Callback.NOOP);
        awaitClose();
    }

    public void awaitClose() throws Exception
    {
        if (!closed.await(5, TimeUnit.SECONDS))
            throw new TimeoutException();
    }


    public static class ServerEchoHandler extends BasicFrameHandler
    {
        private boolean throwOnFrame;
        private boolean noResponse;

        public void throwOnFrame()
        {
            throwOnFrame = true;
        }

        public void noResponseOnFrame()
        {
            noResponse = true;
        }

        public ServerEchoHandler(String name)
        {
            super(name);
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            System.err.println(name + " onFrame(): " + frame);
            receivedFrames.offer(Frame.copy(frame));

            if (throwOnFrame)
                throw new RuntimeException("intentionally throwing in server onFrame()");

            if (noResponse)
                return;

            if (frame.isDataFrame())
            {
                System.err.println(name + " echoDataFrame(): " + frame);
                session.sendFrame(new Frame(frame.getOpCode(), frame.getPayload()), callback, false);
            }
            else
            {
                callback.succeeded();
            }

        }
    }
}