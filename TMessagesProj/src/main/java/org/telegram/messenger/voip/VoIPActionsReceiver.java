/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.messenger.voip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Created by grishka on 28.07.17.
 */

public class VoIPActionsReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		if (intent == null) return;
		if (!intent.hasExtra("group_call_invite_msg_id")) {
			final String action = intent.getAction();
			final String packageName = context.getPackageName();
			if (!VoIPPreNotificationService.hasCallOwner(intent)) return;
			final VoIPService service = VoIPService.getSharedInstance();
			if (VoIPPreNotificationService.isCurrentServiceAction(service, intent)) {
				service.handleNotificationAction(intent);
				return;
			}
			if ((packageName + ".END_CALL").equals(action)) {
				VoIPPreNotificationService.decline(context, VoIPService.DISCARD_REASON_HANGUP, intent);
			} else if ((packageName + ".DECLINE_CALL").equals(action)) {
				VoIPPreNotificationService.decline(context, VoIPService.DISCARD_REASON_LINE_BUSY, intent);
			} else if ((packageName + ".ANSWER_CALL").equals(action)) {
				VoIPPreNotificationService.answer(context, intent);
			} else if ((packageName + ".HIDE_CALL").equals(action)) {
				VoIPPreNotificationService.dismiss(context, false, intent);
			}
			return;
		} else {
			final String packageName = context.getPackageName();
			if ((packageName + ".END_CALL").equals(intent.getAction())) {
					VoIPGroupNotification.decline(context, intent.getIntExtra("currentAccount", 0), intent.getIntExtra("group_call_invite_msg_id", 0));
			} else if ((packageName + ".DECLINE_CALL").equals(intent.getAction())) {
					VoIPGroupNotification.decline(context, intent.getIntExtra("currentAccount", 0), intent.getIntExtra("group_call_invite_msg_id", 0));
			} else if ((packageName + ".ANSWER_CALL").equals(intent.getAction())) {
					VoIPGroupNotification.answer(context, intent.getIntExtra("currentAccount", 0), intent.getIntExtra("group_call_invite_msg_id", 0));
			} else if ((packageName + ".HIDE_CALL").equals(intent.getAction())) {
					VoIPGroupNotification.hide(context, intent.getIntExtra("currentAccount", 0), intent.getIntExtra("group_call_invite_msg_id", 0));
				}
			}
		}
	}
