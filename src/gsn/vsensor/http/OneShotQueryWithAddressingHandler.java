package gsn.vsensor.http;

import gsn.Mappings;
import gsn.beans.DataTypes;
import gsn.beans.StreamElement;
import gsn.beans.VSensorConfig;
import gsn.storage.DataEnumerator;
import gsn.storage.StorageManager;
import gsn.vsensor.Container;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.collections.KeyValue;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.log4j.Logger;


/**
 * @author alisalehi
 *
 */
public class OneShotQueryWithAddressingHandler implements RequestHandler{

   private static transient Logger                                      logger                             = Logger.getLogger( OneShotQueryHandler.class );
   
   public void handle ( HttpServletRequest request , HttpServletResponse response ) throws IOException {
      String vsName = request.getParameter( "name" );
      String vsCondition = request.getParameter( "condition" );
      if ( vsCondition == null || vsCondition.trim( ).length( ) == 0 )
         vsCondition = " ";
      else
         vsCondition = " where " + vsCondition;
      String vsFields = request.getParameter( "fields" );
      if ( vsFields == null || vsFields.trim( ).length( ) == 0 || vsFields.trim( ).equals( "*" ) )
         vsFields = "*";
      else
         vsFields += " , pk, timed";
      String windowSize = request.getParameter( "window" );
      if ( windowSize == null || windowSize.trim( ).length( ) == 0 ) windowSize = "1";
      StringBuilder query = new StringBuilder( "select " + vsFields + " from " + vsName + vsCondition + " order by TIMED DESC limit " + windowSize + " offset 0" );
      DataEnumerator  result = StorageManager.getInstance( ).executeQuery( query , true );
      StringBuilder sb = new StringBuilder("<result>\n");
      while ( result.hasMoreElements( ) ) {
         StreamElement se = result.nextElement( );
         sb.append( "<stream-element>\n" );
         for ( int i = 0 ; i < se.getFieldNames( ).length ; i++ )
            if ( se.getFieldTypes( )[ i ] == DataTypes.BINARY )
               sb.append( "<field name=\"" ).append( se.getFieldNames( )[ i ] ).append( "\">" ).append( se.getData( )[ i ].toString( ) ).append( "</field>\n" );
            else
               sb.append( "<field name=\"" ).append( se.getFieldNames( )[ i ] ).append( "\">" ).append( StringEscapeUtils.escapeXml( se.getData( )[ i ].toString( ) ) ).append( "</field>\n" );
         sb.append( "<field name=\"TIMED\" >" ).append( se.getTimeStamp( ) ).append( "</field>\n" );
         VSensorConfig sensorConfig = Mappings.getVSensorConfig( vsName );
         if ( logger.isInfoEnabled( ) ) logger.info( new StringBuilder( ).append( "Structure request for *" ).append( vsName ).append( "* received." ).toString( ) );
         //StringBuilder sb = new StringBuilder( "<virtual-sensor name=\"" ).append( vsName ).append( "\" last-modified=\"" ).append( new File( sensorConfig.getFileName( ) ).lastModified( ) ).append( "\">\n" );
         for ( KeyValue df : sensorConfig.getAddressing( ) )
            sb.append( "<field name=\"" ).append( StringEscapeUtils.escapeXml( df.getKey( ).toString( ) ) ).append( "\">" ).append( StringEscapeUtils.escapeXml( df.getValue( ).toString( ) ) )
                  .append( "</field>\n" );
         sb.append( "</stream-element>\n" );
      }
      result.close();
      sb.append( "</result>" );
      response.getWriter( ).write( sb.toString( ) );
   }

   public boolean isValid ( HttpServletRequest request , HttpServletResponse response ) throws IOException {
      String vsName = request.getParameter( "name" );
      if ( vsName == null || vsName.trim( ).length( )==0 ) {
         response.sendError( Container.MISSING_VSNAME_ERROR , "The virtual sensor name is missing" );
         return false;
      }
      VSensorConfig sensorConfig = Mappings.getVSensorConfig( vsName );
      if ( sensorConfig == null ) {
         response.sendError( Container.ERROR_INVALID_VSNAME , "The specified virtual sensor doesn't exist." );
         return false;
      }
      return true;
   }
   
}
