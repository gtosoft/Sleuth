package com.gtosoft.sleuth;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.TextView;

import com.gtosoft.libvoyager.android.ActivityHelper;
import com.gtosoft.libvoyager.db.DashDB;
import com.gtosoft.libvoyager.session.HybridSession;
import com.gtosoft.libvoyager.session.MonitorSession;
import com.gtosoft.libvoyager.util.EasyTime;
import com.gtosoft.libvoyager.util.EventCallback;
import com.gtosoft.libvoyager.util.GeneralStats;

public class SleuthActivity extends Activity {
	
	String mBTPeerMAC 			= "";
	ActivityHelper maHelper 	= null;
	HybridSession hs 			= null;
	DashDB ddb 					= null;
	boolean mThreadsOn 			= true;
	GeneralStats mgStats    	= new GeneralStats();
	Handler muiHandler			= new Handler();
//	VoyagerStatusBox mStatusBox = null;

	
	/**
	 * Shuts down all child things and then our own stuff in preparation for total application shutdown. 
	 */
	private void shutdown () {
		mThreadsOn = false;
		if (ddb != null) 		ddb.shutdown();
		if (hs  != null) 		hs.shutdown();
		
	}

	@Override
	protected void onPause() {
		super.onPause();
		
		if (hs != null) hs.shutdown();
		hs = null;
	}

	
	
	@Override
	protected void onDestroy() {
		super.onDestroy();
		
		// run our shutdown cruft. 
		shutdown();
	}
	
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        
        setContentView(R.layout.main);

//        mStatusBox = new VoyagerStatusBox(SleuthActivity.this, R.id.iv1, R.id.iv2, R.id.iv3, R.id.tvstate);   
        
        maHelper = new ActivityHelper(this);
        
        maHelper.registerChosenDeviceCallback(chosenCallback);
        maHelper.startDiscovering();
//		mStatusBox.setStatusLevel("Searching for device...", 1);

        
    }

    @Override
    protected void onResume() {
    	super.onResume();

    	// Either resume the old device or kick off a discovery. 
		if (mBTPeerMAC.length() > 0)
			setupSession(mBTPeerMAC);
		else 
			maHelper.startDiscovering();
    }
    
    
    /** 
     * libVoyager can do the BT discovery and device choosing for you. When it finds/chooses a device  it runs the device chosen callback.
     * This method defines what to do when a new device is found.  
     */
    private EventCallback chosenCallback = new EventCallback () {

    	@Override
    	public void onELMDeviceChosen(String MAC) {
    		mBTPeerMAC = MAC;
    		setupSession(MAC);
    	}
    	
    };// end of eventcallback definition. 
    
    /**
     * This method gets called by the broadcast receiver, for bluetooth devices which are "OBD" devices.  
     * This takes care of any necessary actions to open a connection to the specified device. 
     * @param deviceMACAddress
     * @return - true on success, false otherwise. 
     */
    private synchronized boolean setupSession(String deviceMACAddress) {
  	  
  	  // Make sure we aren't threading out into more than one device. we can't presently handle multiple OBD devices at once. 
  	  if (hs != null) {
  		  msg ("Multiple OBD devices detected. throwing out " + deviceMACAddress);
  		  return false;
  	  }
  	  
  	  // instantiate dashDB if necessary.
  	  if (ddb == null) {
  		  msg  ("Spinning up DashDB...");
  		  ddb = new DashDB(this);
  		  msg  ("DashDB Ready.");
  	  }

//  	  mStatusBox.setStatusLevel("Connecting to " + deviceMACAddress, 2);
  	  hs = new HybridSession (BluetoothAdapter.getDefaultAdapter(), deviceMACAddress, ddb, ecbOOBMessageHandler);
  	  // register a method to be called when new data arrives. 
  	  hs.registerDPArrivedCallback(ecbDPNArrivedHandler);

      startDataCollectorLoop();

      mBTPeerMAC = deviceMACAddress;
        
  	  return true;
    }

    Thread mtDataCollector = null;
    private boolean startDataCollectorLoop () {
    	if (mtDataCollector != null) {
    		return false;
    	}
    	
    	// Define the thread. 
    	mtDataCollector = new Thread() {
    		public void run () {
    			int loops = 0;
    			boolean hardwareDetected = false;
    			while (mThreadsOn == true) {
    				loops++;
    				mgStats.setStat("loops", "" + loops);
    				// moved this to the top of the loop so that we can run a "continue" and not cause a tight loop. 
    				EasyTime.safeSleep(1000);
    				
    				// when hs goes from null to defined, that means a device was discovered. 
    				if (hs != null) {

    					// run session detection if necessary. 
    					if (hardwareDetected == false && hs.getEBT().isConnected() == true) { 
        					dumpStatsToScreen();
//        					mStatusBox.setStatusLevel("Checking Hardware Type", 4);
    						hardwareDetected = detectSessionAndStartSniffing();
        					dumpStatsToScreen();
    						if (hardwareDetected != true) {
    							// hardware detection failed. It will be attempted again soon. 
    							msg ("Failed attempt to detect hardware at loop " + loops);
    							continue; 
    						} else {
    							// hardware detection was successful.
//    							mStatusBox.setStatusLevel("Detected!", 5);

    						}
    					}

    					// Has the session type been detected and switched to moni by the detectSessionAndStartSniffing process...  
    					if (hs.getCurrentSessionType() == HybridSession.SESSION_TYPE_MONITOR) {
        					MonitorSession m = hs.getMonitorSession();
        					// Show GeneralStats leading up to a full connection state. After that, display DPNs.
        					if (m != null && m.getCurrentState() >= MonitorSession.STATE_SNIFFING && hs.getPIDDecoder().getNetworkID().length()>0) {
        						dumpAllDPNsToScreen();
        					} else {
            					dumpStatsToScreen();
        					}
    					}
    				} else {
    					// hybrid session not defined or session still being detected. do nothing / Sleep longer?
    				}
    				
    			}// end of main while loop. 
    			msg ("Data collector loop finished.");
    		}// end of run().

    	};// end of thread definition. 
    	
    	// kick off the thread.
    	mtDataCollector.start();
    	
    	return true;
    }
    
    

    /**
     * write allDPNsAsString to the screen. 
     */
	private void dumpAllDPNsToScreen() {
		setScreenText(hs.getPIDDecoder().getAllDataPointsAsString());
	}

	// TODO: Make sure hybridSEssion is using the new obd packet parser class to decode responses.
	/**
	 * Do whatever necessary to detect hardware type and get it into sniff mode. 
	 * We only try to detect the hardware once, if it fails, we return false. 
	 * So if you get a false reading from this method, make another call. repeat as necessary.  
	 */
    private boolean detectSessionAndStartSniffing() {
		msg ("Running session detection...");
		boolean result = hs.runSessionDetection();
		msg ("Session detection result was " + result);
		if (result == true) {
			// session detection was successful, move to next phase. 
			msg ("Session detection succeeded, Switching to moni session... If possible. ");
			if (hs.isHardwareSniffable()) {
				msg ("Hardware IS sniffable, so switching to moni");
				hs.setActiveSession(HybridSession.SESSION_TYPE_MONITOR);
				msg ("After switch to moni.");
			} else {
				msg ("Hardware does not support sniff.");
				return false;
			}
		} else {
			// return value of the session deteciton was false so return false. 
			return false;
		}
		
		return true;
    }
    
	private void msg (String m) {
    	Log.d("Sleuth",m);
    }

    // Defines the logic to take place when an out of band message is generated by the hybrid session layer. 
	EventCallback ecbOOBMessageHandler = new EventCallback () {
		@Override
		public void onOOBDataArrived(String dataName, String dataValue) {
			
			if (mThreadsOn != true) {
				msg ("Ignoring OOB message out of scope. Threads are off. " + dataName + "=" + dataValue);
				return;
			}
			
			msg ("OOB Data: " + dataName + "=" + dataValue);
			
			// state change?
			if (dataName.equals(HybridSession.OOBMessageTypes.IO_STATE_CHANGE)) {
				int newState = 0;
				try {
					newState = Integer.valueOf(dataValue);
					msg ("IO State changed to " + newState);
//					ioStateChanged(newState);
				} catch (NumberFormatException e) {
					msg ("ERROR: Could not interpret new state as string: " + dataValue);
				}
				
				if (newState > 0) {
//					mStatusBox.setStatusLevel("Bluetooth Connected", 3);
				} else {
//					mStatusBox.setStatusLevel("Bluetooth Disconnected", 2);
				}
				
			}// end of "if this was a io state change". 
			
			// session state change? 
			if (dataName.equals(HybridSession.OOBMessageTypes.SESSION_STATE_CHANGE) && hs.getCurrentSessionType() == HybridSession.SESSION_TYPE_MONITOR) {
				int newState = 0;
				
				// convert from string to integer. 
				try {
					newState = Integer.valueOf(dataValue);
				} catch (NumberFormatException e) {
					return;
				}

			}// end of if. 
				
			}// end of session state change handler. 
		};// end of override.

		EventCallback ecbDPNArrivedHandler = new EventCallback () {
			@Override
			public void onDPArrived(String DPN, String sDecodedData, int iDecodedData) {
//				msg ("DPN Arrived: " + DPN + "=" + sDecodedData);
			}// end of onDPArrived. 
		};// end of eventcallback def. 

		
		public GeneralStats getStats () {
			if (hs != null) mgStats.merge("hs", hs.getStats());
			
			return mgStats;
		}
		
		private void dumpStatsToScreen  () {
			final String _stats = getStats().getAllStats();
			setScreenText(_stats);
		}
		
		private void setScreenText (final String newText) {
			final TextView tv = (TextView) findViewById(R.id.tvMain);
			
			muiHandler.post(new Runnable () {
				public void run () {
					tv.setText(newText);
				}
			});
		}
		
		
		
}// end of class. 