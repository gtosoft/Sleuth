package com.gtosoft.sleuth;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

import com.gtosoft.libvoyager.android.ActivityHelper;
import com.gtosoft.libvoyager.db.DashDB;
import com.gtosoft.libvoyager.session.HybridSession;
import com.gtosoft.libvoyager.session.MonitorSession;
import com.gtosoft.libvoyager.util.EasyTime;
import com.gtosoft.libvoyager.util.EventCallback;
import com.gtosoft.libvoyager.util.GeneralStats;
import com.gtosoft.libvoyager.util.NetworkStats;
import com.gtosoft.libvoyager.util.OOBMessageTypes;

public class SleuthActivity extends Activity {

	String mBTPeerMAC = "";
	ActivityHelper maHelper = null;
	HybridSession hs = null;
	DashDB ddb = null;
	boolean mThreadsOn = true;
	GeneralStats mgStats = new GeneralStats();
	Handler muiHandler = new Handler();
	// VoyagerStatusBox mStatusBox = null;

	Button btnLock;
	Button btnUnlock;
	Button btnWake;

	TextView tvMain;

	/**
	 * Shuts down all child things and then our own stuff in preparation for
	 * total application shutdown.
	 */
	private void shutdown() {
		mThreadsOn = false;
		if (ddb != null)
			ddb.shutdown();
		if (hs != null)
			hs.shutdown();

	}

	@Override
	protected void onPause() {
		super.onPause();

		if (hs != null)
			hs.shutdown();
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
		btnLock = (Button) findViewById(R.id.btnLock);
		btnUnlock = (Button) findViewById(R.id.btnUnLock);
		tvMain = (TextView) findViewById(R.id.tvMain);
		btnWake = (Button) findViewById(R.id.btnWake);
		
		setButtonEventHandlers();

		// mStatusBox = new VoyagerStatusBox(SleuthActivity.this, R.id.iv1,
		// R.id.iv2, R.id.iv3, R.id.tvstate);

		maHelper = new ActivityHelper(this);
		maHelper.registerChosenDeviceCallback(chosenCallback);
		msg("Finding device...");
		maHelper.startDiscovering();
		// mStatusBox.setStatusLevel("Searching for device...", 1);

	}

	@Override
	protected void onResume() {
		super.onResume();

		// Either resume the old device or kick off a discovery.
		if (mBTPeerMAC.length() > 0)
			setupSession(mBTPeerMAC);
		else {
			maHelper.startDiscovering();
			msg ("Searching for device...");
		}
	}

	/**
	 * libVoyager can do the BT discovery and device choosing for you. When it
	 * finds/chooses a device it runs the device chosen callback. This method
	 * defines what to do when a new device is found.
	 */
	private EventCallback chosenCallback = new EventCallback() {

		@Override
		public void onELMDeviceChosen(String MAC) {
			msg("Device chosen! MAC=" + MAC);
			mBTPeerMAC = MAC;
			setupSession(MAC);
		}

	};// end of eventcallback definition.

	/**
	 * This method gets called by the broadcast receiver, for bluetooth devices
	 * which are "OBD" devices. This takes care of any necessary actions to open
	 * a connection to the specified device.
	 * 
	 * @param deviceMACAddress
	 * @return - true on success, false otherwise.
	 */
	private synchronized boolean setupSession(String deviceMACAddress) {

		// Make sure we aren't threading out into more than one device. we can't
		// presently handle multiple OBD devices at once.
		if (hs != null) {
			msg("Multiple OBD devices detected. throwing out "
					+ deviceMACAddress);
			return false;
		}

		// instantiate dashDB if necessary.
		if (ddb == null) {
			// msg ("Spinning up DashDB...");
			ddb = new DashDB(this);
			// msg ("DashDB Ready.");
		}

		// mStatusBox.setStatusLevel("Connecting to " + deviceMACAddress, 2);
		hs = new HybridSession(BluetoothAdapter.getDefaultAdapter(),deviceMACAddress, ddb, ecbOOBMessageHandler);
		// register a method to be called when new data arrives.
		hs.registerDPArrivedCallback(ecbDPNArrivedHandler);


		startDataCollectorLoop();

		mBTPeerMAC = deviceMACAddress;

		return true;
	}

	Thread mtDataCollector = null;

	private boolean startDataCollectorLoop() {
		if (mtDataCollector != null) {
			return false;
		}

		// Define the thread.
		mtDataCollector = new Thread() {
			public void run() {
				int loops = 0;
//				boolean hardwareDetected = false;
				while (mThreadsOn == true) {
					loops++;
					mgStats.setStat("loops", "" + loops);
					// moved this to the top of the loop so that we can run a
					// "continue" and not cause a runaway loop.
					EasyTime.safeSleep(5000);

					// when hs goes from null to defined, that means a device
					// was discovered.
					if (hs != null) {

						// run session detection if necessary.
						if (hs.getEBT().isConnected() == true) {
							// dumpStatsToScreen();
						}

						// Has the session type been detected and switched to
						// moni by the detectSessionAndStartSniffing process...
						if (hs.getCurrentSessionType() == HybridSession.SESSION_TYPE_MONITOR) {
							MonitorSession m = hs.getMonitorSession();
							
							if (loops % 5 == 0) {
								dumpStatsToLogfile();
							}
							
							// Show GeneralStats leading up to a full connection
							// state. After that, display DPNs.
							if (m != null
									&& m.getCurrentState() >= MonitorSession.STATE_SNIFFING
									&& hs.getPIDDecoder().getNetworkID()
											.length() > 0) {
								dumpAllDPNsToScreen();
							} else {
								// network not ID'd but we're sniffing. 
//								if (loops % 10 == 0) dumpStatsToScreen();
								clearMessages();
								msg ("monitor stats: \n" + hs.getNetworkStats().getStatsString());
							}
						}
					} else {
						// hybrid session not defined or session still being
						// detected. do nothing / Sleep longer?
					}

				}// end of main while loop.
				msg("Data collector loop finished.");
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
		msg(hs.getPIDDecoder().getAllDataPointsAsString());
	}

//	// TODO: Make sure hybridSEssion is using the new obd packet parser class to
//	// decode responses.
//	/**
//	 * Do whatever necessary to detect hardware type and get it into sniff mode.
//	 * We only try to detect the hardware once, if it fails, we return false. So
//	 * if you get a false reading from this method, make another call. repeat as
//	 * necessary.
//	 */
//	private boolean detectSessionAndStartSniffing() {
//		msg("Running session detection...");
//		boolean result = hs.runSessionDetection();
//		msg("Session detection result was " + hs.getCapabilitiesString());
//		if (result == true) {
//			// session detection was successful, move to next phase.
//			msg("Is hardware SWCAN? " + hs.isHardwareSWCAN());
//			msg("Session detection succeeded, Switching to moni session... If possible. ");
//			if (hs.isHardwareSniffable() && hs.isHardwareSWCAN() != true) {
//				msg("Hardware IS sniffable, so switching to moni");
//				hs.setActiveSession(HybridSession.SESSION_TYPE_MONITOR);
//				msg("After switch to moni.");
//			} else {
//				msg("Hardware does not support sniff.");
//				return false;
//			}
//
//			if (hs.isHardwareSWCAN()) {
//				msg("Proceed with command sending now.");
//			}
//		} else {
//			// return value of the session deteciton was false so return false.
//			return false;
//		}
//
//		return true;
//	}

	private void msg(final String messg) {
		final String m = getThreadID() + " " + messg;
		Log.d("Sleuth", m);
		muiHandler.post(new Runnable() {
			public void run() {
				tvMain.append(m + "\n");
			}
		});
	}
	
	private void clearMessages () {
		muiHandler.post(new Runnable() {
			public void run() {
				tvMain.setText("");
			}
		});
		
	}

	private String getThreadID() {
		final String m = "[T" + Thread.currentThread().getId() + "]";
		return m;
	}

	// Defines the logic to take place when an out of band message is generated
	// by the hybrid session layer.
	EventCallback ecbOOBMessageHandler = new EventCallback() {
		@Override
		public void onOOBDataArrived(String dataName, String dataValue) {
			
			// IO state changed, we connected, kick off discovery?
//			if () 

			if (mThreadsOn != true) {
				msg("Ignoring OOB message out of scope. Threads are off. " + dataName + "=" + dataValue);
				return;
			}

			msg("OOB Data: " + dataName + "=" + dataValue);

			if (dataName.equals(OOBMessageTypes.AUTODETECT_SUMMARY)) {
				if (hs.isDetectionValid() == true) {
					msg("Hardware detection completed. String=" + hs.getCapabilitiesString());
					
					if (hs.isHardwareSniffable() == true) {
						msg ("Hardware is sniffable - Jumping to monitor mode now!");
						hs.setActiveSession(HybridSession.SESSION_TYPE_MONITOR);
						turnOnLogging ();
					}
				}
			}

			// state change?
			if (dataName.equals(OOBMessageTypes.IO_STATE_CHANGE)) {
				int newState = 0;
				try {
					newState = Integer.valueOf(dataValue);
					msg("IO State changed to " + newState);
					// ioStateChanged(newState);
				} catch (NumberFormatException e) {
					msg("ERROR: Could not interpret new state as string: " + dataValue);
				}

				if (newState > 0) {
					// do auto session detection stuff!
					if (hs.isDetectionValid() != true) {
						msg("Running hardware/network detect...");
						boolean ret = hs.runSessionDetection();
						msg("Hardware/network results: " + ret);
					} else {
						msg("Skipping extraneous IO connect event");
					}
				} else {
					// mStatusBox.setStatusLevel("Bluetooth Disconnected", 2);
				}

			}// end of "if this was a io state change".

			// session state change?
			if (dataName.equals(OOBMessageTypes.SESSION_STATE_CHANGE) && hs.getCurrentSessionType() == HybridSession.SESSION_TYPE_MONITOR) {
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

	
	private void turnOnLogging () {
		// Turn on logging and dump stats to it. 
		msg ("Turning on logging, dumping stats.");
		NetworkStats ns = hs.getNetworkStats();
		if (ns == null ) {
			msg ("No networkstats object defined yet. Unable to collect network stats.");
			return;
		}
		ns.setLogging(true);
		String stats = getStats().getAllStats();
		ns.appendLog(stats);
		msg ("Stats filename = " + ns.getLogFilePath());
	}
	
	
	EventCallback ecbDPNArrivedHandler = new EventCallback() {
		@Override
		public void onDPArrived(String DPN, String sDecodedData,
				int iDecodedData) {
			// msg ("DPN Arrived: " + DPN + "=" + sDecodedData);
		}// end of onDPArrived.
	};// end of eventcallback def.

	public GeneralStats getStats() {
		if (hs != null)
			mgStats.merge("hs", hs.getStats());

		return mgStats;
	}

	
	private void dumpStatsToLogfile () {
		final String _stats = getStats().getAllStats();
		hs.getNetworkStats().appendLog(_stats);
	}
	
	private void dumpStatsToScreen() {
		final String _stats = getStats().getAllStats();
		msg(_stats);
	}

	// private void setScreenText (final String newText) {
	// final TextView tv = (TextView) findViewById(R.id.tvMain);
	//
	// muiHandler.post(new Runnable () {
	// public void run () {
	// tv.setText(newText);
	// }
	// });
	// }

	private boolean setButtonEventHandlers() {
		if (btnLock == null || btnUnlock == null) {
			msg("WTF. Buttons are null. Can't set events");
			return false;
		}

		// lock the doors.
		btnLock.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				doCommand("FOB_LOCK");
			}// end of OnClick
		}); // end of setOnclickListener

		// unlock the doors.
		btnUnlock.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				doCommand ("FOB_UNLOCK");
			}// end of onclick.
		}); // end of setonclicklistener.

		// wake up the network. 
		btnWake.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				doWakeup();
			}// end of onClick
		});// end of setonclicklistener
		
		
		return true;
	}// end of setOnButtonEventHandlers.

	private boolean doCommand (String cmdName) {
		msg ("Commands are disabled for now. Data-collection only.");
		return true;
/*
		if (hs.isHardwareSWCAN() != true) {
			msg("WARANING: hardware may not be SWCAN");
		}

		if (hs.getCurrentSessionType() != HybridSession.SESSION_TYPE_COMMAND) {
			msg("Switching to command session (");
			hs.setActiveSession(HybridSession.SESSION_TYPE_COMMAND);
		}

		if (hs.getCommandSession() == null) {
			msg("Command session not set up yet. ");
			return false;
		}
		msg("Sending UNLock command");
		hs.getCommandSession().sendCommand(cmdName);
		
		return true;
*/
	}

	private void doWakeup () {
		msg ("Wake-up DISABLED for now...");
		
		/*		
		
		if (hs.getCurrentSessionType() != HybridSession.SESSION_TYPE_COMMAND) {
			msg("Switching to command session (");
			hs.setActiveSession(HybridSession.SESSION_TYPE_COMMAND);
		}

		msg ("Sending wakeup...");
		hs.getCommandSession().wakeUpAllNetworks();
		msg ("Wakeup has been sent. ");
		
		*/
	}
	
	
}// end of class. 