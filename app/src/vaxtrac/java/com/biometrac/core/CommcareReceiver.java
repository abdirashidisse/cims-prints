package com.biometrac.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

public class CommcareReceiver extends BroadcastReceiver {

	private final String TAG = "CommcareReceiver";
	
	@Override
	public void onReceive(Context context, Intent intent) {
		Log.i(TAG, "Received Commcare Broadcast");
        Log.i(TAG, "Ignoring");
	}

}
