package org.eclipse.jetty.server;

import java.util.ArrayList;

import static java.util.Arrays.asList;

class RequestLogCollection
    implements RequestLog
{
    private final ArrayList<RequestLog> delegates;

    public RequestLogCollection(RequestLog... requestLogs)
    {
        delegates = new ArrayList<>(asList(requestLogs));
    }

    public void add(RequestLog requestLog)
    {
        delegates.add(requestLog);
    }

    @Override
    public void log(Request request, Response response)
    {
        for (RequestLog delegate:delegates)
            delegate.log(request, response);
    }
}
