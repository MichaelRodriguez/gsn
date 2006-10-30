package gsn.storage;

import gsn.beans.DataTypes;
import gsn.beans.StreamElement;
import gsn.wrappers.StreamProducer;

import java.io.Serializable;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Enumeration;
import java.util.Vector;

import org.apache.log4j.Logger;

/**
 * @author Ali Salehi (AliS, ali.salehi-at-epfl.ch)<br>
 * FIXME : 1. Because a prepared statements relies on the connection being
 * there. If a connection times out the pool will restore it and then the
 * prepared statement is then stale. The only way you can tell this it to use
 * it, and any speed advantage is negated when you have to try it, catch the
 * error, regen the statement and go again. 2. You use a connection pool in
 * threaded enviroment. A prepared statement is a single instance that can only
 * be used in a single thread. So either you block all the threads waiting for
 * the single prepared statement (and then you have to wonder why you are using
 * threads) or you have multiple prepared statements for same thing.
 */
public class DataEnumerator implements Enumeration {
   
   private transient Logger logger                   = Logger.getLogger( DataEnumerator.class );
   
   private ResultSet        resultSet                = null;
   
   private String [ ]       dataFieldNames;
   
   private Integer [ ]      dataFieldTypes;
   
   private boolean          hasNext                  = false;
   
   boolean                  hasTimedFieldInResultSet = false;
   
   int                      indexOfTimedField        = -1;
   
   int                      indexofPK                = -1;
   
   boolean linkBinaryData = false;
   
   public DataEnumerator ( ) {
      hasNext = false;
   }
   
   public DataEnumerator ( ResultSet rs ,boolean binaryLinked) throws SQLException {
      if ( rs == null ) throw new IllegalStateException( "The provided ResultSet is Null." );
      this.linkBinaryData=binaryLinked;
      this.resultSet = rs;
      hasNext = resultSet.next( );
      Vector < String > fieldNames = new Vector < String >( );
      Vector < Integer > fieldTypes = new Vector < Integer >( );
      
      // Initializing the fieldNames and fieldTypes.
      // Also setting the values for <code> hasTimedFieldInResultSet</code>
      // if the TIMED field is present in the result set.
      for ( int i = 1 ; i <= resultSet.getMetaData( ).getColumnCount( ) ; i++ ) {
         String colName = resultSet.getMetaData( ).getColumnName( i );
         int colTypeInJDBCFormat = resultSet.getMetaData( ).getColumnType( i );
         if ( colName.equalsIgnoreCase( "PK" ) ) {
            indexofPK = i;
         } else if ( colName.equalsIgnoreCase( StreamProducer.TIME_FIELD ) ) {
            indexOfTimedField = i;
         } else {
            fieldNames.add( colName );
            fieldTypes.add( DataTypes.convertFromJDBCToGSNFormat( colTypeInJDBCFormat ) );
         }
      }
      dataFieldNames = fieldNames.toArray( new String [ ] {} );
      dataFieldTypes = fieldTypes.toArray( new Integer [ ] {} );
      if (indexofPK==-1 && linkBinaryData)
   	   throw new RuntimeException("The specified query can't be used with binaryLinked paramter set to true."); 
   }
   
   private StreamElement streamElement = null;

   
   public boolean hasMoreElements ( ) {
      return hasNext;
   }
   public StreamElement nextElementLight ( ) {
	   return null;
   }
   public StreamElement nextElement ( ) throws RuntimeException {
       long timestamp = -1;
	  long pkValue = -1;
	    try {
	  if (indexofPK!=-1)
	   pkValue= resultSet.getLong(indexofPK);
      if ( hasNext == false ) return null;
         Serializable [ ] output = new Serializable [ dataFieldNames.length ];
         for ( int actualColIndex = 1 , innerIndex = 0 ; actualColIndex <= resultSet.getMetaData( ).getColumnCount( ) ; actualColIndex++ ) {
            if (actualColIndex == indexOfTimedField) {
          	   timestamp=resultSet.getLong(actualColIndex);
          	   continue;
            }else
            if (actualColIndex==indexofPK)
            	continue;
            else
            switch ( dataFieldTypes[ innerIndex ] ) {
               case DataTypes.VARCHAR :
               case DataTypes.CHAR :
                  output[ innerIndex ] = resultSet.getString( actualColIndex );
                  break;
               case DataTypes.INTEGER :
                  output[ innerIndex ] = resultSet.getInt( actualColIndex );
                  break;
               case DataTypes.TINYINT :
                  output[ innerIndex ] = resultSet.getByte( actualColIndex );
                  break;
               case DataTypes.SMALLINT :
                  output[ innerIndex ] = resultSet.getShort( actualColIndex );
                  break;
               case DataTypes.DOUBLE :
                  output[ innerIndex ] = resultSet.getDouble( actualColIndex );
                  break;
               case DataTypes.BIGINT :
                  output[ innerIndex ] = resultSet.getLong( actualColIndex );
                  break;
               case DataTypes.BINARY :
            	   if (linkBinaryData) 
            		  output[innerIndex] = "/field?vs="+resultSet.getMetaData().getTableName(actualColIndex)+"&field="+resultSet.getMetaData().getColumnName(actualColIndex)+"&pk="+pkValue;
            	   else
            		   output[ innerIndex ] = resultSet.getBytes( actualColIndex );
                  break;
            }
            innerIndex++;
         }
         
         streamElement = new StreamElement( dataFieldNames , dataFieldTypes , output , indexOfTimedField==-1?System.currentTimeMillis():timestamp );
         if (indexofPK!=-1)
        	streamElement.setInternalPrimayKey(pkValue); 
         hasNext = resultSet.next( );
         if ( hasNext == false ) resultSet.getStatement( ).getConnection( ).close( );
      } catch ( SQLException e ) {
         logger.error( e.getMessage( ) , e );
         try {
            resultSet.close( );
         } catch ( SQLException e1 ) {}
      }
      return streamElement;
   }
}
