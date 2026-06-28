package one.suhl2.discify.util;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class AuthServerHandler implements HttpHandler
{
    public static final Logger LOGGER = LogManager.getLogger("Discify");

    @Override
    public void handle(HttpExchange httpExchange) throws IOException
    {
        String requestParamValue = null;
        if ("GET".equals(httpExchange.getRequestMethod()))
        {
            requestParamValue = handleGetRequest(httpExchange);
        }
        try
        {
            handleResponse(httpExchange, requestParamValue);
        } catch (URISyntaxException | InterruptedException e)
        {
            LOGGER.error(e.getMessage());
        }
    }

    private String handleGetRequest(HttpExchange httpExchange)
    {
        String query = httpExchange.getRequestURI().getQuery();
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2 && pair[0].equals("code")) {
                return pair[1];
            }
        }
        return null;
    }

    private void handleResponse(HttpExchange httpExchange, String requestParamValue)
            throws IOException, URISyntaxException, InterruptedException
    {
        OutputStream outputStream = httpExchange.getResponseBody();
        StringBuilder htmlBuilder = new StringBuilder();
        htmlBuilder.append("<html>")
                .append("<body>")
                .append("<h1>")
                .append("Discify — Authorization Successful!")
                .append("</h1>")
                .append("<p>You can close this tab and return to Minecraft.</p>")
                .append("</body>")
                .append("</html>");

        String htmlResponse = htmlBuilder.toString();
        httpExchange.sendResponseHeaders(200, htmlResponse.length());
        outputStream.write(htmlResponse.getBytes());
        outputStream.flush();
        outputStream.close();
        SpotifyUtil.authorize(requestParamValue);
    }
}
