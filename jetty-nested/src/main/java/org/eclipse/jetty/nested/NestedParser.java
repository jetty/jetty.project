package org.eclipse.jetty.nested;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.http.Parser;

public class NestedParser implements Parser
{

    public NestedParser(HttpServletRequest request)
    {
        // TODO Auto-generated constructor stub
    }

    public void reset(boolean returnBuffers)
    {
        // TODO Auto-generated method stub

    }

    public boolean isComplete()
    {
        // TODO Auto-generated method stub
        return false;
    }

    public int parseAvailable() throws IOException
    {
        // TODO Auto-generated method stub
        return 0;
    }

    public boolean isMoreInBuffer() throws IOException
    {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean isIdle()
    {
        // TODO Auto-generated method stub
        return false;
    }

}
