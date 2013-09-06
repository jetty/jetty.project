package org.eclipse.jetty.fcgi.client.http;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpContent;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpSender;
import org.eclipse.jetty.fcgi.generator.ClientGenerator;
import org.eclipse.jetty.fcgi.generator.Generator;
import org.eclipse.jetty.util.Callback;

public class HttpSenderOverFCGI extends HttpSender
{
    private final ClientGenerator generator;

    public HttpSenderOverFCGI(HttpChannel channel)
    {
        super(channel);
        this.generator = new ClientGenerator(channel.getHttpDestination().getHttpClient().getByteBufferPool());
    }

    @Override
    protected HttpChannelOverFCGI getHttpChannel()
    {
        return (HttpChannelOverFCGI)super.getHttpChannel();
    }

    @Override
    protected void sendHeaders(HttpExchange exchange, HttpContent content, Callback callback)
    {
        int request = getHttpChannel().getId();
        Generator.Result result = generator.generateRequestHeaders(request, exchange.getRequest().getHeaders(), callback);
        getHttpChannel().write(result);
    }

    @Override
    protected void sendContent(HttpExchange exchange, HttpContent content, Callback callback)
    {
        int request = getHttpChannel().getId();
        Generator.Result result = generator.generateRequestContent(request, content.getByteBuffer(), content.isLast(), callback);
        getHttpChannel().write(result);
    }
}
