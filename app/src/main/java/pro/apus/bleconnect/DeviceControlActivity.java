/*
 * Copyright (C) 2013 The Android Open Source Project
 * This software is based on Apache-licensed code from the above.
 * 
 * Copyright (C) 2013 APUS
 *
 *     This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.

 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package pro.apus.bleconnect;

import android.app.Activity;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Paint.Align;
import android.net.Uri;
import android.os.Bundle;

import com.dropbox.client2.DropboxAPI.Entry;

import android.os.AsyncTask;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager.LayoutParams;
import android.widget.ImageButton;
import android.widget.LinearLayout;
//import android.widget.ExpandableListView;
//import android.widget.SimpleExpandableListAdapter;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.achartengine.ChartFactory;
import org.achartengine.GraphicalView;
import org.achartengine.chart.PointStyle;
import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.model.XYSeries;
import org.achartengine.renderer.XYMultipleSeriesRenderer;
import org.achartengine.renderer.XYSeriesRenderer;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.android.AndroidAuthSession;
import com.dropbox.client2.exception.DropboxException;
import com.dropbox.client2.session.AccessTokenPair;
import com.dropbox.client2.session.AppKeyPair;
import com.dropbox.client2.session.Session.AccessType;
import com.dropbox.client2.session.TokenPair;

import com.spotify.sdk.android.player.Spotify;
import com.spotify.sdk.android.authentication.AuthenticationClient;
import com.spotify.sdk.android.authentication.AuthenticationRequest;
import com.spotify.sdk.android.authentication.AuthenticationResponse;
import com.spotify.sdk.android.player.Config;
import com.spotify.sdk.android.player.ConnectionStateCallback;
import com.spotify.sdk.android.player.Player;
import com.spotify.sdk.android.player.PlayerNotificationCallback;
import com.spotify.sdk.android.player.PlayerState;

import pro.apus.heartrate.R;

/**
 * For a given BLE device, this Activity provides the user interface to connect,
 * display data, and display GATT services and characteristics supported by the
 * device. The Activity communicates with {@code BluetoothLeService}, which in
 * turn interacts with the Bluetooth LE API.
 */
public class DeviceControlActivity extends Activity implements
		PlayerNotificationCallback, ConnectionStateCallback {
	private final static String TAG = DeviceControlActivity.class
			.getSimpleName();

	private static final String CLIENT_ID = System.getProperty("SPOTIFY_CLIENT_ID");
	private static final String REDIRECT_URI = "ambient://room";

	private final int REQUEST_CODE = 1001;
	private Player mPlayer;

	// BLE stuff
	public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
	public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";
	private BluetoothLeService mBluetoothLeService;
	private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();
	private boolean mConnected = false;
	private BluetoothGattCharacteristic mNotifyCharacteristic;
	private final String LIST_NAME = "NAME";
	private final String LIST_UUID = "UUID";

	// Database
	private EventsDataSource datasource;
	private final int MAX_R2R_SERIES_SIZE = 60;	
	private ArrayList<Integer> mR2RSeries;
	private boolean isStress;
	private int mCntAfterTrigger = 40;
	
	// Dropbox
	private DropboxAPI<AndroidAuthSession> mDBApi;
	private AccessTokenPair dropboxTokens = null;
	final static private String APP_KEY = "tjyi4o6psg0dm0r";
	final static private String APP_SECRET = "jp6054yixb9t9e0";
	final static private AccessType ACCESS_TYPE = AccessType.APP_FOLDER;
	final static private String ACCOUNT_PREFS_NAME = "prefs";
	final static private String ACCESS_KEY_NAME = "ACCESS_KEY";
	final static private String ACCESS_SECRET_NAME = "ACCESS_SECRET";
	private boolean uploadFileRequested = false;

	// Various UI stuff
	public static boolean currentlyVisible;
	private boolean logging = false;
	private TextView mDataField;
	private TextView mR2RField;
	private TextView mRmsField;
	private String mDeviceName;
	private String mDeviceAddress;
	private ImageButton mButtonStart;
	private ImageButton mButtonStop;
	private ImageButton mButtonSend;

	// Chart stuff
	private GraphicalView mChart;
	private XYMultipleSeriesDataset mDataset = new XYMultipleSeriesDataset();
	private XYMultipleSeriesRenderer mRenderer = new XYMultipleSeriesRenderer();
	private XYSeries mCurrentSeries;
	private XYSeriesRenderer mCurrentRenderer;

	private void initChart() {

		Log.i(TAG, "initChart");
		if (mCurrentSeries == null) {
			mCurrentSeries = new XYSeries("Heart Rate");
			mDataset.addSeries(mCurrentSeries);
			Log.i(TAG, "initChart mCurrentSeries == null");
		}

		if (mCurrentRenderer == null) {
			mCurrentRenderer = new XYSeriesRenderer();
			mCurrentRenderer.setLineWidth(4);

			mCurrentRenderer.setPointStyle(PointStyle.CIRCLE);
			mCurrentRenderer.setFillPoints(true);
			mCurrentRenderer.setColor(Color.GREEN);
			Log.i(TAG, "initChart mCurrentRenderer == null");

			mRenderer.setAxisTitleTextSize(70);
			mRenderer.setPointSize(5);
			mRenderer.setYTitle("Time");
			mRenderer.setYTitle("Heart rate");
			mRenderer.setPanEnabled(true);
			mRenderer.setLabelsTextSize(50);
			mRenderer.setLegendTextSize(50);

			mRenderer.setYAxisMin(0);
			mRenderer.setYAxisMax(120);
			mRenderer.setXAxisMin(0);
			mRenderer.setXAxisMax(100);

			mRenderer.setShowLegend(false);

			mRenderer.setApplyBackgroundColor(true);
			mRenderer.setBackgroundColor(Color.BLACK);
			mRenderer.setMarginsColor(Color.BLACK);

			mRenderer.setShowGridY(true);
			mRenderer.setShowGridX(true);
			mRenderer.setGridColor(Color.WHITE);
			// mRenderer.setShowCustomTextGrid(true);

			mRenderer.setAntialiasing(true);
			mRenderer.setPanEnabled(true, false);
			mRenderer.setZoomEnabled(true, false);
			mRenderer.setZoomButtonsVisible(false);
			mRenderer.setXLabelsColor(Color.WHITE);
			mRenderer.setYLabelsColor(0, Color.WHITE);
			mRenderer.setXLabelsAlign(Align.CENTER);
			mRenderer.setXLabelsPadding(10);
			mRenderer.setXLabelsAngle(-30.0f);
			mRenderer.setYLabelsAlign(Align.RIGHT);
			mRenderer.setPointSize(3);
			mRenderer.setInScroll(true);
			// mRenderer.setShowLegend(false);
			mRenderer.setMargins(new int[] { 50, 150, 10, 50 });

			mRenderer.addSeriesRenderer(mCurrentRenderer);
		}
	}

	// Code to manage Service lifecycle.
	private final ServiceConnection mServiceConnection = new ServiceConnection() {

		@Override
		public void onServiceConnected(ComponentName componentName,
				IBinder service) {
			mBluetoothLeService = ((BluetoothLeService.LocalBinder) service)
					.getService();
			if (!mBluetoothLeService.initialize()) {
				Log.e(TAG, "Unable to initialize Bluetooth");
				finish();
			}
			// Automatically connects to the device upon successful start-up
			// initialization.
			mBluetoothLeService.connect(mDeviceAddress);
		}

		@Override
		public void onServiceDisconnected(ComponentName componentName) {
			mBluetoothLeService = null;
		}
	};

	// Handles various events fired by the Service.
	// ACTION_GATT_CONNECTED: connected to a GATT server.
	// ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
	// ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
	// ACTION_DATA_AVAILABLE: received data from the device. This can be a
	// result of read
	// or notification operations.
	private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			final String action = intent.getAction();
			if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
				mConnected = true;
				updateConnectionState(true);
				invalidateOptionsMenu();
			} else if (BluetoothLeService.ACTION_GATT_DISCONNECTED
					.equals(action)) {
				mConnected = false;
				updateConnectionState(false);
				invalidateOptionsMenu();
				clearUI();
			} else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED
					.equals(action)) {
				// Show all the supported services and characteristics on the
				// user interface.
				displayGattServices(mBluetoothLeService
						.getSupportedGattServices());
				// mButtonStop.setVisibility(View.VISIBLE);
			} else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
				displayData(intent
						.getIntegerArrayListExtra(BluetoothLeService.EXTRA_DATA));
			}
		}
	};

	private void clearUI() {
		// mGattServicesList.setAdapter((SimpleExpandableListAdapter) null);
		mDataField.setText(R.string.no_data);
		mR2RField.setText(R.string.no_data);
		mRmsField.setText(R.string.no_data);
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		Log.i(TAG, "onCreate");

		setContentView(R.layout.heartrate);

		// getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);

		final Intent intent = getIntent();
		mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
		mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);

		// Set up database connection
        datasource = new EventsDataSource(this);
        datasource.open();
        
        // Create a list to store r2r values
        mR2RSeries = new ArrayList<Integer>();
        
		// We create a new AuthSession so that we can use the Dropbox API.
		AndroidAuthSession session = buildSession();
		mDBApi = new DropboxAPI<AndroidAuthSession>(session);

		mDataField = (TextView) findViewById(R.id.data_value);
		mR2RField = (TextView) findViewById(R.id.r2r_value);
		mRmsField = (TextView) findViewById(R.id.rms_value);
		
		mButtonSend = (ImageButton) findViewById(R.id.btnSend);
		mButtonSend.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				// emailLog();
				if (dropboxTokens == null) {
					mDBApi.getSession().startAuthentication(
							DeviceControlActivity.this);
					uploadFileRequested = true;
				} else {
					File file = new File(Environment
							.getExternalStorageDirectory().getPath()
							+ "/hrmlog.csv");
					UploadFile upload = new UploadFile(
							DeviceControlActivity.this, mDBApi, "/", file);
					upload.execute();
				}
			}
		});

		mButtonStart = (ImageButton) findViewById(R.id.btnStart);
		mButtonStart.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				startLogging();
			}
		});

		mButtonStop = (ImageButton) findViewById(R.id.btnStop);
		mButtonStop.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				stopLogging();
			}
		});

		getActionBar().setTitle(mDeviceName);
		getActionBar().setDisplayHomeAsUpEnabled(true);
		Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
		// TODO: Lars added this
		this.startService(gattServiceIntent);
		bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

		LinearLayout layout = (LinearLayout) findViewById(R.id.chart);
		if (mChart == null) {
			initChart();
			mChart = ChartFactory.getTimeChartView(this, mDataset, mRenderer,
					"hh:mm");
			layout.addView(mChart);
		} else {
			mChart.repaint();
		}
		
		new HTTPLifxAsyncTask(64, 64, 64, 10).execute();

		//Spotify authentication
		AuthenticationRequest.Builder builder =
				new AuthenticationRequest.Builder(CLIENT_ID, AuthenticationResponse.Type.TOKEN, REDIRECT_URI);
		builder.setScopes(new String[]{"user-read-private", "streaming"});
		AuthenticationRequest request = builder.build();

		AuthenticationClient.openLoginActivity(this, REQUEST_CODE, request);
	}

	@Override
	protected void onResume() {
		super.onResume();
		currentlyVisible = true;
		
		registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
		if (mBluetoothLeService != null) {
			final boolean result = mBluetoothLeService.connect(mDeviceAddress);
			Log.d(TAG, "Connect request result=" + result);
		}

		if (mDBApi.getSession().authenticationSuccessful()) {
			try {
				// Mandatory call to complete the auth
				mDBApi.getSession().finishAuthentication();

				// Store it locally in our app for later use
				dropboxTokens = mDBApi.getSession().getAccessTokenPair();
				storeKeys(dropboxTokens.key, dropboxTokens.secret);

				if (uploadFileRequested) {
					dropboxUpload();
				}

			} catch (IllegalStateException e) {
				// showToast("Couldn't authenticate with Dropbox:" +
				// e.getLocalizedMessage());
				Log.i(TAG, "Error authenticating", e);
			}
		}
	}

	// this is called when the screen rotates.
	// (onCreate is no longer called when screen rotates due to manifest, see:
	// android:configChanges)
	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		// setContentView(R.layout.heartrate);
		if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
			Log.i(TAG, "ORIENTATION_LANDSCAPE");
		} else {
			Log.i(TAG, "ORIENTATION_PORTRAIT");
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		currentlyVisible = false;
		// unregisterReceiver(mGattUpdateReceiver);
	}

	@Override
	protected void onDestroy() {
		Spotify.destroyPlayer(this);
		super.onDestroy();
		currentlyVisible = false;
		unbindService(mServiceConnection);
		// mBluetoothLeService = null;
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
		super.onActivityResult(requestCode, resultCode, intent);

		// Check if result comes from the correct activity
		if (requestCode == REQUEST_CODE) {
			AuthenticationResponse response = AuthenticationClient.getResponse(resultCode, intent);
			if (response.getType() == AuthenticationResponse.Type.TOKEN) {
				Config playerConfig = new Config(this, response.getAccessToken(), CLIENT_ID);
				Log.i(TAG, "TOKEN: " + response.getAccessToken());
				mPlayer = Spotify.getPlayer(playerConfig, this, new Player.InitializationObserver() {
					@Override
					public void onInitialized(Player player) {
						mPlayer.addConnectionStateCallback(DeviceControlActivity.this);
						mPlayer.addPlayerNotificationCallback(DeviceControlActivity.this);
					}

					@Override
					public void onError(Throwable throwable) {
						Log.e(TAG, "Could not initialize player: " + throwable.getMessage());
					}
				});
			}
		}
	}

	@Override
	public void onLoggedIn() {
		Log.d(TAG, "User logged in");
	}

	@Override
	public void onLoggedOut() {
		Log.d(TAG, "User logged out");
	}

	@Override
	public void onLoginFailed(Throwable error) {
		Log.d(TAG, "Login failed");
	}

	@Override
	public void onTemporaryError() {
		Log.d(TAG, "Temporary error occurred");
	}

	@Override
	public void onConnectionMessage(String message) {
		Log.d(TAG, "Received connection message: " + message);
	}

	@Override
	public void onPlaybackEvent(EventType eventType, PlayerState playerState) {
		Log.d(TAG, "Playback event received: " + eventType.name());
		switch (eventType) {
			// Handle event type as necessary
			default:
				break;
		}
	}

	@Override
	public void onPlaybackError(ErrorType errorType, String errorDetails) {
		Log.d(TAG, "Playback error received: " + errorType.name());
		switch (errorType) {
			// Handle error type as necessary
			default:
				break;
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.gatt_services, menu);
		if (mConnected) {
			menu.findItem(R.id.menu_connect).setVisible(false);
			menu.findItem(R.id.menu_disconnect).setVisible(true);
			if (logging) {
				menu.findItem(R.id.menu_start_logging).setVisible(false);
				menu.findItem(R.id.menu_stop_logging).setVisible(true);
				menu.findItem(R.id.menu_dropbox).setVisible(true);
				mButtonStart.setVisibility(View.GONE);
				mButtonStop.setVisibility(View.VISIBLE);
			} else {
				menu.findItem(R.id.menu_start_logging).setVisible(true);
				menu.findItem(R.id.menu_stop_logging).setVisible(false);
				mButtonStart.setVisibility(View.VISIBLE);
				mButtonStop.setVisibility(View.GONE);
			}
		} else {
			menu.findItem(R.id.menu_connect).setVisible(true);
			menu.findItem(R.id.menu_disconnect).setVisible(false);
			menu.findItem(R.id.menu_start_logging).setVisible(false);
			menu.findItem(R.id.menu_stop_logging).setVisible(false);
			menu.findItem(R.id.menu_dropbox).setVisible(false);
			mButtonStart.setVisibility(View.GONE);
			mButtonStop.setVisibility(View.GONE);
		}

		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menu_connect:
			mBluetoothLeService.connect(mDeviceAddress);
			return true;
		case R.id.menu_disconnect:
			mBluetoothLeService.disconnect();
			return true;
		case R.id.menu_dropbox:
			dropboxUpload();
			return true;
		case R.id.menu_start_logging:
			startLogging();
			return true;
		case R.id.menu_stop_logging:
			stopLogging();
			return true;
		case android.R.id.home:
			onBackPressed();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void updateConnectionState(final boolean connected) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				if (connected) {
					mButtonStart.setVisibility(View.VISIBLE);
					mButtonStop.setVisibility(View.GONE);
				} else {
					mButtonStart.setVisibility(View.GONE);
					mButtonStop.setVisibility(View.GONE);
				}
			}
		});
	}

	int x = 0;

	private void displayData(ArrayList<Integer> data) {
		try {
			if (data != null) {

				long time = (new Date()).getTime();
				//int dataElement = Integer.parseInt(data);
				int dataElement = data.get(0);
				mCurrentSeries.add(time, dataElement);
				appendLog((new Date()).toString() + "," + dataElement);
				//datasource.createEvent(1, time, dataElement);
				// Storing last 600 only - should average... 
				while (mCurrentSeries.getItemCount() > 60*10) {
					mCurrentSeries.remove(0);
				}
				
				if (currentlyVisible) {
					mDataField.setText("Pulse: " + dataElement);
					if(data.size() > 1) {
						int r2rValue = (int)((data.get(1) / 1024.0) * 1000 + 0.5);
						mR2RField.setText("R2R: " + r2rValue);
						mR2RSeries.add(Integer.valueOf(r2rValue));
						
						// Calculate root mean squares
						if(mR2RSeries.size() > 2) {
							double aggVal = 0;
							int preVal = mR2RSeries.get(0);
							for(int i = 1; i < mR2RSeries.size(); i++) {
								int nextVal = mR2RSeries.get(i);
								aggVal += (nextVal - preVal) * (nextVal - preVal);
								preVal = nextVal;
							}
							Log.i(TAG, "(" + mR2RSeries.size() + ", " + r2rValue + ") -> " + aggVal);
							aggVal /= (mR2RSeries.size() - 1);
							aggVal = Math.sqrt(aggVal);
							mRmsField.setText("RMS: " + String.format("%.2f", aggVal));
							
							if(aggVal < 40 && !isStress && mCntAfterTrigger >= 40 && mR2RSeries.size() > MAX_R2R_SERIES_SIZE/2) {
								new HTTPLifxAsyncTask(0, 64, 0, 10).execute();
								mCntAfterTrigger = 0;
								isStress = true;
								mPlayer.play("spotify:user:1222611202:playlist:74YxeWm2dyIr65pufsJEND");
							} else if (aggVal >= 40 && isStress && mCntAfterTrigger >= 40 && mR2RSeries.size() > MAX_R2R_SERIES_SIZE/2){
								new HTTPLifxAsyncTask(64, 64, 64, 10).execute();
								mCntAfterTrigger = 0;
								isStress = false;
								mPlayer.pause();
							}
							
							mCntAfterTrigger++;
							
						}
						
						if(mR2RSeries.size() > MAX_R2R_SERIES_SIZE) {
							mR2RSeries.remove(0);
						}
						
//						if(mR2RSeries.size() > 2 * MAX_R2R_SERIES_SIZE) {
//							mR2RSeries.subList(0, MAX_R2R_SERIES_SIZE).clear();
//						}
					}
					
					mRenderer.setYAxisMin(0);
					mRenderer.setYAxisMax(mCurrentSeries.getMaxY() + 20);

					double minx = mCurrentSeries.getMinX();
					double maxx = mCurrentSeries.getMaxX();

					if ((maxx - minx) < 5 * 60 * 1000) {
						mRenderer.setXAxisMin(minx);
						mRenderer.setXAxisMax(minx + (5 * 60 * 1000));
					} else {
						mRenderer.setXAxisMin(maxx - (5 * 60 * 1000));
						mRenderer.setXAxisMax(maxx);
					}

					mChart.repaint();
					mChart.zoomReset();
				} 
			}
		} catch (Exception e) {
			Log.e(TAG, "Exception while parsing: " + data.get(0));
		}
	}

	// Demonstrates how to iterate through the supported GATT
	// Services/Characteristics.
	// In this sample, we populate the data structure that is bound to the
	// ExpandableListView
	// on the UI.
	private void displayGattServices(List<BluetoothGattService> gattServices) {
		if (gattServices == null)
			return;
		String uuid = null;
		String unknownServiceString = getResources().getString(
				R.string.unknown_service);
		String unknownCharaString = getResources().getString(
				R.string.unknown_characteristic);
		ArrayList<HashMap<String, String>> gattServiceData = new ArrayList<HashMap<String, String>>();
		ArrayList<ArrayList<HashMap<String, String>>> gattCharacteristicData = new ArrayList<ArrayList<HashMap<String, String>>>();
		mGattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

		// Loops through available GATT Services.
		for (BluetoothGattService gattService : gattServices) {
			HashMap<String, String> currentServiceData = new HashMap<String, String>();
			uuid = gattService.getUuid().toString();
			currentServiceData.put(LIST_NAME,
					SampleGattAttributes.lookup(uuid, unknownServiceString));
			currentServiceData.put(LIST_UUID, uuid);
			gattServiceData.add(currentServiceData);

			ArrayList<HashMap<String, String>> gattCharacteristicGroupData = new ArrayList<HashMap<String, String>>();
			List<BluetoothGattCharacteristic> gattCharacteristics = gattService
					.getCharacteristics();
			ArrayList<BluetoothGattCharacteristic> charas = new ArrayList<BluetoothGattCharacteristic>();

			// Loops through available Characteristics.
			for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {

				if (UUID.fromString(SampleGattAttributes.HEART_RATE_MEASUREMENT)
						.equals(gattCharacteristic.getUuid())) {
					Log.d(TAG, "Found heart rate");
					mNotifyCharacteristic = gattCharacteristic;
				}

				charas.add(gattCharacteristic);
				HashMap<String, String> currentCharaData = new HashMap<String, String>();
				uuid = gattCharacteristic.getUuid().toString();
				currentCharaData.put(LIST_NAME,
						SampleGattAttributes.lookup(uuid, unknownCharaString));
				currentCharaData.put(LIST_UUID, uuid);
				gattCharacteristicGroupData.add(currentCharaData);
			}
			mGattCharacteristics.add(charas);
			gattCharacteristicData.add(gattCharacteristicGroupData);
		}

	}

	private static IntentFilter makeGattUpdateIntentFilter() {
		final IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
		intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
		intentFilter
				.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
		intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
		return intentFilter;
	}

	private String[] getKeys() {
		SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
		String key = prefs.getString(ACCESS_KEY_NAME, null);
		String secret = prefs.getString(ACCESS_SECRET_NAME, null);
		if (key != null && secret != null) {
			String[] ret = new String[2];
			ret[0] = key;
			ret[1] = secret;
			return ret;
		} else {
			return null;
		}
	}

	private void storeKeys(String key, String secret) {
		// Save the access key for later
		SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
		Editor edit = prefs.edit();
		edit.putString(ACCESS_KEY_NAME, key);
		edit.putString(ACCESS_SECRET_NAME, secret);
		edit.commit();
	}

	private AndroidAuthSession buildSession() {
		AppKeyPair appKeyPair = new AppKeyPair(APP_KEY, APP_SECRET);
		AndroidAuthSession session;

		String[] stored = getKeys();
		if (stored != null) {
			AccessTokenPair accessToken = new AccessTokenPair(stored[0],
					stored[1]);
			session = new AndroidAuthSession(appKeyPair, ACCESS_TYPE,
					accessToken);
		} else {
			session = new AndroidAuthSession(appKeyPair, ACCESS_TYPE);
		}

		return session;
	}

	private void dropboxUpload() {
		File file = new File(Environment.getExternalStorageDirectory()
				.getPath() + "/hrmlog.csv");
		UploadFile upload = new UploadFile(DeviceControlActivity.this, mDBApi,
				"/", file);
		upload.execute();
		uploadFileRequested = false;
	}

	public void appendLog(String text) {
		File logFile = new File(Environment.getExternalStorageDirectory()
				.getPath() + "/hrmlog.csv");
		if (!logFile.exists()) {
			try {
				logFile.createNewFile();
			} catch (IOException e) {
				Log.e(TAG, "Error while creating file. ", e);
				e.printStackTrace();
			}
		}
		try {
			// BufferedWriter for performance, true to set append to file flag
			BufferedWriter buf = new BufferedWriter(new FileWriter(logFile,
					true));
			buf.append(text);
			buf.newLine();
			buf.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void startLogging() {
		mButtonSend.setVisibility(View.VISIBLE);
		mButtonStop.setVisibility(View.VISIBLE);
		mButtonStart.setVisibility(View.GONE);
		mBluetoothLeService.setCharacteristicNotification(
				mNotifyCharacteristic, true);
		invalidateOptionsMenu();
		logging = true;
	}

	private void stopLogging() {
		mButtonStop.setVisibility(View.GONE);
		mButtonStart.setVisibility(View.VISIBLE);
		mBluetoothLeService.setCharacteristicNotification(
				mNotifyCharacteristic, false);
		invalidateOptionsMenu();
		logging = false;
	}
	
	class HTTPLifxAsyncTask extends AsyncTask<Void, Void, Void> {

		private String endPoint = "https://api.lifx.com/v1beta1/lights/all/color?";
		private String authToken = System.getProperty("LIFX_AUTH_TOKEN");
		
		private String query;
		
		public HTTPLifxAsyncTask(int red, int green, int blue, float duration) {
			red &= 0xff;
			green &= 0xff;
			blue &= 0xff;
			
			StringBuilder sb = new StringBuilder();
			sb.append("color=");
			sb.append("rgb:" + String.format("%d,%d,%d", red, green, blue));
			sb.append("&");
			sb.append("duration="+duration);
			query = sb.toString();
		}
		
		@Override
		protected Void doInBackground(Void... params) {
			try {
				URL url = new URL(endPoint + query);
				HttpURLConnection con = (HttpURLConnection) url.openConnection();
				Log.i(TAG, "URL: " + endPoint + query);
				
				con.setRequestMethod("PUT");
				con.setRequestProperty("Authorization", "Bearer " + authToken);
				con.setConnectTimeout(5000);
				
				if(con.getResponseCode() != HttpURLConnection.HTTP_OK) {
					Log.e(TAG, con.getResponseCode() + ": " + con.getResponseMessage());
				}
				
				String response = readStream(con.getInputStream());
				Log.i(TAG, "Response: " + response);
			} catch (MalformedURLException e) {
				Log.e(TAG, e.getMessage());
			} catch (IOException e) {
				Log.e(TAG, e.getMessage());
			}
			
			
			return null;
		}
		
		public String readStream(InputStream is) throws IOException {
			BufferedReader in = new BufferedReader(
				new InputStreamReader(is)
			);
			String line;
			StringBuffer sb = new StringBuffer();
			while ((line = in.readLine()) != null) {
				sb.append(line);
			}
			return sb.toString();		
		}
		
	}
}
