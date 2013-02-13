package org.eclipse.jetty.websocket.jsr356.endpoints;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriverImpl;

public class JsrAnnotatedImpl implements EventDriverImpl
{

    @Override
    public EventDriver create(Object websocket, WebSocketPolicy policy)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String describeRule()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean supports(Object websocket)
    {
        // TODO Auto-generated method stub
        return false;
    }

}
