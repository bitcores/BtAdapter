//	revision 0011
package net.bitcores.btadapter;

import android.bluetooth.BluetoothAdapter;

public class BtCommon {
	
	public BtCommon() {
		
	}
	
	//	because of the number of non-blocking calls available from the bluetooth adapter
	//	we may want it available in the UI thread, so lets make this public so it can be
	//	referenced from there.
	//	care should be made so that no calls are made to it unless initBt returns true
	//	both BtAdapter and BleAdapter depend on this so it is here
	public static BluetoothAdapter mBluetoothAdapter;
	
	//	message types to send back to the handler
	public static final int MESSAGE_STATE_CHANGE = 0;
	public static final int MESSAGE_CONNECTED_DEVICE = 1;
	public static final int MESSAGE_RECEIVE_DATA = 2;
	public static final int MESSAGE_DISCONNECT_DEVICE = 3;
	public static final int MESSAGE_CONNECTION_LOST = 4;
	public static final int MESSAGE_SERVICES_DISCOVERED = 5;
	
}
