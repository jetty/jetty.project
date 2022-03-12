package org.eclipse.jetty.websocket.core.server.internal;

import java.util.ListIterator;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;

public class HttpFieldsWrapper implements HttpFields.Mutable
{
    private final HttpFields.Mutable _fields;

    public HttpFieldsWrapper(HttpFields.Mutable fields)
    {
        _fields = fields;
    }

    // TODO a signature that took HttpField would be better.
    // TODO Do we need Put? Could it just be done as a onRemoveField then an onAddField?
    public boolean onPutField(String name, String value)
    {
        return true;
    }

    public boolean onAddField(String name, String value)
    {
        return true;
    }

    public boolean onRemoveField(String name)
    {
        return true;
    }

    @Override
    public ListIterator<HttpField> listIterator()
    {
        return new ListIterator<>()
        {
            final ListIterator<HttpField> _list = _fields.listIterator();
            HttpField _last;

            @Override
            public boolean hasNext()
            {
                return _list.hasNext();
            }

            @Override
            public HttpField next()
            {
                return _last = _list.next();
            }

            @Override
            public boolean hasPrevious()
            {
                return _list.hasPrevious();
            }

            @Override
            public HttpField previous()
            {
                return _last = _list.previous();
            }

            @Override
            public int nextIndex()
            {
                return _list.nextIndex();
            }

            @Override
            public int previousIndex()
            {
                return _list.previousIndex();
            }

            @Override
            public void remove()
            {
                if (_last != null && HttpFieldsWrapper.this.onRemoveField(_last.getName()))
                    _list.remove();
            }

            @Override
            public void set(HttpField httpField)
            {
                if (_last != null && HttpFieldsWrapper.this.onPutField(_last.getName(), _last.getValue()))
                    _list.set(httpField);
            }

            @Override
            public void add(HttpField httpField)
            {
                if (_last != null && HttpFieldsWrapper.this.onAddField(_last.getName(), _last.getValue()))
                    _list.add(httpField);
            }
        };
    }
}
