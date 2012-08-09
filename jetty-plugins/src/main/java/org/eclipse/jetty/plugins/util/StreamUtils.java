package org.eclipse.jetty.plugins.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class StreamUtils {
	public static String inputStreamToString(InputStream inputStream) throws IOException {
		BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
		StringBuilder stringBuilder = new StringBuilder();
		String line = null;

	    while ((line = bufferedReader.readLine()) != null) {
	    	stringBuilder.append(line + "\n");
	    }
	
	    bufferedReader.close();
	    return stringBuilder.toString();
    }
}
