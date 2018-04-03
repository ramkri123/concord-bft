/**
 * This servlet is used to serve static content.
 * 
 * Endpoints serviced : 
 * /assets/* : Loads content in the priv/www/assets folder
 * /api and /api/ : Loads content from priv/swagger.json
 * /swagger/* : Loads content from priv/www/swagger folder
 * /* : Loads content from priv/www/index.html
 * 
 */
package Servlets;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.json.simple.parser.ParseException;

import configurations.SystemConfiguration;
import io.undertow.util.CanonicalPathUtils;
import io.undertow.util.StatusCodes;

/**
 * Servlet class.
 */
public class StaticContent extends HttpServlet {
   private static final long serialVersionUID = 1L;
   private static Properties config;
   private final Logger logger;

   /**
    * APIs serviced
    */
   private enum Api {
   ASSETS, SWAGGER, API_LIST, DEFAULT_CONTENT;
   }

   public StaticContent() throws IOException, ParseException {
      logger = Logger.getLogger(StaticContent.class);
      // Read configurations
      SystemConfiguration s;
      try {
         s = SystemConfiguration.getInstance();
      } catch (IOException e) {
         logger.error("Error in reading configurations");
         throw e;
      }
      config = s.configurations;
   }

   /**
    * Services a get request. Fetches the resource from the specified path and
    * returns it.
    * 
    * @param request
    *           The request received by the servlet
    * @param response
    *           The response object used to respond to the client
    */
   @SuppressWarnings("resource")
   protected void doGet(final HttpServletRequest request,
            final HttpServletResponse response)
            throws ServletException, IOException {

      String callingApi = request.getServletPath();
      if (callingApi.length() > 0) {
         callingApi = callingApi.substring(1);
      }

      Api api;
      String contentFolder = new String();
      String contentFile = new String();
      String alternateApiListEndpoint = config
               .getProperty("Alternate_ApiList_Endpoint");

      // Read configurations based on API
      switch (callingApi) {

      case "assets":
         logger.trace("/assets API call");
         api = Api.ASSETS;
         contentFolder = config.getProperty("Assets_Folder");
         break;
      case "swagger":
         logger.trace("/swagger API call");
         api = Api.SWAGGER;
         contentFolder = config.getProperty("Swagger_Folder");
         break;
      case "api":
         logger.trace("/api API call");
         api = Api.API_LIST;
         contentFolder = config.getProperty("ApiList_Folder");
         contentFile = config.getProperty("ApiList_File");
         break;
      case "":
         String uri = request.getRequestURI();

         // Load swagger UI if user enters /api/
         if (uri.startsWith(alternateApiListEndpoint)) {
            logger.trace("/api API call");
            contentFolder = config.getProperty("ApiList_Folder");
            contentFile = config.getProperty("ApiList_File");
            api = Api.API_LIST;
         } else {
            logger.trace("Default Content API call");
            api = Api.DEFAULT_CONTENT;
            contentFolder = config.getProperty("Server_DefaultResponse");
            contentFile = "";
         }
         break;
      default:
         return;
      }

      File file = null;
      if (api == Api.ASSETS || api == Api.SWAGGER) {
         // These API calls contain the file path in the request

         /*
          * If the separator char is not / we want to replace it with a / and
          * canonicalise
          */
         String contentPath = CanonicalPathUtils
                  .canonicalize(StaticContentHelper.getPath(request));
         contentPath = contentPath.replace('/', File.separatorChar);

         //Users need to request for a specific resource
         if (contentPath.endsWith("/")) {
            try {
               logger.error("Path does not point to specific file");
               response.sendError(StatusCodes.NOT_FOUND);
            } catch (IOException e) {
               logger.error("Error in sending error message to client");
               throw e;
            }
            return;
         }

         // "." denotes current directory
         Path path = Paths.get(".", contentFolder, contentPath);
         file = path.toFile();

      } else if (api == Api.API_LIST || api == Api.DEFAULT_CONTENT) {
         // These API calls have fixed content to be served

         Path p = Paths.get(".", contentFolder, contentFile);
         file = p.toFile();
      }

      // read the file
      FileInputStream inputStream;
      try {
         inputStream = new FileInputStream(file);
      } catch (FileNotFoundException e) {
         logger.error("File not found : " + file.getAbsolutePath());
         throw e;
      }

      // Detect file type
      String fileType = StaticContentHelper.detectFileType(file);

      // Set file type and charset
      response.setContentType(fileType + ";charset=UTF-8");

      // Respond to client
      int c;
      try {
         byte[] buf = new byte[1024];
         while ((c = inputStream.read(buf, 0, buf.length)) != -1) {
            response.getOutputStream().write(buf, 0, c);
         }
      } catch (IOException e) {
         logger.error("Error in reading from tcp input stream");
         throw e;
      }

      // close input stream
      try {
         inputStream.close();
      } catch (IOException e) {
         logger.error("Error in closing input stream");
         throw e;
      }
   }
}