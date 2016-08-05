package org.eclipse.jetty.load.generator;

import org.eclipse.jetty.client.api.Result;

public interface ResultHandler
{

    void onResponse( Result result );

}
