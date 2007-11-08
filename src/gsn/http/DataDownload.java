package gsn.http;

import gsn.Container;
import gsn.beans.StreamElement;
import gsn.storage.DataEnumerator;
import gsn.storage.StorageManager;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.log4j.Logger;

public class DataDownload extends HttpServlet {
  
  private static transient Logger                                logger                              = Logger.getLogger ( DataDownload.class );
  
  public void  doGet ( HttpServletRequest req , HttpServletResponse res ) throws ServletException , IOException {
    doPost(req, res);
  }
  /**
   * List of the parameters for the requests:
   * url : /data
   * Example: Getting all the data in CSV format => http://localhost:22001/data?vsName=memoryusage4&fields=heap&display=CSV
   * another example: http://localhost:22001/data?vsName=memoryusage4&fields=heap&fields=timed&display=CSV&delimiter=other&otherdelimiter=,
   * 
   * param-name: vsName : the name of the virtual sensor we need.
   * param-name: fields [there can be multiple parameters with this name pointing to different fields in the stream element].
   * param-name: commonReq (always true !)
   * param-name: display , if there is a value it should be CSV.
   * param-name: delimiter, useful for CSV output (can be "tab","space","other")
   * param-name: otherdelimiter useful in the case of having delimiter=other
   * param-name: groupby can point to one of the fields in the stream element. In case groupby=timed then the parameter groupbytimed points to the period for which data should be aggregated [in milliseconds]. 
   * param-name: nb give the maximum number of elements to be outputed (most recent values first).
   * param-name: 
   */
  public void doPost ( HttpServletRequest req , HttpServletResponse res ) throws ServletException , IOException {
    boolean responseCVS = false;
    boolean wantTimeStamp = false;
    boolean commonReq = true;
    boolean groupByTimed = false;
    PrintWriter respond = res.getWriter();
    String vsName = HttpRequestUtils.getStringParameter("vsName", null,req);
    if (vsName ==null)
      vsName = HttpRequestUtils.getStringParameter("vsname", null,req) ;
    if (vsName==null) {
      res.sendError( Container.MISSING_VSNAME_ERROR , "The virtual sensor name is missing" );
      return;
    }
    if (req.getParameter("display") != null && req.getParameter("display").equals("CSV")) {
      responseCVS = true;
      res.setContentType("text/csv");
      //res.setContentType("text/html");
    } else {
      res.setContentType("text/xml");
    }
    if (req.getParameter("commonReq") != null && req.getParameter("commonReq").equals("false")) {
      commonReq = false;
    }
    String delimiter = ";";
    if (req.getParameter("delimiter") != null && !req.getParameter("delimiter").equals("")) {
      String reqdelimiter = req.getParameter("delimiter");
      if (reqdelimiter.equals("tab")) {
        delimiter = "\t";
      } else if (reqdelimiter.equals("space")){
        delimiter = " ";
      } else if (reqdelimiter.equals("other") && req.getParameter("otherdelimiter") != null && !req.getParameter("otherdelimiter").equals("")) {
        delimiter = req.getParameter("otherdelimiter");
      }
    }
    String generated_request_query = "";
    String expression = "";
    String line="";
    String groupby="";
    String[] fields = req.getParameterValues("fields");
    if (commonReq) {
      if (req.getParameter("fields") != null) {
        for (int i=0; i < fields.length; i++) {
          if (fields[i].equals("timed")) {
            wantTimeStamp = true;
          }
          generated_request_query += ", " + fields[i];
        }    
      }
    } else {
      if (req.getParameter("fields") == null) {
        respond.println("Request ERROR");
        return;
      } else {
        for (int i=0; i < fields.length; i++) {
          if (fields[i].equals("timed")) {
            wantTimeStamp = true;
          }
          generated_request_query += ", " + fields[i];
        }    
      }
      if (req.getParameter("groupby") != null) {
        if (req.getParameter("groupby").equals("timed")) {
          groupByTimed = true;
          int periodmeasure = 1;
          if (req.getParameter("groupbytimed")!=null) {
            periodmeasure = new Integer(req.getParameter("groupbytimed"));
            periodmeasure = java.lang.Math.max(periodmeasure, 1);
          }
          generated_request_query += ", Min(timed), FLOOR(timed/" + periodmeasure + ") period "; 
          groupby = "GROUP BY period";
        } else {
          groupby = "GROUP BY " + req.getParameter("groupby");
        }
      }
    }
    
    String limit = "";
    if (req.getParameter("nb") != null && req.getParameter("nb") != "") {
      int nb = new Integer(req.getParameter("nb"));
      if (nb > 0) {
        limit = "LIMIT " + nb + "  offset 0";
      }
    }
    String where = "";
    if (req.getParameter("critfield") != null) {
      try {
        String[] critJoin = req.getParameterValues("critJoin");
        String[] neg = req.getParameterValues("neg");
        String[] critfields = req.getParameterValues("critfield");
        String[] critop = req.getParameterValues("critop");
        String[] critval = req.getParameterValues("critval");
        for (int i=0; i < critfields.length ; i++) {
          if (critop[i].equals("LIKE")) {
            if (i > 0) {
              where += " " + critJoin[i-1] + " " + neg[i] + " " + critfields[i] + " LIKE '%"; // + critval[i] + "%'";
            } else {
              where += neg[i] + " " + critfields[i] + " LIKE '%"; // + critval[i] + "%'";
            }
            if (critfields[i].equals("timed")) {
              try {
                SimpleDateFormat sdf = new SimpleDateFormat ("dd/MM/yyyy HH:mm:ss");
                Date d = sdf.parse(critval[i]);
                where += d.getTime();
              } catch (Exception e) {
                where += "0";
              }
            } else {
              where += critval[i];
            }
            where += "%'";
          } else {
            if (i > 0) {
              where += " " + critJoin[i-1] + " " + neg[i] + " " + critfields[i] + " " + critop[i] + " "; //critval[i];
            } else {
              where += neg[i] + " " + critfields[i] + " " + critop[i] + " "; //critval[i];
            }
            if (critfields[i].equals("timed")) {
              try {
                SimpleDateFormat sdf = new SimpleDateFormat ("dd/MM/yyyy HH:mm:ss");
                Date d = sdf.parse(critval[i]);
                where += d.getTime();
              } catch (Exception e) {
                //System.out.println(e.toString());
                where += "0";
              }
            } else {
              where += critval[i];
            }
          }
        }
        where = " WHERE " + where;
      } catch (NullPointerException npe) {
        where = " ";
      }
    }
    
    if (! generated_request_query.equals("")) {
      generated_request_query = generated_request_query.substring(2);
      if (!commonReq) {
        expression = generated_request_query;
      }
      generated_request_query = "select "+generated_request_query+" from " + vsName + where;
      if (commonReq) {
        generated_request_query += " order by timed DESC "+limit;
      }
      generated_request_query += " " + groupby;
      generated_request_query += ";";
      
      if (req.getParameter("sql") != null) {
        res.setContentType("text/html");
        respond.println("#"+generated_request_query);
        return;
      }
      
      DataEnumerator result;
      try {
        result = StorageManager.getInstance( ).executeQuery( new StringBuilder(generated_request_query) , false );
      } catch (SQLException e) {
        logger.error("ERROR IN EXECUTING, query: "+generated_request_query);
        logger.error(e.getMessage(),e);
        logger.error("Query is from "+req.getRemoteAddr()+"- "+req.getRemoteHost());
        return;
      }
      if (!result.hasMoreElements()) {
        res.setContentType("text/html");
        respond.println("No data corresponds to your request");
        return;
      }
      line = "";
      int nbFields = 0;
      if (responseCVS) {
        boolean firstLine = true;
        respond.println("#"+generated_request_query);
        while ( result.hasMoreElements( ) ) {
          StreamElement se = result.nextElement( );
          if (firstLine) {
            nbFields = se.getFieldNames().length;
            if (groupByTimed) {
              nbFields--;
            }
            for (int i=0; i < nbFields; i++)
              //line += delimiter + se.getFieldNames()[i].toString();
              if ((!groupByTimed) || (i != fields.length)) {
                line += delimiter + fields[i];
              } else {
                line += delimiter + "timed";
              }
            if (wantTimeStamp) {
              line += delimiter + "timed";
            }
            firstLine = false;
          }
          respond.println(line.substring(delimiter.length()));
          line = "";
          for ( int i = 0 ; i < nbFields ; i++ )
            //line += delimiter+se.getData( )[ i ].toString( );
            
            if ( !commonReq && ((i >= fields.length) || (fields[i].contains("timed")))) {
              SimpleDateFormat sdf = new SimpleDateFormat ("dd/MM/yyyy HH:mm:ss");
              line += delimiter+sdf.format(se.getData( )[i]);
            } else {
              line += delimiter+se.getData( )[ i ].toString( );
            }
          if (wantTimeStamp) {
            SimpleDateFormat sdf = new SimpleDateFormat ("dd/MM/yyyy HH:mm:ss");
            Date d = new Date (se.getTimeStamp());
            line += delimiter + sdf.format(d);
          }
          respond.println(line.substring(delimiter.length()));
        }
      } else {
        boolean firstLine = true;
        respond.println("<data>");
        while ( result.hasMoreElements( ) ) {
          StreamElement se = result.nextElement( );
          if (firstLine) {
            respond.println("\t<line>");
            nbFields = se.getFieldNames().length;
            if (groupByTimed) {
              nbFields--;
            }
            for (int i = 0; i < nbFields; i++)
              //if (commonReq) {
              //out.println("\t\t<field>" + se.getFieldNames()[i].toString()+"</field>");
              if ((!groupByTimed) || (i != fields.length)) {
                respond.println("\t\t<field>" + fields[i] + "</field>");
              } else {
                respond.println("\t\t<field>timed</field>");
              }
            //} else {
            //	 out.println("\t\t<field>"+expression+"</field>");
            //}
            if (wantTimeStamp) {
              respond.println("\t\t<field>timed</field>");
            }
            respond.println("\t</line>");
            firstLine = false;
          }
          line = "";
          respond.println("\t<line>");
          for ( int i = 0 ; i < nbFields ; i++ ) {
            
            //if ( !commonReq && expression.contains("timed")) {
            if ( !commonReq && ((i >= fields.length) || (fields[i].contains("timed")))) {
              SimpleDateFormat sdf = new SimpleDateFormat ("dd/MM/yyyy HH:mm:ss");
              respond.println("\t\t<field>"+sdf.format(se.getData( )[i])+"</field>");
            } else {
              if (se.getData()[i]==null)
                respond.println("\t\t<field>Null</field>");
              else
                respond.println("\t\t<field>"+se.getData( )[ i ].toString( )+"</field>");
            }
          }
          if (wantTimeStamp) {
            SimpleDateFormat sdf = new SimpleDateFormat ("dd/MM/yyyy HH:mm:ss");
            Date d = new Date (se.getTimeStamp());
            respond.println("\t\t<field>"+sdf.format(d)+"</field>");
          }
          respond.println("\t</line>");
        }
        respond.println("</data>");
      }
      result.close();
      //*/
    } else {
      res.setContentType("text/html");
      respond.println("Please select some fields");
    }
  }
}