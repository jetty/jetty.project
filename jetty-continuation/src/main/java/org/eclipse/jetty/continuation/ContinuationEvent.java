package org.eclipse.jetty.continuation;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

public interface ContinuationEvent
{
    public ServletRequest getRequest();
    public ServletResponse getResponse();
}
