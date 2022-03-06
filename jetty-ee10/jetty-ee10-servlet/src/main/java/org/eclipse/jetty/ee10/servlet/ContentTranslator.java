package org.eclipse.jetty.ee10.servlet;

import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.util.BufferUtil;

public class ContentTranslator extends HttpInput.Content
{
    private final Content _content;

    public ContentTranslator(Content content)
    {
        super(content.getByteBuffer() == null ? BufferUtil.EMPTY_BUFFER : content.getByteBuffer());
        _content = content;
    }

    @Override
    public boolean isSpecial()
    {
        return _content.isSpecial();
    }

    @Override
    public boolean isEof()
    {
        return _content.isLast();
    }

    @Override
    public Throwable getError()
    {
        if (_content instanceof Content.Error)
            return ((Content.Error)_content).getCause();
        return null;
    }

    @Override
    public String toString()
    {
        return _content.toString();
    }

    @Override
    public void succeeded()
    {
        _content.release();
    }

    @Override
    public void failed(Throwable x)
    {
        _content.release();
    }
}
