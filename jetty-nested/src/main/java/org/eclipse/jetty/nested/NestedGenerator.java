package org.eclipse.jetty.nested;

import java.io.IOException;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.AbstractGenerator;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpVersions;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.Log;

public class NestedGenerator extends AbstractGenerator
{
    final HttpServletResponse _response;
    final String _nestedIn;
    
    public NestedGenerator(Buffers buffers, EndPoint io, HttpServletResponse response, String nestedIn)
    {
        super(buffers,io);
        _response=response;
        _nestedIn=nestedIn;
    }

    public void addContent(Buffer content, boolean last) throws IOException
    {
        Log.debug("addContent {} {}",content.length(),last);
        if (_noContent)
        {
            content.clear();
            return;
        }

        if (content.isImmutable())
            throw new IllegalArgumentException("immutable");

        if (_last || _state == STATE_END)
        {
            Log.debug("Ignoring extra content {}", content);
            content.clear();
            return;
        }
        _last = last;

        if(!_endp.isOpen())
        {
            _state = STATE_END;
            return;
        }

        // Handle any unfinished business?
        if (_content != null && _content.length() > 0)
        {

            flushBuffer();
            if (_content != null && _content.length() > 0)
                throw new IllegalStateException("FULL");
        }

        _content = content;

        _contentWritten += content.length();

        // Handle the _content
        if (_head)
        {
            content.clear();
            _content = null;
        }
        else
        {
            // Yes - so we better check we have a buffer
            initContent();
            // Copy _content to buffer;
            int len = 0;
            len = _buffer.put(_content);

            // make sure there is space for a trailing null
            if (len > 0 && _buffer.space() == 0)
            {
                len--;
                _buffer.setPutIndex(_buffer.putIndex() - 1);
            }

            _content.skip(len);

            if (_content.length() == 0)
                _content = null;
        }
    }

    public boolean addContent(byte b) throws IOException
    {
        Log.debug("addContent 1");
        if (_noContent)
            return false;

        if (_last || _state == STATE_END)
            throw new IllegalStateException("Closed");


        if(!_endp.isOpen())
        {
            _state = STATE_END;
            return false;
        }

        // Handle any unfinished business?
        if (_content != null && _content.length() > 0)
        {
            flushBuffer();
            if (_content != null && _content.length() > 0)
                throw new IllegalStateException("FULL");
        }

        _contentWritten++;

        // Handle the _content
        if (_head)
            return false;

        // we better check we have a buffer
        initContent();

        // Copy _content to buffer;

        _buffer.put(b);

        return _buffer.space() <= 1;
    }

    /* ------------------------------------------------------------ */
    private void initContent() throws IOException
    {
        if (_buffer == null)
        {
            Log.debug("initContent");
            _buffer = _buffers.getBuffer();
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isRequest()
    {
        return false;
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isResponse()
    {
        return true;
    }

    /* ------------------------------------------------------------ */
    @Override
    public int prepareUncheckedAddContent() throws IOException
    {
        initContent();
        return _buffer.space();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void completeHeader(HttpFields fields, boolean allContentAdded) throws IOException
    {
        Log.debug("completeHeader: {}",fields);
        if (_state != STATE_HEADER)
            return;

        if (_last && !allContentAdded)
            throw new IllegalStateException("last?");
        _last = _last | allContentAdded;

        if (_persistent==null)
            _persistent=(_version > HttpVersions.HTTP_1_0_ORDINAL);


        if (_reason == null)
            _response.setStatus(_status);
        else
            _response.setStatus(_status,_reason.toString());

        if (_status == 100 || _status == 204 || _status == 304)
        {
            _noContent = true;
            _content = null;
        }


        boolean has_server = false;
        if (fields != null)
        {
            // Add headers
            int s=fields.size();
            for (int f=0;f<s;f++)
            {
                HttpFields.Field field = fields.getField(f);
                if (field==null)
                    continue;
                _response.setHeader(field.getName(),field.getValue());
            }
        }

        if (!has_server && _status > 100 && getSendServerVersion())
            _response.setHeader(HttpHeaders.SERVER,"Jetty("+Server.getVersion()+",nested in "+_nestedIn+")");

        _state = STATE_CONTENT;
    }

    /* ------------------------------------------------------------ */
    @Override
    public long flushBuffer() throws IOException
    {
        if (_state == STATE_HEADER)
            throw new IllegalStateException("State==HEADER");
        

        if (_content != null && _content.length() < _buffer.space() && _state != STATE_FLUSHING)
        {
            initContent();
            _buffer.put(_content);
            _content.clear();
            _content = null;
        }
        
        if (_buffer==null)
            return 0;
        
        int size=_buffer.length();
        int len = _buffer==null?0:_endp.flush(_buffer);
        Log.debug("flushBuffer {} of {}",len,size);
        if (len>0)
            _buffer.skip(len);

        return len;
    }

}
