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
//	revision 0001

package net.bitcores.bluetoothtest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.util.Log;

public class BtAdapter {
	BluetoothAdapter mBluetoothAdapter;
	
	private AcceptThread mAcceptThread;
	private ConnectThread mConnectThread;
	private ConnectedThread mConnectedThread;
	private BluetoothDevice activeDevice;
	
	private Context btContext;
	private Handler btHandler;	
	
	static String NAME = "Pants";
	//	this UUID is required for connecting to HC-06
	//	if target device isnt HC-06 other UUIDs may be used ?
	//	i dont really know about this
	static UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
	
	static final String TAG = "bluetoothtest";
	
	public BtAdapter() {
		
	}
	
	public boolean initBt(Context context, Handler handler) {
		if (mBluetoothAdapter == null) {
			mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
			
			if (mBluetoothAdapter == null) {
				return false;
			}
			
			btContext = context;
			btHandler = handler;
		}
		
		return true;
	}
	
	public void endBt() {
		mBluetoothAdapter = null;
		btContext = null;
		btHandler = null;
	}
	
	public void connectDevice(String address) {		
		Log.i(TAG, "connecting to '" + address + "'");
		
		BluetoothDevice activeDevice = mBluetoothAdapter.getRemoteDevice(address);
		connect(activeDevice);
	}
	
	public void disconnectDevice() {
		if (activeDevice != null) {
			disconnect(activeDevice);
			
			activeDevice = null;
			//connectedBt.setText("");
			//outputView.setText("");
		}
	}
	
	public void write(byte[] output) {
		ConnectedThread r;
		synchronized (this) {
			r = mConnectedThread;
		}
		
		r.write(output);
	}
	
	private synchronized void disconnect(BluetoothDevice device) {
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}
		
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}
		
		if (mAcceptThread != null) {
			mAcceptThread.cancel();
			mAcceptThread = null;
		}
	}
	
	private synchronized void connect(BluetoothDevice device) {
		
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}
		
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}
		
		mConnectThread = new ConnectThread(device);
		mConnectThread.start();
	}
	
	private synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}
		
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}
		
		mConnectedThread = new ConnectedThread(socket);
		mConnectedThread.start();
	}
	
	//	for accepting connections. will not work with HC-06 because it is slave mode only?
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
			
			while (true) {
				try {
					socket = mmServerSocket.accept();
				} catch (IOException e) {
					break;
				}
				
				if (socket != null) {
					// something for managing the connection
					//manageConnectedSocket(socket);
					cancel();
					break;
				}
			}
		}
		
		public void cancel() {
			try {
				mmServerSocket.close();
			} catch (IOException e) { }
		}
	}
	
	
	private class ConnectThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final BluetoothDevice mmDevice;
		
		public ConnectThread(BluetoothDevice device) {
			BluetoothSocket tmp = null;
			mmDevice = device;
			
			Log.i(TAG, "setup connection");
			
			try {
				tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
			} catch (IOException e) { }
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
			// socket management shit
			//manageConnectedSocket(mmSocket);
			
			synchronized (this) {
				mConnectThread = null;
			}
			
			
			connected(mmSocket, mmDevice);
		}
		
		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) { }
		}
	}
	
	private class ConnectedThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final InputStream mmInStream;
		private final OutputStream mmOutStream;
		
		public ConnectedThread(BluetoothSocket socket) {
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
					bytes = mmInStream.read(buffer);
					//	send messages back to the UI thread
					btHandler.obtainMessage(1, bytes, -1, buffer).sendToTarget();
				} catch (IOException e) {
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
}
