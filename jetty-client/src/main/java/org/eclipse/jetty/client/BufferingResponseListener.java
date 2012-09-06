package org.eclipse.jetty.client;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.api.Response;

public class BufferingResponseListener extends Response.Listener.Adapter
{
    private final CountDownLatch latch = new CountDownLatch(1);
    private final int maxCapacity;
    private Response response;
    private Throwable failure;
    private byte[] buffer = new byte[0];

    public BufferingResponseListener()
    {
        this(16 * 1024 * 1024);
    }

    public BufferingResponseListener(int maxCapacity)
    {
        this.maxCapacity = maxCapacity;
    }

    @Override
    public void onContent(Response response, ByteBuffer content)
    {
        long newLength = buffer.length + content.remaining();
        if (newLength > maxCapacity)
            throw new IllegalStateException("Buffering capacity exceeded");

        byte[] newBuffer = new byte[(int)newLength];
        System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
        content.get(newBuffer, buffer.length, content.remaining());
        buffer = newBuffer;
    }

    @Override
    public void onSuccess(Response response)
    {
        this.response = response;
        latch.countDown();
    }

    @Override
    public void onFailure(Response response, Throwable failure)
    {
        this.response = response;
        this.failure = failure;
        latch.countDown();
    }

    public Response await(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
    {
        boolean expired = !latch.await(timeout, unit);
        if (failure != null)
            throw new ExecutionException(failure);
        if (expired)
            throw new TimeoutException();
        return response;
    }

    public byte[] content()
    {
        return buffer;
    }

    public String contentAsString(String encoding)
    {
        return new String(content(), Charset.forName(encoding));
    }
}
