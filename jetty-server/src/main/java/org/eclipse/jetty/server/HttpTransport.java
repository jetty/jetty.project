package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpGenerator;

public interface HttpTransport
{
    void commit(HttpGenerator.ResponseInfo info, ByteBuffer content, boolean lastContent) throws IOException;

    void write(ByteBuffer content, boolean lastContent) throws IOException;
    
    void completed();
}
