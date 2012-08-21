package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpGenerator;

public interface HttpTransport
{
    public void commit(HttpGenerator.ResponseInfo info, ByteBuffer content, boolean complete) throws IOException;

    public void write(ByteBuffer content, boolean complete) throws IOException;
}
