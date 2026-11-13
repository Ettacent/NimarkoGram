/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.messenger;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.RequiresApi;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.ExperimentalDigitalCredentialApi;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.GetDigitalCredentialOption;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.GetCredentialInterruptedException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.gms.tasks.CancellationToken;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.firebase.pnv.FirebasePhoneNumberVerification;
import com.google.firebase.pnv.VerificationSupportResult;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.web.BotWebViewContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public class FirebasePhoneNumberVerificationHelper {
    private static FirebasePhoneNumberVerification instance;

    private static FirebasePhoneNumberVerification getFpnvInstance() {
        if (instance == null) {
            instance = FirebasePhoneNumberVerification.getInstance();
        }
        return instance;
    }

    private FirebasePhoneNumberVerificationHelper() {

    }

    public static class SimInfo {
        public int slot;
        public String carrierName;
        public String phoneNumber;
    }

    public static SimInfo getSimInfo(Context context, VerificationSupportResult result) {
        final SimInfo info = new SimInfo();
        info.slot = result.getSimSlot();
        info.carrierName = emptyToNull(result.getCarrierId());
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) {
                return info;
            }
            final int subId = getSubscriptionId(context, info.slot);
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                tm = tm.createForSubscriptionId(subId);
            } else if (tm.getPhoneCount() > 1) {
                return info;
            }

            String carrierName = tm.getSimOperatorName();
            if (TextUtils.isEmpty(carrierName) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                final CharSequence idName = tm.getSimCarrierIdName();
                carrierName = idName == null ? null : idName.toString();
            }
            if (!TextUtils.isEmpty(carrierName)) {
                info.carrierName = carrierName;
            }

            info.phoneNumber = emptyToNull(readPhoneNumber(context, tm, subId));
        } catch (Exception e) {
            FileLog.e(e);
        }
        return info;
    }

    private static int getSubscriptionId(Context context, int slot) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                return SubscriptionManager.getSubscriptionId(slot);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return getSubscriptionIdQ(context, slot);
            } else if (hasPermission(context, Manifest.permission.READ_PHONE_STATE)) {
                final SubscriptionManager sm = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                final SubscriptionInfo subscriptionInfo = sm == null ? null : sm.getActiveSubscriptionInfoForSimSlotIndex(slot);
                if (subscriptionInfo != null) {
                    return subscriptionInfo.getSubscriptionId();
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static int getSubscriptionIdQ(Context context, int slot) {
        final SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
        final int[] ids = sm == null ? null : sm.getSubscriptionIds(slot);
        return ids != null && ids.length > 0 ? ids[0] : SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    private static String readPhoneNumber(Context context, TelephonyManager tm, int subId) {
        final boolean allowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? hasPermission(context, Manifest.permission.READ_PHONE_NUMBERS)
            : hasPermission(context, Manifest.permission.READ_PHONE_STATE);
        if (!allowed) {
            return null;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                final SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
                final String number = sm == null ? null : sm.getPhoneNumber(subId);
                if (!TextUtils.isEmpty(number)) {
                    return number;
                }
            }
            return tm.getLine1Number();
        } catch (SecurityException e) {
            return null;
        }
    }

    private static boolean hasPermission(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static String emptyToNull(String value) {
        return TextUtils.isEmpty(value) ? null : value;
    }

    public static void getVerificationSupportInfo(Utilities.Callback<List<VerificationSupportResult>> callback) {
        if (!BuildVars.SUPPORTS_FPNV) {
            callback.run(Collections.emptyList());
            return;
        }

        getFpnvInstance().getVerificationSupportInfo()
            .addOnSuccessListener(AndroidUtilities::runOnUIThread, results -> {
                final ArrayList<VerificationSupportResult> supportedResults = new ArrayList<>();
                for (VerificationSupportResult result : results) {
                    if (result.isSupported()) {
                        supportedResults.add(result);
                    }
                }
                callback.run(supportedResults);
            })
            .addOnFailureListener(AndroidUtilities::runOnUIThread, error -> {
                FileLog.e(error);
                callback.run(Collections.emptyList());
            });
    }

    @OptIn(markerClass = ExperimentalDigitalCredentialApi.class)
    public static Task<TLRPC.auth_Authorization> loginViaFirebasePhoneNumberVerification(
        Context context,
        int currentAccount,
        CancellationToken cancellationToken,
        @NonNull BooleanSupplier isCurrentAttempt,
        @Nullable Runnable onVerifying
    ) {
        final TaskCompletionSource<TLRPC.auth_Authorization> taskSource = new TaskCompletionSource<>(cancellationToken);
        final int[] requestIds = new int[2];
        final CancellationSignal cancellationSignal = new CancellationSignal();
        final Runnable cancel = () -> {
            for (int i = 0; i < requestIds.length; i++) {
                final int requestId = requestIds[i];
                requestIds[i] = 0;
                if (requestId != 0) {
                    ConnectionsManager.getInstance(currentAccount).cancelRequest(requestId, true);
                }
            }
            cancellationSignal.cancel();
        };
        cancellationToken.onCanceledRequested(() -> AndroidUtilities.runOnUIThread(cancel));
        final BooleanSupplier canContinue = () -> {
            if (cancellationToken.isCancellationRequested() || !isCurrentAttempt.getAsBoolean()) {
                cancel.run();
                taskSource.trySetException(new CancellationException());
                return false;
            }
            return !taskSource.getTask().isComplete();
        };
        if (!canContinue.getAsBoolean()) {
            return taskSource.getTask();
        }

        final CredentialManager credentialManager;
        try {
            credentialManager = CredentialManager.create(context);
        } catch (Exception e) {
            taskSource.trySetException(e);
            return taskSource.getTask();
        }

        final TLRPC.TL_auth_initFirebasePnvLogin req =
                new TLRPC.TL_auth_initFirebasePnvLogin();
        req.api_id = BuildVars.APP_ID;
        req.api_hash = BuildVars.APP_HASH;
        req.except_ids = UserConfig.getLoggedInAccountIds();

        requestIds[0] = ConnectionsManager.getInstance(currentAccount).sendRequestTyped(
            req,
            AndroidUtilities::runOnUIThread,
            (res, err) -> {
                if (!canContinue.getAsBoolean()) {
                    return;
                }

                if (res == null || err != null) {
                    taskSource.trySetException(new ConnectionsManager.RequestException(err));
                    return;
                }

                final String requestJson = wrapFirebasePnvPayload(
                    res.nonce,
                    res.digital_credential_payload
                );

                if (requestJson == null) {
                    taskSource.trySetException(
                        new Exception("Failed to create Firebase PNV payload")
                    );
                    return;
                }

                try {
                    final GetDigitalCredentialOption option =
                        new GetDigitalCredentialOption(requestJson);

                    final GetCredentialRequest credentialRequest =
                        new GetCredentialRequest.Builder()
                            .addCredentialOption(option)
                            .build();

                    if (!canContinue.getAsBoolean()) {
                        return;
                    }

                    credentialManager.getCredentialAsync(
                        context,
                        credentialRequest,
                        cancellationSignal,
                        AndroidUtilities::runOnUIThread,
                        new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                            @Override
                            public void onResult(GetCredentialResponse response) {
                                if (!canContinue.getAsBoolean()) {
                                    return;
                                }

                                final String googleToken;
                                try {
                                    final Credential credential = response.getCredential();
                                    final String responseJson = credential.getData().getString(
                                            "androidx.credentials.BUNDLE_KEY_REQUEST_JSON"
                                    );

                                    final JSONObject responseObject =
                                        new JSONObject(responseJson);

                                    googleToken = responseObject
                                        .getJSONObject("data")
                                        .getJSONObject("vp_token")
                                        .getJSONArray("firebase")
                                        .getString(0);
                                } catch (Exception e) {
                                    taskSource.trySetException(e);
                                    return;
                                }

                                if (onVerifying != null) {
                                    onVerifying.run();
                                }
                                if (!canContinue.getAsBoolean()) {
                                    return;
                                }

                                final TLRPC.TL_auth_finishFirebasePnvLogin finishRequest =
                                    new TLRPC.TL_auth_finishFirebasePnvLogin();
                                finishRequest.google_token = googleToken;

                                requestIds[1] =
                                    ConnectionsManager.getInstance(currentAccount)
                                        .sendRequestTyped(
                                            finishRequest,
                                            AndroidUtilities::runOnUIThread,
                                            (authorization, finishError) -> {
                                                if (!canContinue.getAsBoolean()) {
                                                    return;
                                                }

                                                if (authorization == null || finishError != null) {
                                                    taskSource.trySetException(
                                                        new ConnectionsManager.RequestException(finishError)
                                                    );
                                                    return;
                                                }

                                                taskSource.trySetResult(authorization);
                                            },
                                            ConnectionsManager.RequestFlagWithoutLogin
                                        );

                                canContinue.getAsBoolean();
                            }

                            @Override
                            public void onError(@NonNull GetCredentialException error) {
                                if (!canContinue.getAsBoolean()) {
                                    return;
                                }

                                if (error instanceof NoCredentialException) {
                                    taskSource.trySetException(new Exception("EMPTY"));
                                } else if (error instanceof GetCredentialCancellationException ||
                                        error instanceof GetCredentialInterruptedException) {
                                    taskSource.trySetException(new CancellationException());
                                } else {
                                    taskSource.trySetException(error);
                                }
                            }
                        }
                    );
                } catch (Exception e) {
                    taskSource.trySetException(e);
                }
            },
            ConnectionsManager.RequestFlagWithoutLogin
        );

        canContinue.getAsBoolean();

        return taskSource.getTask();
    }

    @Nullable
    private static String wrapFirebasePnvPayload(String nonce, String firebasePayload) {
        try {
            return BotWebViewContainer.obj(
                "requests",
                new JSONArray().put(
                    BotWebViewContainer.obj(
                        "protocol", "openid4vp-v1-unsigned",
                        "data", BotWebViewContainer.obj(
                            "response_type", "vp_token",
                            "response_mode", "dc_api",
                            "nonce", nonce,
                            "dcql_query", BotWebViewContainer.obj(
                                "credentials", new JSONArray().put(new JSONObject(firebasePayload))
                            )
                        )
                    )
                )
            ).toString();
        } catch (Exception e) {
            return null;
        }
    }
}
