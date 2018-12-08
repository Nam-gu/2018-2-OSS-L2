package org.empyrn.darkknight.bluetooth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

/**
 * This class does all the work for setting up and managing Bluetooth
 * 블루투스를 설정하고 관리하는 작업을 하는 클래스
 * 
 * connections with other devices. It has a thread that listens for incoming
 * 다른 기기들과의 연결. 들어오는 연결을 받기위한 쓰레드를 가진다.
 * 
 * connections, a thread for connecting with a device, and a thread for
 * 기기와 연결을 하기 위한 쓰레드
 * 
 * performing data transmissions when connected.
 * 연결이 되었을 때 데이터 전송을 위한 쓰레드
 */


public class BluetoothGameService {
	// Debugging
	// 디버깅
	private static final String TAG = "BluetoothGameControllerService";
	private static final boolean D = true;

	// Name for the SDP record when creating server socket
	// 서버 소켓이 만들어 졌을때 SDP record의 이름
	private static final String NAME = "BluetoothGameController";

	// Unique UUID for this application

	private static final UUID MY_UUID = UUID.fromString("72caefa0-568b-11e0-b8af-0800200c9a66");

	// Member fields
	private final BluetoothAdapter mAdapter;
	private final Handler mHandler;
	private AcceptThread mAcceptThread;
	private ConnectThread mConnectThread;
	private ConnectedThread mConnectedThread;
	private int mState;

	// Constants that indicate the current connection state
	// 현재 상태를 나타내는 상수들
	// 아무것도 하지 않을때 0
	// 들어오는 연결을 받을때 1
	// 나가는 연결을 초기화 2
	// 장치와 연결 3
	// 연결 끊김 4
	public static final int STATE_NONE = 0; // we're doing nothing
	public static final int STATE_LISTEN = 1; // now listening for incoming
												// connections
	public static final int STATE_CONNECTING = 2; // now initiating an outgoing
													// connection
	public static final int STATE_CONNECTED = 3; // now connected to a remote
													// device
	public static final int STATE_LOST_CONNECTION = 4; // lost connection

	/**
	 * Constructor. Prepares a new BluetoothGameController session.
	 * 
	 * @param context
	 *            The UI Activity Context
	 * @param handler
	 *            A Handler to send messages back to the UI Activity
	 */
	public BluetoothGameService(Context context, Handler handler) {
		mAdapter = BluetoothAdapter.getDefaultAdapter();
		mState = STATE_NONE;
		mHandler = handler;
	}

	/**
	 * 현재 chat 연결의 상태를 설정
	 * Set the current state of the chat connection
	 * 
	 * @param state
	 *            An integer defining the current connection state
	 * 현재 연결 상태를 저장하는 변수
	 */
	private synchronized void setState(int state) {
		if (D)
			Log.d(TAG, "setState() " + mState + " -> " + state);
		mState = state;


		// Give the new state to the Handler so the UI Activity can update
		// 핸들러에게 새로운 상태 줌. UI Activity가 업데이트를 할 수 있도록
		mHandler.obtainMessage(BluetoothGameController.MESSAGE_STATE_CHANGE,
				state, -1).sendToTarget();
	}

	/**
	 * Return the current connection state.
	 * 연재 연결 상태 값을 반환함
	 */
	public synchronized int getState() {
		return mState;
	}

	/**
	 * Start the chat service. Specifically start AcceptThread to begin a
	 * session in listening (server) mode.
	 * chat 서비스를 시작함
	 * 
	 * accept thread가 리스닝 모드에서 세션을 시작함
	 */
	public synchronized void start() {
		if (D)
			Log.d(TAG, "start");

		// Cancel any thread attempting to make a connection
		// 연결을 하려고 시도하는 쓰레드를 취소
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		// Cancel any thread currently running a connection
		// 현재 연결하는 쓰레드 취소
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		// Start the thread to listen on a BluetoothServerSocket
		// 쓰레드 시작(블루투스 서버소켓 listen)
		if (mAcceptThread == null) {
			mAcceptThread = new AcceptThread();
			mAcceptThread.start();
		}
		setState(STATE_LISTEN);

		Message msg = mHandler
				.obtainMessage(BluetoothGameController.MESSAGE_TOAST);
		Bundle bundle = new Bundle();
		bundle.putString(BluetoothGameController.TOAST,
				"Bluetooth chess service started");
		msg.setData(bundle);
		mHandler.sendMessage(msg);
	}

	/**
	 * Reset the service.
	 * 서버 리셋
	 */
	public synchronized void reset() {
		// Cancel any thread attempting to make a connection
		// 연결을 하려고 시도하는 쓰레드를 취소
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		// Cancel any thread currently running a connection
		// 현재 연결하는 쓰레드 취소
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		// Start the thread to listen on a BluetoothServerSocket
		// 쓰레드 시작(블루투스 서버소켓 listen)
		if (mAcceptThread == null) {
			mAcceptThread = new AcceptThread();
			mAcceptThread.start();
		}

		setState(STATE_LISTEN);
	}

	/**
	 * Start the ConnectThread to initiate a connection to a remote device.
	 * 기기와 연결을 시작하기 위해 connect thread 시작
	 * 
	 * @param device
	 *            The BluetoothDevice to connect
	 * 연결하기 위한 블루투스 기기
	 */
	public synchronized void connect(BluetoothDevice device) {
		if (D)
			Log.d(TAG, "connect to: " + device);

		// Cancel any thread attempting to make a connection
		// 연결을 하려고 시도하는 쓰레드를 취소
		if (mState == STATE_CONNECTING) {
			if (mConnectThread != null) {
				mConnectThread.cancel();
				mConnectThread = null;
			}
		}

		// Cancel any thread currently running a connection
		// 현재 연결하는 쓰레드 취소
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		// Start the thread to connect with the given device
		// 쓰레드 시작(블루투스 서버소켓 listen)
		mConnectThread = new ConnectThread(device);
		mConnectThread.start();
		setState(STATE_CONNECTING);
	}

	/**
	 * Start the ConnectedThread to begin managing a Bluetooth connection
	 * 블루투수 연결을 관리하는 것을 시작하기 위해 연결된 쓰레드 시작
	 * 
	 * @param socket
	 *            The BluetoothSocket on which the connection was made
	 * 블루투스 소켓 연결이 되는 곳
	 * @param device
	 *            The BluetoothDevice that has been connected
	 * 블루투스 기기 연결됨
	 */
	public synchronized void connected(BluetoothSocket socket,
			BluetoothDevice device) {
		if (D)
			Log.d(TAG, "connected");

		// Cancel the thread that completed the connection
		// 연결이 끝난 쓰레드 cancel
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		// Cancel any thread currently running a connection
		// 현재 연결하는 쓰레드 cancel
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		// Cancel the accept thread because we only want to connect to one
		// device
		// 받이진 쓰레드 cancel 하나의 기기에만 연결을 원하기 때문에
		if (mAcceptThread != null) {
			mAcceptThread.cancel();
			mAcceptThread = null;
		}

		// Start the thread to manage the connection and perform transmissions
		// 연결을 관리하고 전송을 수행하는 쓰레드 시작
		mConnectedThread = new ConnectedThread(socket);
		mConnectedThread.start();

		// Send the name of the connected device back to the UI Activity
		// 연결된 기기의 이름을 UI Activity에 보내줌
		Message msg = mHandler
				.obtainMessage(BluetoothGameController.MESSAGE_DEVICE_NAME);
		Bundle bundle = new Bundle();
		bundle.putString(BluetoothGameController.DEVICE_NAME, device.getName());
		msg.setData(bundle);
		mHandler.sendMessage(msg);

		setState(STATE_CONNECTED);
	}

	/**
	 * Stop all threads
	 * 모든 쓰레드 정지
	 */
	public synchronized void stop() {
		if (D)
			Log.d(TAG, "stop");

		Message msg = mHandler
				.obtainMessage(BluetoothGameController.MESSAGE_TOAST);
		Bundle bundle = new Bundle();
		bundle.putString(BluetoothGameController.TOAST,
				"Bluetooth chess service stopped");
		msg.setData(bundle);
		mHandler.sendMessage(msg);

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
		setState(STATE_NONE);
	}

	/**
	 * Write to the ConnectedThread in an unsynchronized manner
	 * 
	 * @param out
	 *            The bytes to write
	 * @see ConnectedThread#write(byte[])
	 */
	public void write(byte[] out) {
		// Create temporary object
		// 임시 객체 생성
		ConnectedThread r;
		// Synchronize a copy of the ConnectedThread
		// 연결된 쓰레드의 복사본과 동기화
		synchronized (this) {
			if (mState != STATE_CONNECTED)
				return;
			r = mConnectedThread;
		}
		// Perform the write unsynchronized
		// write 비동기화 수행
		r.write(out);
	}

	/**
	 * Indicate that the connection attempt failed and notify the UI Activity.
	 * 연결 시도 실패를 나타냄
	 */
	private void connectionFailed() {
		setState(STATE_LISTEN);

		// Send a failure message back to the Activity
		// 실패 메세지 보냄
		Message msg = mHandler
				.obtainMessage(BluetoothGameController.MESSAGE_TOAST);
		Bundle bundle = new Bundle();
		bundle.putString(BluetoothGameController.TOAST,
				"Unable to connect to device");
		msg.setData(bundle);
		mHandler.sendMessage(msg);
	}

	/**
	 * Indicate that the connection was lost and notify the UI Activity.
	 */
	private void connectionLost() {
		setState(STATE_LOST_CONNECTION);
	}

	/**
	 * This thread runs while listening for incoming connections. It behaves
	 * like a server-side client. It runs until a connection is accepted (or
	 * until cancelled).
	 */
	private class AcceptThread extends Thread {
		// The local server socket
		private final BluetoothServerSocket mmServerSocket;

		public AcceptThread() {
			BluetoothServerSocket tmp = null;

			// Create a new listening server socket
			try {
				tmp = mAdapter
						.listenUsingRfcommWithServiceRecord(NAME, MY_UUID);
			} catch (IOException e) {
				Log.e(TAG, "listen() failed", e);
			}
			mmServerSocket = tmp;
		}

		public void run() {
			if (D)
				Log.d(TAG, "BEGIN mAcceptThread" + this);
			setName("AcceptThread");
			BluetoothSocket socket = null;

			// Listen to the server socket if we're not connected
			while (mState != STATE_CONNECTED) {
				try {
					// This is a blocking call and will only return on a
					// successful connection or an exception
					socket = mmServerSocket.accept();
				} catch (IOException e) {
					Log.e(TAG, "accept() failed", e);
					break;
				}

				// If a connection was accepted
				if (socket != null) {
					synchronized (BluetoothGameService.this) {
						switch (mState) {
						case STATE_LISTEN:
						case STATE_CONNECTING:
							// Situation normal. Start the connected thread.
							connected(socket, socket.getRemoteDevice());
							break;
						case STATE_NONE:
						case STATE_CONNECTED:
							// Either not ready or already connected. Terminate
							// new socket.
							try {
								socket.close();
							} catch (IOException e) {
								Log.e(TAG, "Could not close unwanted socket", e);
							}
							break;
						}
					}
				}
			}
			if (D)
				Log.i(TAG, "END mAcceptThread");
		}

		public void cancel() {
			if (D)
				Log.d(TAG, "cancel " + this);
			try {
				mmServerSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "close() of server failed", e);
			}
		}
	}

	/**
	 * This thread runs while attempting to make an outgoing connection with a
	 * device. It runs straight through; the connection either succeeds or
	 * fails.
	 */
	private class ConnectThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final BluetoothDevice mmDevice;

		public ConnectThread(BluetoothDevice device) {
			mmDevice = device;
			BluetoothSocket tmp = null;

			// Get a BluetoothSocket for a connection with the
			// given BluetoothDevice
			try {
				tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
			} catch (IOException e) {
				Log.e(TAG, "create() failed", e);
			}
			mmSocket = tmp;
		}

		public void run() {
			Log.i(TAG, "BEGIN mConnectThread");
			setName("ConnectThread");

			// Always cancel discovery because it will slow down a connection
			mAdapter.cancelDiscovery();

			// Make a connection to the BluetoothSocket
			try {
				// This is a blocking call and will only return on a
				// successful connection or an exception
				mmSocket.connect();
			} catch (IOException e) {
				connectionFailed();
				// Close the socket
				try {
					mmSocket.close();
				} catch (IOException e2) {
					Log.e(TAG,
							"unable to close() socket during connection failure",
							e2);
				}
				// Start the service over to restart listening mode
				BluetoothGameService.this.start();
				return;
			}

			// Reset the ConnectThread because we're done
			synchronized (BluetoothGameService.this) {
				mConnectThread = null;
			}

			// Start the connected thread
			connected(mmSocket, mmDevice);
		}

		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "close() of connect socket failed", e);
			}
		}
	}

	/**
	 * This thread runs during a connection with a remote device. It handles all
	 * incoming and outgoing transmissions.
	 */
	private class ConnectedThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final InputStream mmInStream;
		private final OutputStream mmOutStream;

		public ConnectedThread(BluetoothSocket socket) {
			Log.d(TAG, "create ConnectedThread");
			mmSocket = socket;
			InputStream tmpIn = null;
			OutputStream tmpOut = null;

			// Get the BluetoothSocket input and output streams
			try {
				tmpIn = socket.getInputStream();
				tmpOut = socket.getOutputStream();
			} catch (IOException e) {
				Log.e(TAG, "temp sockets not created", e);
			}

			mmInStream = tmpIn;
			mmOutStream = tmpOut;
		}

		public void run() {
			Log.i(TAG, "BEGIN mConnectedThread");
			byte[] buffer = new byte[1024];
			int bytes;

			// Keep listening to the InputStream while connected
			while (true) {
				try {
					// Read from the InputStream
					bytes = mmInStream.read(buffer);

					// Send the obtained bytes to the UI Activity
					mHandler.obtainMessage(
							BluetoothGameController.MESSAGE_READ, bytes, -1,
							buffer).sendToTarget();
				} catch (IOException e) {
					Log.e(TAG, "disconnected", e);
					connectionLost();
					break;
				}
			}
		}

		/**
		 * Write to the connected OutStream.
		 * 
		 * @param buffer
		 *            The bytes to write
		 */
		public void write(byte[] buffer) {
			try {
				mmOutStream.write(buffer);

				// Share the sent message back to the UI Activity
				mHandler.obtainMessage(BluetoothGameController.MESSAGE_WRITE,
						-1, -1, buffer).sendToTarget();
			} catch (IOException e) {
				Log.e(TAG, "Exception during write", e);
			}
		}

		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "close() of connect socket failed", e);
			}
		}
	}
}
