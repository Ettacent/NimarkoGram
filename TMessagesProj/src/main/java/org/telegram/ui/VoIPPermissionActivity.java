/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.voip.VoIPPreNotificationService;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.ui.Components.voip.VoIPHelper;

import java.util.ArrayList;

@TargetApi(23)
public class VoIPPermissionActivity extends Activity {
	private static final String STATE_OWNER = "voip_permission_owner";
	private static final String STATE_REQUEST_PENDING = "voip_permission_pending";
	private Intent callOwner;
	private boolean permissionRequestPending;
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		callOwner = savedInstanceState == null ? VoIPPreNotificationService.capturePermissionOwner(getIntent())
				: savedInstanceState.getParcelable(STATE_OWNER);
		if (!VoIPPreNotificationService.isCurrentCallAction(callOwner)) {
			finish();
			return;
		}
		permissionRequestPending = savedInstanceState != null && savedInstanceState.getBoolean(STATE_REQUEST_PENDING);
		if (permissionRequestPending) return;
		final boolean isVideoCall = VoIPPreNotificationService.isVideo(callOwner);

		ArrayList<String> permissions = new ArrayList<>();
		if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
			permissions.add(Manifest.permission.RECORD_AUDIO);
		}
		if (isVideoCall && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
			permissions.add(Manifest.permission.CAMERA);
		}
		if (!permissions.isEmpty()) {
			try {
				permissionRequestPending = true;
				requestPermissions(permissions.toArray(new String[0]), isVideoCall ? 102 : 101);
			} catch (Exception e) {
				permissionRequestPending = false;
				FileLog.e(e);
				finish();
			}
		} else {
			completePermissions(true, false, isVideoCall ? 102 : 101);
		}
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		outState.putParcelable(STATE_OWNER, callOwner == null ? null : new Intent(callOwner));
		outState.putBoolean(STATE_REQUEST_PENDING, permissionRequestPending);
		super.onSaveInstanceState(outState);
	}

	private void completePermissions(boolean granted, boolean permanentlyDenied, int requestCode) {
		if (!VoIPPreNotificationService.isCurrentCallAction(callOwner)) {
			finish();
			return;
		}
		if (granted) {
			final boolean accepted = VoIPPreNotificationService.answer(this, callOwner);
			finish();
			if (accepted && VoIPPreNotificationService.isCurrentCallAction(callOwner)) {
				startActivity(new Intent(callOwner).setClass(this, LaunchActivity.class).setAction("voip").setFlags(0));
			}
		} else if (permanentlyDenied) {
			if (VoIPPreNotificationService.decline(this, VoIPService.DISCARD_REASON_HANGUP, callOwner)) {
				VoIPHelper.permissionDenied(this, this::finish, requestCode);
			} else {
				finish();
			}
		} else {
			finish();
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		if (requestCode == 101 || requestCode == 102) {
			if (!permissionRequestPending) return;
			permissionRequestPending = false;
			if (grantResults.length == 0 || permissions.length != grantResults.length) {
				finish();
				return;
			}
			boolean allGranted = true;
			boolean permanentlyDenied = false;
			for (int a = 0; a < grantResults.length; a++) {
				if (grantResults[a] != PackageManager.PERMISSION_GRANTED) {
					allGranted = false;
					permanentlyDenied |= !shouldShowRequestPermissionRationale(permissions[a]);
				}
			}
			completePermissions(allGranted, permanentlyDenied, requestCode);
				}
			}
		}
