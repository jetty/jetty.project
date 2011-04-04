package org.eclipse.jetty.nested;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.http.Parser;

public class NestedParser implements Parser
{

    public NestedParser()
    {
    }

    public void reset(boolean returnBuffers)
    {
    }

    public boolean isComplete()
    {
        return false;
    }

    public int parseAvailable() throws IOException
    {
        return 0;
    }

    public boolean isMoreInBuffer() throws IOException
    {
        return false;
    }

    public boolean isIdle()
    {
        return false;
    }

}
