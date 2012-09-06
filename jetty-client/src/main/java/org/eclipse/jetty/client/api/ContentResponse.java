package org.eclipse.jetty.client.api;

public interface ContentResponse extends Response
{
    byte[] content();
}
