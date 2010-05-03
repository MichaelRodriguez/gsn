package gsn.wrappers.backlog.plugins;

import org.apache.log4j.Logger;
import org.h2.tools.Server;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

import gsn.beans.AddressBean;
import gsn.beans.DataField;
import gsn.storage.StorageManager;
import gsn.wrappers.BackLogWrapper;



/**
 * This plugin offers the functionality to download binaries from a deployment
 * in the size of up to 4GB. The binaries will be sent in chunks. Thus, no significant
 * interrupts of other plugin traffic is guaranteed. In case of a connection loss,
 * the download of the actual binary will be resumed as soon
 * as GSN reconnects to the deployment. The downloaded binaries
 * can be stored on disk or in the database. This will be configured on side of the
 * deployment.
 * <p>
 * The 'storage-directory' predicate (defined in the virtual sensor's XML file) has
 * to be used to specify the storage location. If the binaries are stored in the
 * database the directory is only used to store the partly downloaded binary. If
 * the binaries are stored on disk, it defines the root directory in which the
 * binaries will be stored.
 * <p>
 * If the binaries should be stored on disk the same folder structure as on side of
 * the deployment is used. In addition to that the binaries are separated into subfolders
 * named and sorted after the binaries modification time. The needed resolution of separation
 * can be specified on side of the deployment.
 * 
 * @author Tonio Gsell
 * <p>
 * TODO: remove CRC functionality after long time testing. It is not necessary over TCP.
 */
public class BinaryPlugin extends AbstractPlugin {
	
	protected static final int RESEND_INTERVAL_SEC = 10;
	
	private static final String STORAGE_DIRECTORY = "storage-directory";
	
	private static final String PROPERTY_REMOTE_BINARY = "remote_binary";
	private static final String PROPERTY_DOWNLOADED_SIZE = "downloaded_size";
	private static final String PROPERTY_BINARY_TIMESTAMP = "timestamp";
	private static final String PROPERTY_BINARY_SIZE = "file_size";
	protected static final String PROPERTY_CHUNK_NUMBER = "chunk_number";
	protected static final String PROPERTY_STORAGE_TYPE = "storage_type";
	protected static final String PROPERTY_TIME_DATE_FORMAT = "time_date_format";
	
	private static final String TEMP_BINARY_NAME = "binaryplugin_download.part";
	private static final String PROPERTY_FILE_NAME = ".gsnBinaryStat";

	protected static final byte ACK_PACKET = 0;
	protected static final byte INIT_PACKET = 1;
	protected static final byte RESEND_PACKET = 2;
	protected static final byte CHUNK_PACKET = 3;
	protected static final byte CRC_PACKET = 4;

	private Timer connectionTestTimer = null;
	private SimpleDateFormat folderdatetimefm;

	private String rootBinaryDir;

	protected final transient Logger logger = Logger.getLogger( BinaryPlugin.class );
	
	private DataField[] dataField = new DataField[] {new DataField("GENERATIONTIME", "BIGINT"),
										new DataField("DEVICE_ID", "INTEGER"),
			   							new DataField("RELATIVEFILE", "VARCHAR(255)"),
			   							new DataField("STORAGEDIRECTORY", "VARCHAR(255)"),
			   							new DataField("DATA", "binary")};
	
	private LinkedBlockingQueue<Message> msgQueue = new LinkedBlockingQueue<Message>();
	private String propertyFileName = null;
	private boolean storeInDatabase;
	protected Properties configFile = new Properties();
	private long binaryTimestamp = -1;
	private long binaryLength = -1;
	protected CRC32 calculatedCRC = new CRC32();
	protected String remoteBinaryName = null;
	protected String localBinaryName = null;
	protected long downloadedSize = -1;
	protected long lastChunkNumber = -1;
	private static Set<String> coreStationsList = new HashSet<String>();
    private static int threadCounter = 0;

	private Server web;
	private String coreStationName = null;
	
	private CalculateChecksum calcChecksumThread;
	protected BigBinarySender bigBinarySender;

	private boolean dispose = false;
	
	
	@Override
	public boolean initialize ( BackLogWrapper backlogwrapper, String coreStationName, String deploymentName) {
		activeBackLogWrapper = backlogwrapper;
		this.coreStationName = coreStationName;

		AddressBean addressBean = getActiveAddressBean();
		
		calcChecksumThread = new CalculateChecksum(this);
		bigBinarySender = new BigBinarySender(this);
		
		try {
			rootBinaryDir = addressBean.getPredicateValueWithException(STORAGE_DIRECTORY);
		} catch (Exception e) {
			logger.error(e.getMessage());
			return false;
		}
		
		if(!rootBinaryDir.endsWith("/"))
			rootBinaryDir += "/";
		
		File f = new File(rootBinaryDir);
		if (!f.isDirectory()) {
			logger.error(rootBinaryDir + " is not a directory");
			return false;
		}
		
		if (!f.canWrite()) {
			logger.error(rootBinaryDir + " is not writable");
			return false;
		}
		
		// check if this plugin has already be used for this deployment
		synchronized (coreStationsList) {
			if (!coreStationsList.add(coreStationName)) {
				logger.error("This plugin can only be used once per CoreStation!");
				return false;
			}
		}

		try {
			if (logger.isDebugEnabled() && StorageManager.getDatabaseForConnection(StorageManager.getInstance().getConnection()) == StorageManager.DATABASE.H2) {
				try {
					String [] args = {"-webPort", "8082", "-webAllowOthers", "false"};
					web = Server.createWebServer(args);
					web.start();
				} catch (SQLException e) {
					logger.warn(e.getMessage(), e);
				}
			}
		} catch (SQLException e) {
			logger.warn(e.getMessage(), e);
		}
		
		propertyFileName = rootBinaryDir + PROPERTY_FILE_NAME;
		
		logger.debug("property file name: " + propertyFileName);
		logger.debug("local binary directory: " + rootBinaryDir);

        setName(getPluginName() + "-Thread" + (++threadCounter));
        
        registerListener();
		
		return true;
	}


	@Override
	public String getPluginName() {
		return "BigBinaryPlugin";
	}
	

	@Override
	public byte getMessageType() {
		return gsn.wrappers.backlog.BackLogMessage.BINARY_MESSAGE_TYPE;
	}

	@Override
	public DataField[] getOutputFormat() {
		return dataField;
	}
	
	@Override
	public void dispose() {
		logger.debug("dispose thread");
		calcChecksumThread.dispose();
		try {
			calcChecksumThread.join();
		} catch (InterruptedException e) {
			logger.error(e.getMessage(), e);
		}
		bigBinarySender.dispose();
		try {
			bigBinarySender.join();
		} catch (InterruptedException e) {
			logger.error(e.getMessage(), e);
		}
		if (web != null) {
			web.shutdown();
			web = null;
		}
		dispose = true;
		msgQueue.add(new Message());
		
		synchronized (coreStationsList) {
			coreStationsList.remove(coreStationName);
		}
		
        threadCounter--;
        
        super.dispose();
	}
	
	@Override
	public void run() {
        logger.info("thread started");
        
		bigBinarySender.start();
		calcChecksumThread.start();
		long lastRecvPacketType = -1;
		
    	// start connection check timer
		if (connectionTestTimer == null) {
			connectionTestTimer = new Timer("ConnectionCheck");
			connectionTestTimer.schedule( new ConnectionCheckTimer(this), 15000 );
		}

    	Message msg;
    	while (!dispose) {
			try {
				msg = msgQueue.take();
			} catch (InterruptedException e) {
				logger.error(e.getMessage());
				break;
			}
			if (dispose)
				break;
			
			bigBinarySender.stopSending();
        	
    		long filelen = -1;
    		// get packet type
    		byte pktType = msg.getPacket()[0];
    		
			if (pktType == ACK_PACKET) {
				byte ackType = msg.getPacket()[1];
				logger.debug("acknowledge packet type >" + ackType + "< received");
				
				lastRecvPacketType = ACK_PACKET;
			}
			else if (pktType == INIT_PACKET) {
				if (lastRecvPacketType == INIT_PACKET)
					logger.debug("init packet already received");
				else {
					logger.debug("init packet received");
    				StringBuffer name = new StringBuffer();
    				
    				// get file info
    				binaryTimestamp = arr2long(msg.getPacket(), 1);
    				binaryLength = arr2uint(msg.getPacket(), 9);
    				byte storage = msg.getPacket()[13];
    				int i = 14;
    				for (; i < msg.getPacket().length; i++) {
    					if (msg.getPacket()[i] == 0) break;
    					name.append((char) msg.getPacket()[i]);
    				}
    				remoteBinaryName = name.toString();
    				name = new StringBuffer();
    				i++;
    				for (; i < msg.getPacket().length; i++) {
    					if (msg.getPacket()[i] == 0) break;
    					name.append((char) msg.getPacket()[i]);
    				}
    				String datetimefm = name.toString();
    				try {
    					folderdatetimefm = new SimpleDateFormat(datetimefm);
    				} catch (IllegalArgumentException e) {
    					logger.error("the received init packet does contain a mallformed date time format >" + datetimefm + "<! Please check your backlog configuration on the deployment -> drop this binary");
    					bigBinarySender.requestNewBinary();
    					continue;
    				}
    				
    				if (storage == 1)
    					storeInDatabase = true;
    				else
    					storeInDatabase = false;
    	
    				logger.debug("new incoming binary:");
    				logger.debug("   remote binary name: " + remoteBinaryName);
    				logger.debug("   timestamp of the binary: " + binaryTimestamp);
    				logger.debug("   binary length: " + binaryLength);
    				if (storeInDatabase)
    					logger.debug("   store in database");
    				else
    					logger.debug("   store on disk");
    				logger.debug("   folder date time format: " + datetimefm);
    	
    			    File f = new File(remoteBinaryName);
    			    
    			    if (storeInDatabase) {
    			    	localBinaryName = rootBinaryDir + TEMP_BINARY_NAME;
    			    }
    			    else {
    			    	String subpath = f.getParent();
    			    	if (subpath == null	)
    			    		subpath = "";
    			    	logger.debug("subpath: " + subpath);
    					
    					if(!subpath.endsWith("/"))
    						subpath += "/";
    			    	
	    			    String datedir = rootBinaryDir + subpath + folderdatetimefm.format(new java.util.Date(binaryTimestamp)) + "/";
	    			    String filename = f.getName();
	    			    f = new File(datedir);
	    			    if (!f.exists()) {
	    			    	if (!f.mkdirs()) {
	    			    		logger.error("could not mkdir >" + datedir + "<  -> drop remote binary " + remoteBinaryName);
	    			    		bigBinarySender.requestNewBinary();
	    			    		continue;
	    			    	}
	    			    }
	    			    localBinaryName = datedir + filename;
    			    }
    	
    				filelen = 0;
    				
    				// delete the file if it already exists
    				f = new File(localBinaryName);
    			    if (f.exists()) {
    			    	logger.debug("overwrite already existing binary >" + localBinaryName + "<");
    			    	f.delete();
    			    }
    			    
					lastChunkNumber = -1;
    	
    			    // write the new binary info to the property file
    				configFile.setProperty(PROPERTY_REMOTE_BINARY, remoteBinaryName);
    				configFile.setProperty(PROPERTY_DOWNLOADED_SIZE, Long.toString(0));
    				configFile.setProperty(PROPERTY_BINARY_TIMESTAMP, Long.toString(binaryTimestamp));
    				configFile.setProperty(PROPERTY_BINARY_SIZE, Long.toString(binaryLength));
    				configFile.setProperty(PROPERTY_CHUNK_NUMBER, Long.toString(lastChunkNumber));
    				configFile.setProperty(PROPERTY_STORAGE_TYPE, Boolean.toString(storeInDatabase));
    				configFile.setProperty(PROPERTY_TIME_DATE_FORMAT, datetimefm);
    				
    				try {
						configFile.store(new FileOutputStream(propertyFileName), null);
					} catch (Exception e) {
						logger.error(e.getMessage(), e);
						dispose();
					}
    				
    				calculatedCRC.reset();
				}
				
	    		bigBinarySender.sendInitAck();
			}
			else if (pktType == CHUNK_PACKET) {
				// get number of this chunk
				long chunknum = arr2uint(msg.getPacket(), 1);
				logger.debug("Chunk for " + remoteBinaryName + " with number " + chunknum + " received");
				
				if (chunknum == lastChunkNumber)
					logger.info("chunk already received");
				else if (lastChunkNumber+1 == chunknum) {
					try {
						// store the binary chunk to disk
						File file = new File(localBinaryName);
						FileOutputStream fos;
						try {
							fos = new FileOutputStream(file, true);
						} catch (FileNotFoundException e) {
							logger.warn(e.getMessage());
							bigBinarySender.requestRetransmissionOfBinary(remoteBinaryName);
							continue;
						}
						byte [] chunk = java.util.Arrays.copyOfRange(msg.getPacket(), 5, msg.getPacket().length);
						calculatedCRC.update(chunk);
						logger.debug("updated crc: " + calculatedCRC.getValue());
						fos.write(chunk);
						fos.close();
						filelen = file.length();
						// write the actual binary length and chunk number to the property file
						// to be able to recover in case of a GSN failure
						configFile.setProperty(PROPERTY_DOWNLOADED_SIZE, Long.toString(filelen));
						configFile.setProperty(PROPERTY_CHUNK_NUMBER, Long.toString(chunknum));
						configFile.store(new FileOutputStream(propertyFileName), null);
	    				
	    				logger.debug("actual length of concatenated binary is " + filelen + " bytes");
					}
					catch (IOException e) {
						logger.error(e.getMessage(), e);
						dispose();
					}
				}
				else {
					// we should never reach this point...
					logger.error("received chunk number (received nr=" + chunknum + "/last nr=" + lastChunkNumber + ") out of order -> request binary retransmission");
					bigBinarySender.requestRetransmissionOfBinary(remoteBinaryName);
					continue;
				}

				lastChunkNumber = chunknum;

	    		bigBinarySender.sendChunkAck(lastChunkNumber);
			}
			else if (pktType == CRC_PACKET) {
				long crc = arr2uint(msg.getPacket(), 1);
				
				if (lastRecvPacketType == CRC_PACKET) {
					logger.debug("crc packet already received -> drop it");
		    		bigBinarySender.sendCRCAck();
				}
				else {
					logger.debug("crc packet with crc32 >" + crc + "< received");
					
    				// do we really have the whole binary?
    				if ((new File(localBinaryName)).length() == binaryLength) {
    					// check crc
    					if (calculatedCRC.getValue() == crc) {
    						logger.debug("crc is correct");
    						if (storeInDatabase) {
    							byte[] tmp = null;
    							File file = new File(localBinaryName);
    							FileInputStream fin;
    							
    							try {
    								fin = new FileInputStream(file);
    								// find index of first null byte
    								tmp = new byte[(int)file.length()];
    								fin.read(tmp);
    								fin.close();
    							} catch (FileNotFoundException e) {
    								logger.warn(e.getMessage());
    								bigBinarySender.requestRetransmissionOfBinary(remoteBinaryName);
    								continue;
    							} catch (IOException e) {
    								logger.error(e.getMessage(), e);
    								dispose();
    							}

    							String relDir = remoteBinaryName;
    							Serializable[] data = {binaryTimestamp, getDeviceID(), relDir, null, tmp};
    							if(!dataProcessed(System.currentTimeMillis(), data)) {
    								logger.warn("The binary data  (timestamp=" + binaryTimestamp + "/length=" + binaryLength + "/name=" + remoteBinaryName + ") could not be stored in the database.");
    							}
    							else
    								logger.debug("binary data (timestamp=" + binaryTimestamp + "/length=" + binaryLength + "/name=" + remoteBinaryName + ") successfully stored in database");
    							
    							file.delete();
    						}
    						else {
    							String relLocalName = localBinaryName.replaceAll(rootBinaryDir, "");
    							Serializable[] data = {binaryTimestamp, getDeviceID(), relLocalName, rootBinaryDir, null};
    							if(!dataProcessed(System.currentTimeMillis(), data)) {
    								logger.warn("The binary data with >" + binaryTimestamp + "< could not be stored in the database.");
    							}
    							if (!(new File(localBinaryName)).setLastModified(binaryTimestamp))
    								logger.warn("could not set modification time for " + localBinaryName);
    							logger.debug("binary data (timestamp=" + binaryTimestamp + "/length=" + binaryLength + "/name=" + remoteBinaryName + ") successfully stored on disk");
    						}
    					
    						File stat = new File(propertyFileName);
    						stat.delete();
    						
    						localBinaryName = null;

    			    		bigBinarySender.sendCRCAck();
    					}
    					else {
    						logger.warn("crc does not match (received=" + crc + "/calculated=" + calculatedCRC.getValue() + ") -> request binary retransmission");
    						bigBinarySender.requestRetransmissionOfBinary(remoteBinaryName);
    					}
    				}
    				else {
    					// we should never reach this point as well...
    					logger.error("binary length does not match (actual length=" + (new File(localBinaryName)).length() + "/should be=" + binaryLength + ") -> request binary retransmission (should never happen!)");
						bigBinarySender.requestRetransmissionOfBinary(remoteBinaryName);
    				}
    			}
			}
			lastRecvPacketType = pktType;
    	}
        
        logger.info("thread stopped");
    }
	

	@Override
	public boolean messageReceived(int deviceID, long timestamp, byte[] packet) {
		try {
			logger.debug("message received with timestamp " + timestamp);
			msgQueue.add(new Message(timestamp, packet));
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			return false;
		}
		return true;
	}


	@Override
	public void remoteConnEstablished() {
		logger.debug("Connection established");
		if (connectionTestTimer != null)
			connectionTestTimer.cancel();
		else
			connectionTestTimer = new Timer("ConnectionCheck");
		
		File sf = new File(propertyFileName);
		if (sf.exists()) {
			try {
				configFile.load(new FileInputStream(propertyFileName));
			} catch (Exception e) {
				logger.error(e.getMessage(), e);
				dispose();
				return;
			}
			
			try {
				remoteBinaryName = configFile.getProperty(PROPERTY_REMOTE_BINARY);
				if (remoteBinaryName == null)
					throw new Exception("property >" + PROPERTY_REMOTE_BINARY + "< not found in " + propertyFileName);
				String prop = configFile.getProperty(PROPERTY_DOWNLOADED_SIZE);
				if (prop == null)
					throw new Exception("property >" + PROPERTY_DOWNLOADED_SIZE + "< not found in " + propertyFileName);
				downloadedSize = Long.valueOf(prop).longValue();
				prop = configFile.getProperty(PROPERTY_BINARY_TIMESTAMP);
				if (prop == null)
					throw new Exception("property >" + PROPERTY_BINARY_TIMESTAMP + "< not found in " + propertyFileName);
				binaryTimestamp = Long.valueOf(prop).longValue();
				prop = configFile.getProperty(PROPERTY_BINARY_SIZE);
				if (prop == null)
					throw new Exception("property >" + PROPERTY_BINARY_SIZE + "< not found in " + propertyFileName);
				binaryLength = Long.valueOf(prop).longValue();
				prop = configFile.getProperty(PROPERTY_STORAGE_TYPE);
				if (prop == null)
					throw new Exception("property >" + PROPERTY_STORAGE_TYPE + "< not found in " + propertyFileName);
				storeInDatabase = Boolean.valueOf(prop).booleanValue();
				prop = configFile.getProperty(PROPERTY_TIME_DATE_FORMAT);
				if (prop == null)
					throw new Exception("property >" + PROPERTY_TIME_DATE_FORMAT + "< not found in " + propertyFileName);
				
				folderdatetimefm = new SimpleDateFormat(prop);
			    if (storeInDatabase) {
			    	localBinaryName = rootBinaryDir + TEMP_BINARY_NAME;
			    }
			    else {
				    File f = new File(remoteBinaryName);
			    	String subpath = f.getParent();
			    	if (subpath == null	)
			    		subpath = "";
			    	else if(!subpath.endsWith("/"))
						subpath += "/";
	
			    	logger.debug("subpath: " + subpath);
			    	
				    String datedir = rootBinaryDir + subpath + folderdatetimefm.format(new java.util.Date(binaryTimestamp)) + "/";
				    String filename = f.getName();
				    localBinaryName = datedir + filename;
			    }
			    
			    if ((new File(localBinaryName)).exists())
			    	calcChecksumThread.newChecksum(localBinaryName);
			    else {
			    	logger.error("binary >" + localBinaryName + "< does not exist -> request retransmission");
			    	bigBinarySender.requestRetransmissionOfBinary(remoteBinaryName);
			    }
			} catch (Exception e) {
		    	logger.error(e.getMessage() + " -> request new binary");
				bigBinarySender.requestNewBinary();
			}
		} else {
			bigBinarySender.requestNewBinary();
		}
	}


	@Override
	public void remoteConnLost() {
		logger.debug("Connection lost");

		msgQueue.clear();
		bigBinarySender.stopSending();
	}
}





/**
 * A message to be put into the message queue.
 * 
 * @author Tonio Gsell
 */
class Message {
	protected long timestamp;
	protected byte[] packet;
	
	Message() {	}
	
	Message(long t, byte[] pkt) {
		timestamp = t;
		packet = pkt.clone();
	}
	
	public long getTimestamp() {
		return this.timestamp;
	}
	
	public byte[] getPacket() {
		return this.packet;
	}
}


/**
 * Offers the functionality to calculate the checksum of a partly downloaded
 * binary. After the checksum has been calculated the deployment is asked to
 * resume the download.
 * 
 * @author Tonio Gsell
 */
class CalculateChecksum extends Thread {
	private boolean dispose = false;
    private CheckedInputStream cis = null;
    private BinaryPlugin parent = null;
	private LinkedBlockingQueue<String> fileQueue = new LinkedBlockingQueue<String>();
	
	public CalculateChecksum(BinaryPlugin plug) {
		this.setName("CalculateChecksumThread");
		parent = plug;
	}
	
	public void run() {
		String file;
        
        parent.logger.info("thread started");
		
		while (!this.dispose) {
			try {
				file = fileQueue.take();
			} catch (InterruptedException e) {
				parent.logger.debug(e.getMessage());
				break;
			}
			if (this.dispose)
				break;
			
			// if the property file exists we have already downloaded a part of a binary -> resume
			// calculate crc from already downloaded binary
			parent.logger.debug("calculating cheksum for already downloaded part of binary >" + parent.localBinaryName + "<");
	        try {
	            // Computer CRC32 checksum
	            cis = new CheckedInputStream(
	                    new FileInputStream(file), new CRC32());

	            byte[] buf = new byte[4096];
		        while(cis.read(buf) >= 0 && !this.dispose) {
		        	yield();
		        }
		        if (this.dispose)
		        	break;
	        } catch (Exception e) {
				// no good... -> ask for retransmission of the binary
				parent.logger.error(e.getMessage(), e);
				parent.bigBinarySender.requestRetransmissionOfBinary(parent.remoteBinaryName);
				continue;
			}
	        
	        parent.calculatedCRC = (CRC32) cis.getChecksum();
			
	        parent.logger.debug("recalculated crc (" + parent.calculatedCRC.getValue() + ") from " + parent.localBinaryName);
			
			parent.bigBinarySender.resumeBinary(parent.remoteBinaryName, parent.downloadedSize, Long.valueOf(parent.configFile.getProperty(BinaryPlugin.PROPERTY_CHUNK_NUMBER)).longValue()+1, parent.calculatedCRC.getValue());
		}
        
        parent.logger.info("thread stopped");
	}
	
	



	/**
	 * Calculate the checksum of the partly downloaded binary.
	 * 
	 * @param binary the partly downloaded binary which should be resumed
	 */
	public void newChecksum(String binary) {
		fileQueue.add(binary);
	}
	
	public void dispose() {
		this.dispose = true;
		fileQueue.add("");
	}
}

class BigBinarySender extends Thread
{
    private BinaryPlugin parent = null;
	private boolean stopped = false;
	private boolean triggered = false;
	private Object event = new Object();
	private byte [] packet = null;
	
	BigBinarySender(BinaryPlugin plug) {
		this.setName("BigBinarySenderThread");
		parent = plug;
	}
	
	public void run() {
		parent.logger.info("thread started");
		while (!stopped) {
			try {
				synchronized (event) {
					if (!triggered)
						event.wait();
					else {
						event.wait(BinaryPlugin.RESEND_INTERVAL_SEC*1000);
						if (triggered)
							parent.logger.info("resend message");
						else
							continue;
					}
				}
			} catch (InterruptedException e) {
				break;
			}
			
			if(triggered) {
				try {
					if(!parent.sendRemote(System.currentTimeMillis(), packet))
						stopSending();
				} catch (Exception e) {
					parent.logger.error(e.getMessage(), e);
				}
			}
		}
		parent.logger.info("thread stopped");
	}
	
	
	private void trigger() {
		synchronized (event) {
			triggered = true;
			event.notify();
		}
	}
	
	public void stopSending() {
		parent.logger.debug("stop sending");
		synchronized (event) {
			triggered = false;
			packet = null;
			event.notify();
		}
	}
	
	public void dispose() {
		stopped = true;
		super.interrupt();
	}
	
	
	public void sendChunkAck(long ackNr) {
		if (triggered)
			parent.logger.error("already sending a message");
		else {
			parent.logger.debug("acknowledge for chunk number >" + ackNr + "< sent");
    		ByteArrayOutputStream baos = new ByteArrayOutputStream(5);
    		baos.write(BinaryPlugin.ACK_PACKET);
    		baos.write(BinaryPlugin.CHUNK_PACKET);
			try {
				baos.write(BinaryPlugin.uint2arr(ackNr));
				parent.sendRemote(System.currentTimeMillis(), baos.toByteArray());
			} catch (Exception e) {
				parent.logger.error(e.getMessage(), e);
			}
		}
	}
	
	
	public void sendInitAck() {
		if (triggered)
			parent.logger.error("already sending a message");
		else {
			parent.logger.debug("init acknowledge sent");
			byte [] packet = new byte[2];
			packet[0] = BinaryPlugin.ACK_PACKET;
			packet[1] = BinaryPlugin.INIT_PACKET;
			try {
				parent.sendRemote(System.currentTimeMillis(), packet);
			} catch (Exception e) {
				parent.logger.error(e.getMessage(), e);
			}
		}
	}
	
	
	public void sendCRCAck() {
		if (triggered)
			parent.logger.error("already sending a message");
		else {
			parent.logger.debug("crc acknowledge sent");
			byte [] packet = new byte[2];
			packet[0] = BinaryPlugin.ACK_PACKET;
			packet[1] = BinaryPlugin.CRC_PACKET;
			try {
				parent.sendRemote(System.currentTimeMillis(), packet);
			} catch (Exception e) {
				parent.logger.error(e.getMessage(), e);
			}
		}
	}



	/**
	 * Get a new binary from the deployment.
	 */
	public void requestNewBinary() {
		if (triggered)
			parent.logger.error("already sending a message");
		else {
			packet = new byte [1];
			packet[0] = BinaryPlugin.INIT_PACKET;
			trigger();
		}
	}



	/**
	 * Retransmit the specified binary from the deployment.
	 */
	public void requestRetransmissionOfBinary(String remoteLocation) {	
		parent.calculatedCRC.reset();
		// delete the file if it already exists
		File f = new File(parent.localBinaryName);
	    if (f.exists()) {
	    	parent.logger.debug("overwrite already existing binary >" + parent.localBinaryName + "<");
	    	f.delete();
	    }
		requestSpecificBinary(remoteLocation, 0, 0, 0);
	}


	/**
	 * Resume the specified binary from the deployment
	 * 
	 * @param remoteLocation the relative location of the remote binary
	 * @param sizeAlreadyDownloaded the size of the binary which has already been downloaded
	 * @param chunkNr the number of the last chunk which has already been downloaded
	 * 
	 * @throws Exception if an I/O error occurs.
	 */
	public void resumeBinary(String remoteLocation, long sizeAlreadyDownloaded, long chunkNr, long crc) {
		requestSpecificBinary(remoteLocation, sizeAlreadyDownloaded, chunkNr, crc);
	}
	
	
	private void requestSpecificBinary(String remoteLocation, long sizeAlreadyDownloaded, long chunkNr, long crc) {
		if (triggered)
			parent.logger.error("already sending a message");
		else {
			// ask the deployment to resend the specified binary from the specified position
			ByteArrayOutputStream baos = new ByteArrayOutputStream(remoteLocation.length() + 5);
			baos.write(BinaryPlugin.RESEND_PACKET);
			try {
				baos.write(BinaryPlugin.uint2arr(sizeAlreadyDownloaded));
				baos.write(BinaryPlugin.uint2arr(chunkNr));
				baos.write(BinaryPlugin.uint2arr(crc));
				baos.write(remoteLocation.getBytes());
			} catch (IOException e) {
				parent.logger.error(e.getMessage(), e);
			}
			packet = baos.toByteArray();
			
			parent.lastChunkNumber = chunkNr-1;
			
			trigger();
		}
	}
}



/**
 * Pretends a connection establishment on fire.
 */
class ConnectionCheckTimer extends TimerTask {
	private BinaryPlugin parent;
	
	public ConnectionCheckTimer(BinaryPlugin parent) {
		this.parent = parent;
	}
	
	public void run() {
		parent.logger.debug("connection check timer fired");
		if (parent.isConnected())
			parent.remoteConnEstablished();
	}
}
