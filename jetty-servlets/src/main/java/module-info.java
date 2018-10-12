module org.eclipse.jetty.servlets {
    exports org.eclipse.jetty.servlets;

    requires static javax.servlet.api;
    requires static org.eclipse.jetty.util;
    requires static org.eclipse.jetty.io;
    requires static org.eclipse.jetty.http;
    requires static org.eclipse.jetty.server;
}
