package gsn.vsensor;

import gsn.beans.StreamElement;
import gsn.beans.VSensorConfig;
import gsn.storage.StorageManager;
import gsn.utils.GSNRuntimeException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.TreeMap;

import org.apache.log4j.Logger;

/**
 * This virtual sensor saves its input stream to any JDBC accessible source.
 */
public class StreamExporterVirtualSensor extends AbstractVirtualSensor {

	public static final String            PARAM_USER    = "user" , PARAM_PASSWD = "password" , PARAM_URL = "url" , TABLE_NAME = "table",PARAM_DRIVER="driver";

	public static final String[] OBLIGATORY_PARAMS = new String[] {PARAM_USER,PARAM_URL,PARAM_DRIVER};

	private static final transient Logger logger        = Logger.getLogger( StreamExporterVirtualSensor.class );

	private Connection                    connection;

	private CharSequence table_name;

	private String password;

	private String user;
	
	private String url;

	public boolean initialize ( ) {
		VSensorConfig vsensor = getVirtualSensorConfiguration( );
		TreeMap < String , String > params = vsensor.getMainClassInitialParams();

		for (String param : OBLIGATORY_PARAMS)
			if ( params.get( param ) == null || params.get(param).trim().length()==0) {
				logger.warn("Initialization Failed, The "+param+ " initialization parameter is missing");
				return false;
			}
		table_name = params.get( TABLE_NAME );
		user = params.get(PARAM_USER);
		password = params.get(PARAM_PASSWD);
		url = params.get(PARAM_URL);
		try {
			Class.forName(params.get(PARAM_DRIVER));
			connection = getConnection();
			logger.debug( "jdbc connection established." );
			if (!StorageManager.tableExists(table_name,getVirtualSensorConfiguration().getOutputStructure() , connection))
				StorageManager.executeCreateTable(table_name, getVirtualSensorConfiguration().getOutputStructure(), false,connection);
		} catch (ClassNotFoundException e) {
			logger.error(e.getMessage(),e);
			logger.error("Initialization of the Stream Exporter VS failed !");
			return false;
		} catch (SQLException e) {
			logger.error(e.getMessage(),e);
			logger.error("Initialization of the Stream Exporter VS failed !");
			return false;
		}catch (GSNRuntimeException e) {
			logger.error(e.getMessage(),e);
			logger.error("Initialization failed. There is a table called " + TABLE_NAME+ " Inside the database but the structure is not compatible with what GSN expects.");
			return false;
		}
		return true;
	}

	public void dataAvailable ( String inputStreamName , StreamElement streamElement ) {
		StringBuilder query = StorageManager.getStatementInsert(table_name, getVirtualSensorConfiguration().getOutputStructure());
		
		try {
			StorageManager.executeInsert(table_name ,getVirtualSensorConfiguration().getOutputStructure(),streamElement,getConnection() );
		} catch (SQLException e) {
			logger.error(e.getMessage(),e);
			logger.error("Insertion failed! ("+ query+")");
		}finally {
			dataProduced( streamElement );
		}
		
	}


	public Connection getConnection() throws SQLException {
		if (this.connection==null || this.connection.isClosed())
			this.connection=DriverManager.getConnection(url,user,password);
		return connection;
	}


	public void dispose ( ) {
	}
}