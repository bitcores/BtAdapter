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
//	revision 0012

package net.bitcores.btadapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

public class BleAdapter extends Service {
	private BluetoothManager mBluetoothManager;
	private WriteManager mWriteManager;
	
	//	once again i want a hashmap so that i can handle multiple gatt threads at once if allowed
	//	under the multi connection mode. these will be handled differently than other connections though
	private HashMap<String, BluetoothGatt> gattConnections = new HashMap<String, BluetoothGatt>();
	
	//	store the accepted characteristics for each connection in a hashmap which will be sent back to the
	//	application when requested. the application should know what characteristics it wants to use
	//	the characteristic list could potentially be kept after a disconnection and reused if the
	//	device is connected to again without doing a discoverServices? 
	//	TODO test
	private HashMap<String, HashMap<String, HashMap<String, BluetoothGattCharacteristic>>> gattCharacteristics = new HashMap<String, HashMap<String, HashMap<String, BluetoothGattCharacteristic>>>();
	
	
	//	instead of filling these in this adapter they are now filled with hashmaps at the moment the
	//	adapter is initialized in initBle. if nothing is put in them all services/characteristics
	//	are accepted
	private static HashMap<String, String> acceptedServices;
	private static HashMap<String, String> acceptedCharacteristics;

	private Context mContext;
	private Handler mHandler;
	
	static final String TAG = "bluetooth bleadapter";
	
	public BleAdapter() {
		
	}
	
	//	this is the new way of getting the bluetooth adapter in api18+ which also gives us
	//	the bluetoothmanager, because ble requires api18+ we can use it here
	//	the old method that btadapter uses still works on api18+, so we can safely use both
	//	bt and bleadapters without worrying for now, but this may change in the future
	// TODO can i put an api warning here that will show up when initBle is evoked?
	public boolean initBle(Context context, Handler handler, Boolean mode, HashMap<String, String> services, HashMap<String, String> characteristics) {
		mContext = context;
		mHandler = handler;
		
		if (mBluetoothManager == null) {
			mBluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
			if (mBluetoothManager != null && BtCommon.mBluetoothAdapter == null) {
				BtCommon.mBluetoothAdapter = mBluetoothManager.getAdapter();
			}		
		}
		
		if (BtCommon.mBluetoothAdapter == null) {
			return false;
		}
		
		if (mWriteManager == null) {
			mWriteManager = new WriteManager();
		}
		
		if (services != null) {
			acceptedServices.putAll(services);
		}
		if (characteristics != null) {
			acceptedCharacteristics.putAll(characteristics);
		}
					
		return true;
	} 
	
	public void endBle() {
		mWriteManager.cancel();
		acceptedServices.clear();
		acceptedCharacteristics.clear();
	}
	
	
	//	return gatt connection
	public BluetoothGatt getGattConnection(String address) {
		BluetoothGatt gatt = gattConnections.get(address);
		return gatt;
	}
	
	//	return a hashmap containing a list of ble devices currently connected
	public HashMap<String, String> connectedGatts() {
		HashMap<String, String> mConnectedGatts = new HashMap<String, String>();
		String[] keys = gattConnections.keySet().toArray(new String[gattConnections.size()]);
		for (String mAddress : keys) {
			BluetoothGatt gatt = gattConnections.get(mAddress);
			String device = gatt.getDevice().getName();
			mConnectedGatts.put(mAddress, device);
		}
		
		return mConnectedGatts;
	}
	
	private void removeGattConnection(String address) {
		BluetoothGatt gatt = getGattConnection(address);
		if (gatt != null) {
			gatt.close();
			gattConnections.remove(address);
			gattCharacteristics.remove(address);
			
			if (mWriteManager != null) {
				mWriteManager.cleanBuffers(gatt);
			}
		}		
	}
	
	public BluetoothGattCharacteristic getCharacteristic(String address, String service, String characteristic) {
		BluetoothGattCharacteristic bgc = null;
		bgc = gattCharacteristics.get(address).get(service).get(characteristic);
		
		return bgc;
	}
	
	//	unlike in classic BT where an open socket is maintained between the two devices so that data can be
	//	sent anyway at any time in BLE it is pretty much up to the central device to decide when to read data
	//	from the peripheral. so if you want to get new data on from a characteristic as it comes available you
	//	set a notification on the characteristic which will then trigger the onCharacteristicChanged callback
	//	which will probably work essentially the same as the onCharacteristicRead
	public void setCharacteristicNotification(String address, String service, String characteristic, boolean enabled) {
		BluetoothGatt gatt = getGattConnection(address);
		BluetoothGattCharacteristic mCharacteristic = getCharacteristic(address, service, characteristic);
		if (gatt != null) {
			gatt.setCharacteristicNotification(mCharacteristic, enabled);
		} else {
			
		}
	}
	
	public void writeCharacteristic(String address, String service, String characteristic, byte[] bytes) {
		BluetoothGatt gatt = getGattConnection(address);
		BluetoothGattCharacteristic mCharacteristic = getCharacteristic(address, service, characteristic);
		mCharacteristic.setValue(bytes);
		if (gatt != null) {
			if (mWriteManager == null) {
				// TODO something	
			} else {
				mWriteManager.bufferWrite(gatt, mCharacteristic);
			}		
		} else {
			
		}
	}
	
	public void write (BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
		gatt.writeCharacteristic(characteristic);
	}
	
	public void readCharacteristic(String address, String service, String characteristic) {
		BluetoothGatt gatt = getGattConnection(address);
		BluetoothGattCharacteristic mCharacteristic = getCharacteristic(address, service, characteristic);
		if (gatt != null) {
			read(gatt, mCharacteristic);
		} else {
			
		}
	}
	
	public void connectDevice(String address) {		
		Log.i(TAG, "connecting to '" + address + "'");
		
		BluetoothDevice device = BtCommon.mBluetoothAdapter.getRemoteDevice(address);
		connect(device);
	}

	
	
	
	public void connect(BluetoothDevice device) {
		BluetoothGatt gatt = device.connectGatt(mContext, false, mGattCallback);
		String address = device.getAddress();
		gattConnections.put(address, gatt);
	}
	
	public void disconnect() {
		String[] keys = gattConnections.keySet().toArray(new String[gattConnections.size()]);
		for (String address : keys) {
			removeGattConnection(address);
		}
	}	
	public void disconnect(String address) {		
		removeGattConnection(address);
	}
	
	private void read(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
		if (gatt != null) {
			gatt.readCharacteristic(characteristic);
		} else {
			
		}
	}	
	
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
	
	
	//	callback for the gatt connections
	private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
		@Override
		public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
			BluetoothDevice device = gatt.getDevice();
			String address = device.getAddress();
			
			if (status == BluetoothGatt.GATT_SUCCESS) {
				if (newState == BluetoothProfile.STATE_CONNECTED) {
					Log.i(TAG, "gatt connected");

					Message msg = mHandler.obtainMessage(BtCommon.MESSAGE_CONNECTED_DEVICE);
					Bundle bundle = new Bundle();
					bundle.putString("DEVICE_NAME", device.getName());
					bundle.putString("DEVICE_ADDRESS", address);
					bundle.putInt("DIRECTION", 1);
					bundle.putInt("TYPE", 1);
					msg.setData(bundle);
					mHandler.sendMessage(msg);
					
					gatt.discoverServices();
					gattConnections.put(address, gatt);
					
				} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
					Log.i(TAG, "gatt disconnected");
					
					Message msg = mHandler.obtainMessage(BtCommon.MESSAGE_DISCONNECT_DEVICE);
					Bundle bundle = new Bundle();
					bundle.putString("DEVICE_ADDRESS", address);
					msg.setData(bundle);
					mHandler.sendMessage(msg);
					
					// do disconnect/cleanup
					
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
			
			HashMap<String, HashMap<String, BluetoothGattCharacteristic>> bgsHashmap = new HashMap<String, HashMap<String, BluetoothGattCharacteristic>>();
			HashMap<String, BluetoothGattCharacteristic> bgcHashmap;
			
			for (BluetoothGattService service : services) {
				String suuid = service.getUuid().toString();
				//Log.i(TAG, "service found: " + suuid);
				
				if (acceptedServices.containsKey(suuid) || acceptedServices.size() == 0) {
				    characteristics = service.getCharacteristics();			
					
				    bgcHashmap = new HashMap<String, BluetoothGattCharacteristic>();
					for (BluetoothGattCharacteristic gattCharacteristic : characteristics) {
						String cuuid = gattCharacteristic.getUuid().toString();
						//Log.i(TAG, "characteristic found: " + cuuid);
						
						if (acceptedCharacteristics.containsKey(cuuid) || acceptedCharacteristics.size() == 0) {
							bgcHashmap.put(acceptedCharacteristics.get(cuuid) == null ? cuuid : acceptedCharacteristics.get(cuuid), gattCharacteristic);
						}
		
					}
					bgsHashmap.put(acceptedServices.get(suuid) == null ? suuid : acceptedServices.get(suuid), bgcHashmap);
				}
			}		
			gattCharacteristics.put(device.getAddress(), bgsHashmap);
			
			Message msg = mHandler.obtainMessage(BtCommon.MESSAGE_SERVICES_DISCOVERED);
			Bundle bundle = new Bundle();
			bundle.putString("DEVICE_ADDRESS", device.getAddress());
			msg.setData(bundle);
			mHandler.sendMessage(msg);
			
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
			byte[] value = characteristic.getValue();
			String cuuid = characteristic.getUuid().toString();
			
			Message msg = mHandler.obtainMessage(BtCommon.MESSAGE_RECEIVE_DATA, value.length, -1, value);
			Bundle bundle = new Bundle();
			bundle.putString("DEVICE_ADDRESS", device.getAddress());
			bundle.putString("CHARACTERISTIC_UUID", cuuid);
			msg.setData(bundle);
			mHandler.sendMessage(msg);
		}
		
		@Override
		public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
			BluetoothDevice device = gatt.getDevice();
			if (status == BluetoothGatt.GATT_SUCCESS) {
				if (mWriteManager != null) {
					mWriteManager.bufferUnlock(gatt);
				} else {
					//	TODO something terrible has happened
				}
			}
		}
		
		@Override
		public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
			BluetoothDevice device = gatt.getDevice();
			byte[] value = characteristic.getValue();
			String cuuid = characteristic.getUuid().toString();
			
			Message msg = mHandler.obtainMessage(BtCommon.MESSAGE_RECEIVE_DATA, value.length, -1, value);
			Bundle bundle = new Bundle();
			bundle.putString("DEVICE_ADDRESS", device.getAddress());
			bundle.putString("CHARACTERISTIC_UUID", cuuid);
			msg.setData(bundle);
			mHandler.sendMessage(msg);
		}
		
	};
	
	//	thread removed
	//	because it will be possible to maintain multiple gatt connections at once and the
	//	nature of gatt writes is fairly slow and you mustnt overload a device with writes
	//	this class will manage the write operations
	private class WriteManager {
		private HashMap<BluetoothGatt, List<BluetoothGattCharacteristic>> characteristicWriteBuffer = new HashMap<BluetoothGatt, List<BluetoothGattCharacteristic>>();
		private HashMap<BluetoothGatt, Boolean> characteristicWriteLock = new HashMap<BluetoothGatt, Boolean>();
		private Integer bufferCount;
		
		public WriteManager() {

		}
		
		public void queueWrite(BluetoothGatt gatt) {
			bufferCount = 0;
			
			List<BluetoothGattCharacteristic> writeBuffer = characteristicWriteBuffer.get(gatt);
			
			//	if the value is false, meaning the gatt is not locked
			if (!characteristicWriteLock.get(gatt)) {
				if (writeBuffer.size() > 0) {
					BluetoothGattCharacteristic characteristic = writeBuffer.get(0);					
					characteristicWriteLock.put(gatt, true);
					
					//gatt.writeCharacteristic(characteristic);
					synchronized (BleAdapter.this) {
						write(gatt, characteristic);
					}
					
					writeBuffer.remove(0);										
				}
				
			} 
			
			bufferCount += writeBuffer.size();					
		}
		
		//	im not sure if i need to clear values and such or just null the thread
		//	but for now this will work
		public void cancel() {
			characteristicWriteBuffer.clear();
			characteristicWriteLock.clear();
			
			synchronized (BleAdapter.this) {
				mWriteManager = null;
			}
		}
		
		//	buffer up write operations 
		public void bufferWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
			if (characteristicWriteBuffer.containsKey(gatt)) {
				characteristicWriteBuffer.get(gatt).add(characteristic);
			} else {
				List<BluetoothGattCharacteristic> tmpList = new ArrayList<BluetoothGattCharacteristic>();
				tmpList.add(characteristic);
				characteristicWriteBuffer.put(gatt, tmpList);
			}
			
			//	each gatt needs to be in the writelock list because we will iterate that
			//	to check if they are ready for writing
			if (!characteristicWriteLock.containsKey(gatt)) {
				characteristicWriteLock.put(gatt, false);
				queueWrite(gatt);
			} else if (!characteristicWriteLock.get(gatt)) {
				queueWrite(gatt);
			}
		}
		
		public synchronized void bufferUnlock(BluetoothGatt gatt) {
			characteristicWriteLock.put(gatt, false);
			queueWrite(gatt);
		}
		
		//	call this when disconnecting a device to clear anything waiting to be written
		public void cleanBuffers(BluetoothGatt gatt) {
			characteristicWriteBuffer.remove(gatt);
			characteristicWriteLock.remove(gatt);
		}
		
		
		
	}
}
