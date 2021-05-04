package org.eclipse.jetty.http3.qpack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.MetaData;

import static org.eclipse.jetty.http3.qpack.QpackEncoder.C_METHODS;
import static org.eclipse.jetty.http3.qpack.QpackEncoder.C_SCHEME_HTTP;
import static org.eclipse.jetty.http3.qpack.QpackEncoder.C_SCHEME_HTTPS;
import static org.eclipse.jetty.http3.qpack.QpackEncoder.STATUSES;

public class Http3Fields implements HttpFields
{
    private final List<HttpField> pseudoHeaders = new ArrayList<>(8);
    private final HttpFields httpFields;

    public Http3Fields(MetaData metadata)
    {
        httpFields = metadata.getFields();
        if (metadata.isRequest())
        {
            MetaData.Request request = (MetaData.Request)metadata;
            String method = request.getMethod();
            HttpMethod httpMethod = method == null ? null : HttpMethod.fromString(method);
            HttpField methodField = C_METHODS.get(httpMethod);
            pseudoHeaders.add(methodField == null ? new HttpField(HttpHeader.C_METHOD, method) : methodField);
            pseudoHeaders.add(new HttpField(HttpHeader.C_AUTHORITY, request.getURI().getAuthority()));

            boolean isConnect = HttpMethod.CONNECT.is(request.getMethod());
            String protocol = request.getProtocol();
            if (!isConnect || protocol != null)
            {
                pseudoHeaders.add(HttpScheme.HTTPS.is(request.getURI().getScheme()) ? C_SCHEME_HTTPS : C_SCHEME_HTTP);
                pseudoHeaders.add(new HttpField(HttpHeader.C_PATH, request.getURI().getPathQuery()));

                if (protocol != null)
                    pseudoHeaders.add(new HttpField(HttpHeader.C_PROTOCOL, protocol));
            }
        }
        else if (metadata.isResponse())
        {
            MetaData.Response response = (MetaData.Response)metadata;
            int code = response.getStatus();
            HttpField status = code < STATUSES.length ? STATUSES[code] : null;
            if (status == null)
                status = new HttpField.IntValueHttpField(HttpHeader.C_STATUS, code);
            pseudoHeaders.add(status);
        }
    }

    @Override
    public Immutable asImmutable()
    {
        return new Immutable(stream().toArray(HttpField[]::new));
    }

    @Override
    public HttpField getField(int index)
    {
        if (index < pseudoHeaders.size())
            return pseudoHeaders.get(index);
        else if (httpFields != null)
            return httpFields.getField(index - pseudoHeaders.size());
        else
            throw new NoSuchElementException();
    }

    @Override
    public int size()
    {
        if (httpFields == null)
            return pseudoHeaders.size();
        return pseudoHeaders.size() + httpFields.size();
    }

    @Override
    public Stream<HttpField> stream()
    {
        if (httpFields == null)
            return pseudoHeaders.stream();
        return Stream.concat(pseudoHeaders.stream(), httpFields.stream());
    }

    @Override
    public Iterator<HttpField> iterator()
    {
        return stream().iterator();
    }
}
