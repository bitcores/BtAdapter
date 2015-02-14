//	this is written based off the bluetooth api guides at
//	http://developer.android.com/guide/topics/connectivity/bluetooth.html
//	http://developer.android.com/guide/topics/connectivity/bluetooth-le.html
//	and their sample packages
//	https://developer.android.com/samples/BluetoothChat/index.html
//	https://developer.android.com/samples/BluetoothLeGatt/index.html
//	my aim however is to turn this into a class that i can just drop into any app as-is to add bluetooth communications
//	which can then be extended to add application specific features, or whatever
//	i guess this should be in here as well then
/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
//	revision 0007

package net.bitcores.bluetoothtest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

public class BtAdapter {
	//	a single accept thread will be fine in both single and multi connection modes as the
	//	thread just loops waiting for connections
	private AcceptThread mAcceptThread;
	
	//	because we could potentially desire to connect to multiple bluetooth devices at once lets
	//	set up a hashmap like the connectionthreads one
	//	we will have to be careful that each thread is killed and does not get stuck open
	private HashMap<String, ConnectThread> connectThreads = new HashMap<String, ConnectThread>();
	
	//	theres technically no reason why we cannot have multiple bluetooth connections at once
	//	but they need to be managed in code which means we need to have reference to, at the least,
	//	the device MAC address and the connectedthread for that connection
	private HashMap<String, ConnectedThread> connectedThreads = new HashMap<String, ConnectedThread>();
	//	UI may also want a list of connected devices by MAC address and name
	
	//	when working with multiple connections potentially between a mixture of bluetooth classic and
	//	ble we need to keep a map so that when the UI says "i want to interact with to this device/mac"
	//	we know what methods to use
	private HashMap<String, String> btConnections = new HashMap<String, String>();
	
	private Context mContext;
	private Handler mHandler;
	
	//	for simplicity sake we will use the hashmap below even if multiconnectmode is disabled
	//	however we will manage connections to prevent there being more than one active connection
	//	we also want this set only during initBt to prevent any confusing in switching modes
	private boolean multiConnectionMode = false;
		
	static String NAME = "Pants";
	//	this UUID is required for connecting to HC-06
	//	if target device isnt HC-06 other UUIDs may be used ?
	//	i dont really know about this
	static UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
	
	static final String TAG = "bluetoothtest";
	
	//	because of the number of non-blocking calls available from the bluetooth adapter
	//	we may want it available in the UI thread, so lets make this public so it can be
	//	referenced from there.
	//	care should be made so that no calls are made to it unless initBt returns true
	public BluetoothAdapter mBluetoothAdapter;
	
	//	putting this in here just for now, means that if the device you are connecting to
	//	supports both ble and classic it will use classic to connect
	public boolean preferLe = false;
	
	//	rather than having one overall state variable i will have three because this lets
	//	me track the listening state separately
	public static boolean STATE_LISTENING = false;
	public static boolean STATE_CONNECTING = false;
	public static boolean STATE_CONNECTED = false;
    
	//	message types to send back to the handler
	public static final int MESSAGE_STATE_CHANGE = 0;
	public static final int MESSAGE_CONNECTED_DEVICE = 1;
	public static final int MESSAGE_RECEIVE_DATA = 2;
	public static final int MESSAGE_DISCONNECT_DEVICE = 3;
	public static final int MESSAGE_CONNECTION_LOST = 4;
	
	//	bluetooth connection types
	public static final int BLUETOOTH_CLASSIC = 0;
	public static final int BLUETOOTH_LE = 1;
	public static final int BLUETOOTH_BOTH = 2;
	
	
	private GattService gattService;
	
	public BtAdapter() {
		
	}
	
	public boolean initBt(Context context, Handler handler, Boolean mode, int type) {
		if (mBluetoothAdapter == null) {
			mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
			
			if (mBluetoothAdapter == null) {
				return false;
			}
			
			resetStates();
			mContext = context;
			mHandler = handler;
			multiConnectionMode = mode;
			context.startService(new Intent(context, GattService.class));
			gattService = new GattService();
		}
		
		return true;
	}
	
	public void endBt() {
		mBluetoothAdapter = null;
		endAccept();
		disconnect(null);
		resetStates();
		mContext = null;
		mHandler = null;
		multiConnectionMode = false;
	}
	
	public void resetStates() {
		STATE_LISTENING = false;
		STATE_CONNECTING = false;
		STATE_CONNECTED = false;
	}
	
	public void startListen() {
		Log.i(TAG, "start listening for connections");
		
		accept();
	}
	public void endListen() {
		Log.i(TAG, "stop listening for connections");
		
		endAccept();
	}
	
	public void connectDevice(String address) {		
		Log.i(TAG, "connecting to '" + address + "'");
		
		//	setting a public btdevice variable will allow us to get information on the current connected device
		//	in the ui thread or other functions as needed
		BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
		if (device.getType() == BluetoothDevice.DEVICE_TYPE_CLASSIC || (device.getType() == BluetoothDevice.DEVICE_TYPE_DUAL && preferLe)) {
			Log.i(TAG, "connect using classic");
			connect(device);
		} else {
			Log.i(TAG, "connect using ble");
			gattService.connect(mContext, device);
		}
	}
		
	public void disconnectDevice(String address) {	
		Log.i(TAG, "do disconnect");
		
		//	if address is null all devices will be disconnected including devices in the process of being connected
		//	otherwise the address will be checked in the endconnect/endconnected methods
		disconnect(address);
	}
	
	//	return a hashmap containing a list of devices currently connected
	//	i do not think a method for connecting devices will be necessary
	public HashMap<String, String> connectedDevices() {
		HashMap<String, String> mConnectedDevices = new HashMap<String, String>();
		String[] keys = connectedThreads.keySet().toArray(new String[connectedThreads.size()]);
		for (String mAddress : keys) {
			ConnectedThread thread = connectedThreads.get(mAddress);
			String device = thread.mmDevice.getName();
			mConnectedDevices.put(mAddress, device);
		}
		
		return mConnectedDevices;
	}
	
	//	return gatt connection
	public BluetoothGatt getGattConnection(String address) {
		BluetoothGatt gatt = gattConnections.get(address);
		return gatt;
	}
	
	//	sending no address will allow you to send the output to all connected devices
	//	when not in multiconnectionmode this could be used instead of the address as there should be only one
	//	connected device anyway but it would be better practice to specify your target
	public void write(byte[] output) {
		String[] keys = connectedThreads.keySet().toArray(new String[connectedThreads.size()]);
		for (String address : keys) {
			ConnectedThread target;

			synchronized (BtAdapter.this) {
				target = connectedThreads.get(address);
			}
			if (target == null) {
				return;
			} 
			
			target.write(output);
			Log.i(TAG, "message sent");
		}
	}
	public void write(String address, byte[] output) {
		if (address != null) {
			ConnectedThread target;

			synchronized (BtAdapter.this) {
				target = connectedThreads.get(address);
			}
			if (target == null) {
				return;
			} 
			
			target.write(output);
			Log.i(TAG, "message sent");
		} 
	}
	
	
	//	these could potentially be left unsynchronized so long as they are only called from within synchronized methods
	//	but they are called fairly often, as you see below, and may need to be called individually in the future
	//	rather than sending null, sending no address will call the methods to remove all threads
	private synchronized void endAcceptThread() {
		if (mAcceptThread != null) {
			mAcceptThread.cancel();
			mAcceptThread = null;
		}
		STATE_LISTENING = false;
	}
	private synchronized void removeConnectThread(String address) {
		ConnectThread thread = connectThreads.get(address);
		if (thread != null) {
			thread.cancel();
			connectThreads.remove(address);
		}
	}
	private synchronized void endConnectThread() {
		String[] keys = connectThreads.keySet().toArray(new String[connectThreads.size()]);
		for (String address : keys) {
			removeConnectThread(address);
		}
		STATE_CONNECTING = false;
	}
	private synchronized void endConnectThread(String address) {
		if (address != null) {
			removeConnectThread(address);
			STATE_CONNECTING = false;
		}
	}
	private synchronized void removeConnectedThread(String address) {
		ConnectedThread thread = connectedThreads.get(address);
		if (thread != null) {
			thread.cancel();
			connectedThreads.remove(address);
			
			Message msg = mHandler.obtainMessage(MESSAGE_DISCONNECT_DEVICE);
			Bundle bundle = new Bundle();
			bundle.putString("DEVICE_ADDRESS", address);
			msg.setData(bundle);
			mHandler.sendMessage(msg);
		}
	}
	private synchronized void endConnectedThread() {
		String[] keys = connectedThreads.keySet().toArray(new String[connectedThreads.size()]);
		for (String address : keys) {
			removeConnectedThread(address);
		}
		STATE_CONNECTED = false;
	}
	private synchronized void endConnectedThread(String address) {
		if (address == null) {
			removeConnectedThread(address);	
			STATE_CONNECTED = false;
		}		
	}
	
	private synchronized void accept() {	
		if (!multiConnectionMode) {
			endAcceptThread();
			endConnectThread();
			endConnectedThread();
			STATE_LISTENING = true;
		}
		
		mAcceptThread = new AcceptThread();
		mAcceptThread.start();	
	}
	
	private synchronized void endAccept() {
		endAcceptThread();
	}
	
	private synchronized void connect(BluetoothDevice device) {
		if (!multiConnectionMode) {
			endConnectThread();			
			endConnectedThread();
			STATE_CONNECTING = true;
		}
		
		String address = device.getAddress();
		connectThreads.put(address, new ConnectThread(device));
		connectThreads.get(address).start();
	}
	
	private synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {	
		if (!multiConnectionMode) {
			endAcceptThread();	
			endConnectThread();	
			endConnectedThread();
			STATE_CONNECTED = true;
		}
		String address = device.getAddress();
		connectedThreads.put(address, new ConnectedThread(device, socket));
		connectedThreads.get(address).start();
	}
	
	private synchronized void disconnect(String address) {
		if (address == null) {
			endConnectThread();	
			endConnectedThread();
		} else {
			endConnectedThread(address);
		}
	}
	

	
	//	THREADS =======================================================================================
	//	for accepting incoming connections. will not work with HC-06 because it is slave mode only?
	private class AcceptThread extends Thread {
		private final BluetoothServerSocket mmServerSocket;
		
		public AcceptThread() {
			BluetoothServerSocket tmp = null;
			try {
				tmp = mBluetoothAdapter.listenUsingRfcommWithServiceRecord(NAME, MY_UUID);
			} catch (IOException e) { }
			mmServerSocket = tmp;
		}
		
		public void run() {
			BluetoothSocket socket = null;
			BluetoothDevice device = null;
			
			while (true) {
				try {
					socket = mmServerSocket.accept();
				} catch (IOException e) {
					break;
				}
				
				if (socket != null) {		
					
					synchronized (BtAdapter.this) {
						//	if multiconnectionmode is disabled and there is already a connection drop it, otherwise fine
						if (!multiConnectionMode && STATE_CONNECTED) {
							try {
								socket.close();
							} catch (IOException e) {
								Log.e(TAG, "failed to close unwanted socket", e);
							}
							return;
						}
						
						device = socket.getRemoteDevice();
						
						Message msg = mHandler.obtainMessage(MESSAGE_CONNECTED_DEVICE);
						Bundle bundle = new Bundle();
						bundle.putString("DEVICE_NAME", device.getName());
						bundle.putString("DEVICE_ADDRESS", device.getAddress());
						bundle.putInt("DIRECTION", 0);
						msg.setData(bundle);
						mHandler.sendMessage(msg);
						
						connected(socket, device);
					}

				}
			}
		}
		
		public void cancel() {
			try {
				mmServerSocket.close();
			} catch (IOException e) { }
		}
	}
	
	//	for creating outgoing connections
	private class ConnectThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final BluetoothDevice mmDevice;
		private final String address;
		
		public ConnectThread(BluetoothDevice device) {
			BluetoothSocket tmp = null;
			mmDevice = device;
			address = device.getAddress();
			
			Log.i(TAG, "setup connection");
			
			try {
				tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
			} catch (IOException e) {
				//	if connecting with a secure rfcomm fails attempt to fall back onto insecure comm
				try {
					tmp = device.createInsecureRfcommSocketToServiceRecord(MY_UUID);
				} catch (IOException x) { }
			}
			mmSocket = tmp;
		}
		
		public void run() {
			mBluetoothAdapter.cancelDiscovery();
			
			Log.i(TAG, "starting connection");
			
			try {
				mmSocket.connect();
			} catch (IOException connectException) {
				Log.e(TAG, "Error", connectException);
				cancel();
				return;
			}
			Log.i(TAG, "connected");
			
			synchronized (BtAdapter.this) {
				connectThreads.remove(address);
			}
			
			Message msg = mHandler.obtainMessage(MESSAGE_CONNECTED_DEVICE);
			Bundle bundle = new Bundle();
			bundle.putString("DEVICE_NAME", mmDevice.getName());
			bundle.putString("DEVICE_ADDRESS", mmDevice.getAddress());
			bundle.putInt("DIRECTION", 1);
			msg.setData(bundle);
			mHandler.sendMessage(msg);
			
			connected(mmSocket, mmDevice);
		}
		
		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) { }
		}
	}
	
	//	handles communications on a connected socket
	private class ConnectedThread extends Thread {
		private final BluetoothDevice mmDevice;
		private final BluetoothSocket mmSocket;
		private final InputStream mmInStream;
		private final OutputStream mmOutStream;
		
		public ConnectedThread(BluetoothDevice device, BluetoothSocket socket) {
			mmDevice = device;
			mmSocket = socket;
			InputStream tmpIn = null;
			OutputStream tmpOut = null;
			
			try {
				tmpIn = socket.getInputStream();
				tmpOut = socket.getOutputStream();
			} catch (IOException e) { }
						
			mmInStream = tmpIn;
			mmOutStream = tmpOut;
		}
		
		public void run() {
			byte[] buffer = new byte[1024];
			int bytes;
			Log.i(TAG, "listening on socket");
			
			while (true) {
				try {
					Log.i(TAG, "message received");
					bytes = mmInStream.read(buffer);
					
					Message msg = mHandler.obtainMessage(MESSAGE_RECEIVE_DATA, bytes, -1, buffer);
					Bundle bundle = new Bundle();
					bundle.putString("DEVICE_ADDRESS", mmDevice.getAddress());
					msg.setData(bundle);
					mHandler.sendMessage(msg);
				} catch (IOException e) {
					Log.i(TAG, "connection lost");
					
					Message msg = mHandler.obtainMessage(MESSAGE_CONNECTION_LOST);
					Bundle bundle = new Bundle();
					bundle.putString("DEVICE_ADDRESS", mmDevice.getAddress());
					msg.setData(bundle);
					mHandler.sendMessage(msg);
					break;
				}
			}
			
		}
		
		public void write(byte[] bytes) {
			try {
				mmOutStream.write(bytes);
			} catch (IOException e) { }
		}
		
		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) { }
		}
	}
	
	//	handles GATT/Le
	private class GattService extends Service {
		
		//	once again i want a hashmap so that i can handle multiple gatt threads at once if allowed
		//	under the multi connection mode. these will be handled differently than other connections though
		//private HashMap<String, GattThread> gattThreads = new HashMap<String, GattThread>();
		private HashMap<String, BluetoothGatt> gattConnections = new HashMap<String, BluetoothGatt>();
		
		
		public GattService() {
			
		}

		@Override
		public IBinder onBind(Intent intent) {
			return null;
		}
		
		public void connect(Context context, BluetoothDevice device) {
			BluetoothGatt gatt = device.connectGatt(context, false, mGattCallback);
			String address = device.getAddress();
			gattConnections.put(address, gatt);
		}
		
		public void read(String address, BluetoothGattCharacteristic characteristic) {
			BluetoothGatt gatt = getGattConnection(address);
			if (gatt != null) {
				gatt.readCharacteristic(characteristic);
			} else {
				
			}
		}
		
		public void write(String address, BluetoothGattCharacteristic characteristic) {
			BluetoothGatt gatt = getGattConnection(address);
			if (gatt != null) {
				gatt.writeCharacteristic(characteristic);
			} else {
				
			}
		}
		
		public void cancel(String address) {
			BluetoothGatt gatt = getGattConnection(address);
			if (gatt != null) {
				gatt.close();
			}
		}
		
	}
	
	/*
	private class GattThread extends Thread {
		private final BluetoothDevice mmDevice;		
		private final Context mmContext;
		private BluetoothGatt mmBluetoothGatt;
		
		public GattThread(Context context, BluetoothDevice device) {
			mmDevice = device;
			mmContext = context;
			mmBluetoothGatt = null;
		}
		
		public void run() {
			
			while (true) {
				
			}
		}
		
		public void connectBle() {
			mmBluetoothGatt = mmDevice.connectGatt(mmContext, false, mGattCallback);
		}
		
		public void readBle(BluetoothGattCharacteristic characteristic) {
			if (mmBluetoothGatt == null) {
				cancel();
			} else {
				mmBluetoothGatt.readCharacteristic(characteristic);
			}
		}
		
		public void write(BluetoothGattCharacteristic characteristic) {
			if (mmBluetoothGatt == null) {
				cancel();
			} else {
				mmBluetoothGatt.writeCharacteristic(characteristic);
			}
		}
		
		public void cancel() {
			mmBluetoothGatt.close();
		}	
	}
	*/
	
	//	callback for the gatt connections
	private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
		@Override
		public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
			
			if (status == BluetoothGatt.GATT_SUCCESS) {
				if (newState == BluetoothProfile.STATE_CONNECTED) {
					Log.i(TAG, "gatt connected");
					gatt.discoverServices();
				} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
					Log.i(TAG, "gatt disconnected");
				} else {
					Log.i(TAG, "gatt different state");
				}
			}
		}
		
		@Override
		public void onServicesDiscovered(BluetoothGatt gatt, int status) {
			BluetoothDevice device = gatt.getDevice();
			
			List<BluetoothGattCharacteristic> characteristics = null;
			List<BluetoothGattService> services = gatt.getServices();
			BluetoothGattCharacteristic SerialCharacteristic = null;
			for (BluetoothGattService service : services) {
			    characteristics = service.getCharacteristics();
			}
			
			String uuid;
			for (BluetoothGattCharacteristic gattCharacteristic : characteristics) {
				uuid = gattCharacteristic.getUuid().toString();
				if(uuid.equals("00002a24-0000-1000-8000-00805f9b34fb")){
					//mModelNumberCharacteristic=gattCharacteristic;
				} else if(uuid.equals("0000dfb1-0000-1000-8000-00805f9b34fb")){
					SerialCharacteristic = gattCharacteristic;
					Log.i(TAG, "serial characteristic found, sending time");
					
					BluetoothGattCharacteristic WriteCharacteristic = SerialCharacteristic;
					String address = device.getAddress();
					
					String time = "tp";
					long unixTime = System.currentTimeMillis() / 1000L;
					Calendar cal = Calendar.getInstance();
					int offset = (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 1000;
					time = time + (unixTime + offset);
					
					WriteCharacteristic.setValue(time);
					
					gattService.write(address, WriteCharacteristic);
					
					// updateConnectionState(R.string.comm_establish);
				} else if(uuid.equals("0000dfb2-0000-1000-8000-00805f9b34fb")){
					//mCommandCharacteristic = gattCharacteristic;
					// updateConnectionState(R.string.comm_establish);
				}
			}
			
			/*
			for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) {
			    //find descriptor UUID that matches Client Characteristic Configuration (0x2902)
			    // and then call setValue on that descriptor

			    descriptor.setValue( BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
			    bluetoothGatt.writeDescriptor(descriptor);
			}*/
			
		}
		
		@Override
		public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
			BluetoothDevice device = gatt.getDevice();
		}
		
		@Override
		public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
			BluetoothDevice device = gatt.getDevice();
		}
		
		@Override
		public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
			BluetoothDevice device = gatt.getDevice();
		}
	};
}
