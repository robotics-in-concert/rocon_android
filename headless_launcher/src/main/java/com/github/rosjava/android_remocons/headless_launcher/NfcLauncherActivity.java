package com.github.rosjava.android_remocons.headless_launcher;


import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;
import android.widget.Toast;

import com.github.rosjava.android_apps.application_management.ConcertDescription;
import com.github.rosjava.android_apps.application_management.MasterId;
import com.github.rosjava.android_remocons.common_tools.AppsManager;
import com.github.rosjava.android_remocons.common_tools.Util;
import com.github.rosjava.android_remocons.common_tools.NfcManager;
import com.github.rosjava.android_remocons.common_tools.WifiChecker;
import com.github.rosjava.android_remocons.common_tools.ConcertChecker;

import org.ros.exception.RemoteException;
import org.ros.internal.message.DefaultMessageFactory;
import org.ros.internal.message.definition.MessageDefinitionReflectionProvider;
import org.ros.node.service.ServiceResponseListener;

import concert_msgs.RequestInteractionResponse;

import static com.github.rosjava.android_remocons.common_tools.RoconConstants.*;

/**
 * @author jorge@yujinrobot.com (Jorge Santos Simon)
 */
public class NfcLauncherActivity extends Activity {

    private enum Step {
        STARTUP,
        CONNECT_TO_SSID,
        VALIDATE_CONCERT,
        GET_NFC_APP_INFO,
        REQUEST_PERMIT,
        LAUNCH_APP,
        ABORT_LAUNCH;

        public Step next() {
            return this.ordinal() < Step.values().length - 1
                    ? Step.values()[this.ordinal() + 1]
                    : null;
        }
    }
    private Step   launchStep = Step.STARTUP;
    private String ssid;
    private String password;
    private String masterHost;
    private short  masterPort;
    private short  nfcAppId;
    private short  extraData;
    private MasterId masterId;
    private Vibrator vibrator;
    private ConcertDescription concert;

    /** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
	    super.onCreate(savedInstanceState);
	    //setContentView(R.layout.nfc_reader);

//	    wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
//	    wifiFilter = new IntentFilter();
//	    wifiFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);

        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            vibrator.vibrate(500);
            toast(getString(R.string.app_name) + " started", Toast.LENGTH_LONG);

            Intent intent = getIntent();
            String action = intent.getAction();
            Log.d("NfcLaunch", action + " action started");

            NfcManager nfcManager = new NfcManager(this);
            nfcManager.onNewIntent(intent);

            if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action) == false)
            {
                // This should not append unless we are debugging
                throw new Exception("Not started by NDEF_DISCOVERED action; this activity is only intended to run that way");
            }

            //** Step 1. Parsing NFC Data
            ByteBuffer bb = ByteBuffer.wrap(nfcManager.getPayload());

            byte[] payload = nfcManager.getPayload();
            if (payload.length != NFC_PAYLOAD_LENGTH + 3) // 1 byte for status and 2 lang bytes
            {
                throw new Exception("Payload doesn't match expected length: "
                            + payload.length +" != " + NFC_PAYLOAD_LENGTH);
            }

            int offset = 3; // skip 1 byte for status and 2 lang bytes
            ssid       = Util.toString(payload, offset, NFC_SSID_FIELD_LENGTH).trim();
            offset    += NFC_SSID_FIELD_LENGTH;
            password   = Util.toString(payload, offset, NFC_PASSWORD_FIELD_LENGTH).trim();
            offset    += NFC_PASSWORD_FIELD_LENGTH;
            masterHost = Util.toString(payload, offset, NFC_MASTER_HOST_FIELD_LENGTH).trim();
            offset    += NFC_MASTER_HOST_FIELD_LENGTH;
            masterPort = Util.toShort(payload, offset, NFC_MASTER_PORT_FIELD_LENGTH);
            offset    += NFC_MASTER_PORT_FIELD_LENGTH;
            nfcAppId   = Util.toShort(payload, offset, NFC_NFC_APP_ID_FIELD_LENGTH);
            offset    += NFC_NFC_APP_ID_FIELD_LENGTH;
            extraData  = Util.toShort(payload, offset, NFC_EXTRA_DATA_FIELD_LENGTH);

            launchStep = launchStep.next();

            //** Step 2. Connect to SSID
            String masterUri  = "http://" + masterHost + ":" + masterPort;
            String controlUri = masterUri; // not needed
            String encryption = "WPA2";    // not needed
            masterId = new MasterId(masterUri, controlUri, ssid, encryption, password);

            final WifiChecker wc = new WifiChecker(
                new WifiChecker.SuccessHandler() {
                    public void handleSuccess() {
//                        Log.i("NfcLaunch", "Connected to " + ssid);
//                        toast("Connected to " + ssid, Toast.LENGTH_LONG);
                        launchStep = launchStep.next();
                    }
                },
                new WifiChecker.FailureHandler() {
                    public void handleFailure(String reason) {
//                        Log.e("NfcLaunch", "Cannot connect to " + ssid + ". Aborting app launch");
//                        toast("Cannot connect to " + ssid, Toast.LENGTH_LONG);
//                        toast("Aborting application launch", Toast.LENGTH_LONG);
//                        finish();
                        launchStep = Step.ABORT_LAUNCH;
                    }
                },
                new WifiChecker.ReconnectionHandler() {
                    public boolean doReconnection(String from, String to) {
//                        if (from == null) {
//                            wifiDialog.setMessage("To use this concert, you must connect to a wifi network. You are currently not connected to a wifi network. Would you like to connect to the correct wireless network?");
//                        } else {
//                            wifiDialog.setMessage("To use this concert, you must switch wifi networks. Do you want to switch from "
//                                    + from + " to " + to + "?");
//                        }
                        // TODO should I ask for permit? maybe it's a bit rude tu switch network without asking!
                        Log.i("NfcLaunch", "Switching from " + from + " to " + to);
                        toast("Switching from " + from + " to " + to, Toast.LENGTH_LONG);

                        return true;
                    }
                }
            );
            toast("Connecting to " + ssid + "...", Toast.LENGTH_LONG);
            wc.beginChecking(masterId, (WifiManager) getSystemService(WIFI_SERVICE));

            if (waitFor(Step.VALIDATE_CONCERT, 15) == false) {
                throw new Exception("Cannot connect to " + ssid + ". Aborting app launch");
            }
            Log.i("NfcLaunch", "Connected to " + ssid);
            toast("Connected to " + ssid, Toast.LENGTH_LONG);

	    	//** Step 3. Validate the concert: check for specific topics on masterUri
            final ConcertChecker cc = new ConcertChecker(
                new ConcertChecker.ConcertDescriptionReceiver() {
                    public void receive(ConcertDescription concertDescription) {
                        concert = concertDescription;
                        if ( concert.getConnectionStatus() == ConcertDescription.UNAVAILABLE ) {
                            // Check that it's not busy
                            Log.e("NfcLaunch", "Concert is unavailable: busy serving another remote controller");
                            launchStep = Step.ABORT_LAUNCH;
                        } else {
                            launchStep = launchStep.next();
                        }
                    }
                },
                new ConcertChecker.FailureHandler() {
                    public void handleFailure(String reason) {
                        Log.e("NfcLaunch", "Cannot contact ROS master: " + reason);
                        launchStep = Step.ABORT_LAUNCH;
                    }
                }
            );
            toast("Validating " + masterId.getMasterUri() + "...", Toast.LENGTH_LONG);
            cc.beginChecking(masterId);

            if (waitFor(Step.GET_NFC_APP_INFO, 10) == false) {
                throw new Exception("Cannot connect to " + masterId.getMasterUri() + ". Aborting app launch");
            }
            Log.i("NfcLaunch", "Concert " + masterId.getMasterUri() + " up and running");
            toast("Concert " + masterId.getMasterUri() + " up and running", Toast.LENGTH_LONG);

            //** Step 4. Retrieve app basic info given its NFC app id
            //concert_msgs.GetAppInfo appInfo;  request.setAppId(nfcAppId);
MessageDefinitionReflectionProvider messageDefinitionProvider = new MessageDefinitionReflectionProvider();
DefaultMessageFactory messageFactory = new DefaultMessageFactory(messageDefinitionProvider);
concert_msgs.RemoconApp app = messageFactory.newFromType(concert_msgs.RemoconApp._TYPE);
app.setName("http://chimek.yujinrobot.com/dorothy/dorothy_web_menu.html");
app.setParameters("{'masterip':'192.168.10.233','bridgeport':9090,'tableid':3}");
app.setServiceName("cybernetic_piracy");
app.setDisplayName("Cafe Dorothy");

String appURI  = "http://chimek.yujinrobot.com/dorothy/dorothy_web_menu.html";
String role = "Pirate";
String appName = "Cafe Dorothy";
rocon_std_msgs.Remapping remaps;
String service = "cybernetic_piracy";
String params = "{'masterip':'192.168.10.233','bridgeport':9090,'tableid':3}";
launchStep = launchStep.next();

            if (waitFor(Step.REQUEST_PERMIT, 10) == false) {
                throw new Exception("Cannot get app info for id " + nfcAppId + ". Aborting app launch");
            }
            Log.i("NfcLaunch", app.getDisplayName() + " configuration received from concert");
            toast(app.getDisplayName() + " configuration received from concert", Toast.LENGTH_LONG);


            //** Step 5. Request permission to use the app
            AppsManager am = new AppsManager(new AppsManager.FailureHandler() {
                public void handleFailure(String reason) {
                    Log.e("NfcLaunch", "Cannot request app use: " + reason);
                    launchStep = Step.ABORT_LAUNCH;
                }
            });
            am.setupRequestService(new ServiceResponseListener<RequestInteractionResponse>() {
                @Override
                public void onSuccess(concert_msgs.RequestInteractionResponse response) {
                    if (response.getResult() == true) {
                        launchStep = launchStep.next();
                    }
                    else {
                        Log.i("NfcLaunch", "Concert deny app use. " + response.getMessage());
                        toast("Concert deny app use. " + response.getMessage(), Toast.LENGTH_LONG);
                        launchStep = Step.ABORT_LAUNCH;
                    }
                }

                @Override
                public void onFailure(RemoteException e) {
                    Log.e("NfcLaunch", "Request app use failed. " + e.getMessage());
                    launchStep = Step.ABORT_LAUNCH;
                }
            });
            am.requestAppUse(masterId, role, app);
            toast("Requesting permit to use " + app.getDisplayName() + "...", Toast.LENGTH_LONG);

            if (waitFor(Step.LAUNCH_APP, 10) == false) {
                throw new Exception("Cannot get permission to use " + app.getDisplayName() + ". Aborting app launch");
            }
            Log.i("NfcLaunch", app.getDisplayName() + " ready to launch!");
            toast(app.getDisplayName() + " ready to launch!", Toast.LENGTH_LONG);


            int kk = 0;

//	    	wifiManager.startScan();
//	    	configs = wifiManager.getConfiguredNetworks() ;

            //** Step 4. Try to connect to wireless network (ssid/password)
            // This step starts after completion of the wifi scan
        }
        catch (Exception e) {
            // TODO make and "error sound"
            Log.e("NfcLaunch", e.getMessage());
            toast(e.getMessage(), Toast.LENGTH_LONG);
            finish();
        }
	}

	@Override
	protected void onResume() {
		// TODO Auto-generated method stub
		super.onResume();
//		if(wifiFilter != null)
//    		registerReceiver(wifiEventReceiver, wifiFilter);
	}
	
	@Override
	protected void onPause() {
		// TODO Auto-generated method stub
		super.onPause();
//		if(wifiFilter != null)
//    		unregisterReceiver(wifiEventReceiver);
//
//		finish();
	}

    private boolean waitFor(final Step step, final int timeout) {
        AsyncTask<Void, Void, Boolean> asyncTask = new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... params) {
                while ((launchStep != step) && (launchStep != Step.ABORT_LAUNCH)) {
                    try { Thread.sleep(200); }
                    catch (InterruptedException e) { return false; }
                }
                return (launchStep == step); // returns false on ABORT_LAUNCH or InterruptedException
            }
        }.execute();
        try {
            return asyncTask.get(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e("NfcLaunch", "Async task interrupted. " + e.getMessage());
            return false;
        } catch (ExecutionException e) {
            Log.e("NfcLaunch", "Async task execution error. " + e.getMessage());
            return false;
        } catch (TimeoutException e) {
            Log.e("NfcLaunch", "Async task timeout (" + timeout + " s). " + e.getMessage());
            return false;
        }
    }
	private BroadcastReceiver wifiEventReceiver = new BroadcastReceiver() {

		@Override
		public void onReceive(Context context, Intent intent) {
			// TODO Auto-generated method stub

            int i = 3;
//			if(intent.getAction().equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
//				//** Step 4.
//				scanResults = wifiManager.getScanResults();
//				if(connecting == false) {
//					connecting = true ;
//					wifiConnect() ;
//
//					Intent rocon_intent = new Intent();///NfcLauncherActivity.this, RoconMainActivity.class);
////					rocon_intent.putExtra("table", nfc_table);
////					rocon_intent.putExtra("weblink", nfc_weblink);
////					rocon_intent.putExtra("masteruri", nfc_masteruri);
//
//					startActivity(rocon_intent);
//				}
//			}
		}
	};
		
	
//	private void wifiConnect()
//	{
//		Toast.makeText(this, "wifiConnect()", Toast.LENGTH_SHORT).show();
//
//		scanResults = wifiManager.getScanResults();
//		if(scanResults == null || scanResults.size() == 0) {
//			Toast.makeText(this, "No access point is found.", Toast.LENGTH_SHORT).show();
//			return ;
//		}
//
//		ScanResult foundResult = null ;
//		for(ScanResult result : scanResults){
//			if(ssid.equals(result.SSID)) {
//				foundResult = result ;
//				break ;
//			}
//		}
//
//		if( foundResult == null ) {
//			Toast.makeText(this, "Sorry!" + ssid + " is not found!", Toast.LENGTH_SHORT).show();
//			return ;
//		}
//
//		configs = wifiManager.getConfiguredNetworks() ;
//
//		for(WifiConfiguration config : configs){
//			if(("\"" + ssid + "\"").equals(config.SSID)) {
//				wifiManager.enableNetwork(config.networkId, true);
//				return ;
//			}
//		}
//
//		WifiConfiguration config = new WifiConfiguration() ;
//		config.SSID = "\"" + foundResult.SSID + "\"" ;
//		config.priority = 40 ;
//
//		if(foundResult.capabilities.contains("WPA")) {
//			config.status = WifiConfiguration.Status.ENABLED ;
//			config.preSharedKey = "\"" + password + "\"" ;
//		} else if(foundResult.capabilities.contains("WEB")){
//			config.wepKeys[0] = "\"" + password + "\"" ;
//			config.wepTxKeyIndex = 0 ;
//		} else {
//			config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
//		}
//
//		int newId = wifiManager.addNetwork(config);
//		if(newId < 0) {
//			Toast.makeText(this, "Sorry! Fail to add network " + ssid, Toast.LENGTH_SHORT).show();
//			return ;
//		}
//		else {
//			if(wifiManager.enableNetwork(newId, true)) {
//				Toast.makeText(this, "Trying to connect to " + config.SSID, Toast.LENGTH_SHORT).show();
//				wifiManager.saveConfiguration();
//			}
//			else {
//				Toast.makeText(this, "Sorry! Fail to connect to " + ssid, Toast.LENGTH_SHORT).show();
//			}
//		}
//	}

    private void toast(final String message, final int length) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(NfcLauncherActivity.this, message, length).show();
            }
        });
    }
}
