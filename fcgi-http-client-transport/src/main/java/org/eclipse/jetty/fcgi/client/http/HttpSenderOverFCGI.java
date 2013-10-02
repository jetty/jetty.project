package org.eclipse.jetty.fcgi.client.http;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpContent;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpSender;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.generator.ClientGenerator;
import org.eclipse.jetty.fcgi.generator.Generator;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
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
        Request httpRequest = exchange.getRequest();
        URI uri = httpRequest.getURI();
        HttpFields headers = httpRequest.getHeaders();

        HttpField field = headers.remove(HttpHeader.AUTHORIZATION);
        if (field != null)
            headers.put(FCGI.Headers.AUTH_TYPE, field.getValue());

        field = headers.remove(HttpHeader.CONTENT_LENGTH);
        if (field != null)
            headers.put(FCGI.Headers.CONTENT_LENGTH, field.getValue());

        field = headers.remove(HttpHeader.CONTENT_TYPE);
        if (field != null)
            headers.put(FCGI.Headers.CONTENT_TYPE, field.getValue());

        headers.put(FCGI.Headers.GATEWAY_INTERFACE, "CGI/1.1");

        HttpClientTransportOverFCGI transport = (HttpClientTransportOverFCGI)getHttpChannel().getHttpDestination().getHttpClient().getTransport();
        Pattern uriPattern = transport.getURIPattern();
        Matcher matcher = uriPattern.matcher(uri.toString());

        // TODO: what if the URI does not match ? Here is kinda too late to abort the request ?
        // TODO: perhaps this works in conjuntion with the ProxyServlet, which is mapped to the same URI regexp
        // TODO: so that if the call arrives here, we are sure it matches.

//        headers.put(Headers.PATH_INFO, ???);
//        headers.put(Headers.PATH_TRANSLATED, ???);

        headers.put(FCGI.Headers.QUERY_STRING, uri.getQuery());

        // TODO: the fields below are probably provided by ProxyServlet as X-Forwarded-*
//        headers.put(Headers.REMOTE_ADDR, ???);
//        headers.put(Headers.REMOTE_HOST, ???);
//        headers.put(Headers.REMOTE_USER, ???);

        headers.put(FCGI.Headers.REQUEST_METHOD, httpRequest.getMethod());

        headers.put(FCGI.Headers.REQUEST_URI, uri.toString());

        headers.put(FCGI.Headers.SERVER_PROTOCOL, httpRequest.getVersion().asString());

        // TODO: translate remaining HTTP header into the HTTP_* format

        int request = getHttpChannel().getRequest();
        boolean hasContent = content.hasContent();
        Generator.Result result = generator.generateRequestHeaders(request, headers,
                hasContent ? callback : new Callback.Adapter());
        getHttpChannel().flush(result);
        if (!hasContent)
        {
            result = generator.generateRequestContent(request, null, true, callback);
            getHttpChannel().flush(result);
        }
    }

    @Override
    protected void sendContent(HttpExchange exchange, HttpContent content, Callback callback)
    {
        if (content.isConsumed())
        {
            callback.succeeded();
        }
        else
        {
            int request = getHttpChannel().getRequest();
            Generator.Result result = generator.generateRequestContent(request, content.getByteBuffer(), content.isLast(), callback);
            getHttpChannel().flush(result);
        }
    }
}
