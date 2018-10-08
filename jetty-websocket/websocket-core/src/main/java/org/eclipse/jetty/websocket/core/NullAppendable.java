package org.eclipse.jetty.websocket.core;

import org.eclipse.jetty.util.Utf8Appendable;

public class NullAppendable extends Utf8Appendable
{
    public NullAppendable()
    {
        super(new Appendable()
        {
            @Override
            public Appendable append(CharSequence csq)
            {
                return null;
            }

            @Override
            public Appendable append(CharSequence csq, int start, int end)
            {
                return null;
            }

            @Override
            public Appendable append(char c)
            {
                return null;
            }
        });
    }

    @Override
    public int length()
    {
        return 0;
    }

    @Override
    public String getPartialString()
    {
        return null;
    }
}