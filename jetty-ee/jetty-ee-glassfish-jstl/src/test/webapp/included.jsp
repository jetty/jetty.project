<%
  String headerPrefix = "";
  if(request.getDispatcherType() == DispatcherType.INCLUDE)
    headerPrefix = "org.eclipse.jetty.server.include.";

  response.setHeader(headerPrefix + "included-page-key","included-page-value");
%>
<h3> This is the included page