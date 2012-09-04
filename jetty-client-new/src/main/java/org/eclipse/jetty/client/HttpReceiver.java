package org.eclipse.jetty.client;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

class HttpReceiver implements HttpParser.ResponseHandler<ByteBuffer>
{
    private static final Logger LOG = Log.getLogger(HttpReceiver.class);

    private final HttpParser parser = new HttpParser(this);
    private HttpConversation conversation;

    public void receive(HttpConversation conversation)
    {
        if (this.conversation != null)
            throw new IllegalStateException();
        this.conversation = conversation;

        HttpConnection connection = conversation.connection();
        HttpClient client = connection.getHttpClient();
        ByteBufferPool bufferPool = client.getByteBufferPool();
        ByteBuffer buffer = bufferPool.acquire(client.getResponseBufferSize(), true);
        EndPoint endPoint = connection.getEndPoint();
        try
        {
            while (true)
            {
                int read = endPoint.fill(buffer);
                if (read > 0)
                {
                    parser.parseNext(buffer);
                    // TODO: response done, reset ?
                }
                else if (read == 0)
                {
                    connection.fillInterested();
                    break;
                }
                else
                {
                    parser.shutdownInput();
                    break;
                }
            }
        }
        catch (IOException x)
        {
            LOG.debug(x);
            bufferPool.release(buffer);
            fail(x);
        }
    }

    @Override
    public boolean startResponse(HttpVersion version, int status, String reason)
    {
        HttpResponse response = conversation.response();
        response.version(version).status(status).reason(reason);

        // Probe the protocol listeners
        HttpClient client = conversation.connection().getHttpClient();
        Response.Listener listener = client.lookup(status);
        if (listener != null)
            conversation.listener(listener);
        else
            conversation.listener(conversation.applicationListener());

        notifyBegin(conversation.listener(), response);
        return false;
    }

    @Override
    public boolean parsedHeader(HttpHeader header, String name, String value)
    {
        conversation.response().headers().put(name, value);
        return false;
    }

    @Override
    public boolean headerComplete()
    {
        notifyHeaders(conversation.listener(), conversation.response());
        return false;
    }

    @Override
    public boolean content(ByteBuffer buffer)
    {
        notifyContent(conversation.listener(), conversation.response(), buffer);
        return false;
    }

    @Override
    public boolean messageComplete(long contentLength)
    {
        success();
        return false;
    }

    protected void success()
    {
        HttpConversation conversation = this.conversation;
        this.conversation = null;
        Response.Listener listener = conversation.listener();
        Response response = conversation.response();
        conversation.done();
        notifySuccess(listener, response);
    }

    protected void fail(Throwable failure)
    {
        Response.Listener listener = conversation.listener();
        Response response = conversation.response();
        conversation.done();
        conversation = null;
        notifyFailure(listener, response, failure);
    }

    @Override
    public boolean earlyEOF()
    {
        fail(new EOFException());
        return false;
    }

    @Override
    public void badMessage(int status, String reason)
    {
        conversation.response().status(status).reason(reason);
        fail(new HttpResponseException());
    }

    private void notifyBegin(Response.Listener listener, Response response)
    {
        try
        {
            if (listener != null)
                listener.onBegin(response);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    private void notifyHeaders(Response.Listener listener, Response response)
    {
        try
        {
            if (listener != null)
                listener.onHeaders(response);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    private void notifyContent(Response.Listener listener, Response response, ByteBuffer buffer)
    {
        try
        {
            if (listener != null)
                listener.onContent(response, buffer);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    private void notifySuccess(Response.Listener listener, Response response)
    {
        try
        {
            if (listener != null)
                listener.onSuccess(response);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    private void notifyFailure(Response.Listener listener, Response response, Throwable failure)
    {
        try
        {
            if (listener != null)
                listener.onFailure(response, failure);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    public void idleTimeout()
    {
        fail(new TimeoutException());
    }
}
