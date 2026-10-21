/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Wallet;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.telegram.ui.Wallet.GramWallet.SecretPhrase;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_toncenter;
import org.telegram.tgnet.tl.TL_wallet;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@Keep
public class WalletEngine2 implements AutoCloseable {

    public final int currentAccount;
    public final String address;
    public final byte[] publicKey;

    private long ptr;
    private volatile boolean closed;
    private final HttpTransport transport;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "WalletEngine2"));
    private PendingSend pendingSend;
    private ScheduledFuture<?> resolutionTask;
    private long nextAuxiliaryRequestId = -1;

    private static String[] mnemonicWordlist;
    private static native String[] mnemonicWordlist();
    public static String[] getMnemonicWordlist() {
        if (mnemonicWordlist == null) {
            mnemonicWordlist = mnemonicWordlist();
        }
        return mnemonicWordlist;
    }

    public static boolean isTgWalletSecretPhrase(@Nullable SecretPhrase secretPhrase) {
        try {
            secretPhraseToPublicKey(secretPhrase);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static byte[] secretPhraseToPublicKey(SecretPhrase secretPhrase) {
        if (secretPhrase == null || secretPhrase.isEmpty()) {
            throw new IllegalArgumentException("Recovery phrase is required");
        }
        byte[] bytes = secretPhrase.copyBytes();
        try {
            return nativeSecretPhraseToPublicKey(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public static byte[] secretPhraseToAnchorPublicKey(SecretPhrase secretPhrase) {
        if (secretPhrase == null || secretPhrase.isEmpty()) {
            throw new IllegalArgumentException("Recovery phrase is required");
        }
        int count = secretPhrase.wordCount();
        if (count != 12 && count != 24) {
            throw new IllegalArgumentException("Recovery phrase must contain 12 or 24 words");
        }
        try (SecretPhrase anchor = secretPhrase.firstWords(12)) {
            return secretPhraseToPublicKey(anchor);
        }
    }

    public static String secretPhraseToAddress(SecretPhrase secretPhrase) {
        if (secretPhrase == null || secretPhrase.isEmpty()) {
            throw new IllegalArgumentException("Recovery phrase is required");
        }
        byte[] bytes = secretPhrase.copyBytes();
        try {
            return nativeSecretPhraseToAddress(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    static JSONObject tonConnectCrypto(SecretPhrase secretPhrase, TL_wallet.tonConnectSession session,
            String address, byte[] publicKey, JSONObject parameters) throws JSONException {
        if (session == null || session.nonce == null || session.dapp_client_id == null
                || secretPhrase == null || address == null || publicKey == null) {
            throw new IllegalArgumentException("Missing TON Connect crypto parameters");
        }
        parameters.put("dappClientId", session.dapp_client_id);
        parameters.put("sessionNonce", Base64.encodeToString(session.nonce, Base64.NO_WRAP));
        parameters.put("clientId", session.client_id);
        parameters.put("walletPublicKey", Base64.encodeToString(publicKey, Base64.NO_WRAP));
        parameters.put("walletAddress", address);
        byte[] phrase = secretPhrase.copyBytes();
        byte[] output = null;
        try {
            output = nativeTonConnectCrypto(phrase, parameters.toString().getBytes(StandardCharsets.UTF_8));
            return new JSONObject(new String(output, StandardCharsets.UTF_8));
        } finally {
            Arrays.fill(phrase, (byte) 0);
            if (output != null) Arrays.fill(output, (byte) 0);
        }
    }

    static native boolean isValidCellBoc(String boc);

    static boolean sameTonConnectAddress(String first, String second) {
        return isValidAddress(first) && isValidAddress(second)
                && nativeTransactionAddress(first).equals(nativeTransactionAddress(second));
    }

    void previewTonConnect(GramWalletTonConnect.Transaction request, Utilities.Callback2<TL_wallet.walletTransaction, String> callback) {
        try {
            worker.execute(() -> {
                TL_wallet.walletTransaction result = null;
                String error = null;
                try {
                    if (closed) throw new IllegalStateException("Wallet engine is closed");
                    result = nativePreviewTonConnect(ptr, request.fields(), request.validUntil);
                } catch (RuntimeException e) { error = GramWalletTonConnect.exception("wallet-engine transaction", e); }
                final TL_wallet.walletTransaction preview = result;
                final String failure = error;
                AndroidUtilities.runOnUIThread(() -> callback.run(closed ? null : preview, closed ? "Wallet engine is closed" : failure));
            });
        } catch (RejectedExecutionException e) {
            String failure = GramWalletTonConnect.exception("schedule transaction preview", e);
            AndroidUtilities.runOnUIThread(() -> callback.run(null, failure));
        }
    }

    void prepareTonConnectTransfer(SecretPhrase secretPhrase, GramWalletTonConnect.Transaction request, String operationId,
            Utilities.Callback2<TL_wallet.sendTransfer, String> callback) {
        byte[] phrase = secretPhrase.copyBytes();
        try {
            worker.execute(() -> {
                TL_wallet.sendTransfer result = null;
                String error = null;
                try {
                    if (closed) throw new IllegalStateException("Wallet engine is closed");
                    resolveBeforeNewSend();
                    if (isPendingPhase(nativeResolvePending(ptr).phase))
                        throw new IllegalStateException("Previous wallet send is still unresolved");
                    result = nativePrepareTonConnectTransfer(ptr, phrase, request.fields(), request.validUntil, operationId).transfer;
                } catch (RuntimeException e) { error = GramWalletTonConnect.exception("wallet-engine transaction", e); }
                finally { Arrays.fill(phrase, (byte) 0); }
                final TL_wallet.sendTransfer transfer = result;
                final String failure = error;
                AndroidUtilities.runOnUIThread(() -> callback.run(closed ? null : transfer, closed ? "Wallet engine is closed" : failure));
            });
        } catch (RejectedExecutionException e) {
            Arrays.fill(phrase, (byte) 0);
            String failure = GramWalletTonConnect.exception("schedule transaction signing", e);
            AndroidUtilities.runOnUIThread(() -> callback.run(null, failure));
        }
    }

    void previewSignMessage(GramWalletTonConnect.Transaction request, Utilities.Callback<String> callback) {
        try {
            worker.execute(() -> {
                String error = null;
                try {
                    if (closed) throw new IllegalStateException("Wallet engine is closed");
                    nativePreviewSignMessage(ptr, request.fields(), request.validUntil);
                } catch (RuntimeException e) { error = GramWalletTonConnect.exception("preview signing", e); }
                final String failure = error;
                AndroidUtilities.runOnUIThread(() -> callback.run(closed ? "Wallet engine is closed" : failure));
            });
        } catch (RejectedExecutionException e) { AndroidUtilities.runOnUIThread(() -> callback.run("Wallet engine is closed")); }
    }

    void signMessage(SecretPhrase secretPhrase, GramWalletTonConnect.Transaction request, String operationId,
            Utilities.Callback2<String, String> callback) {
        byte[] phrase = secretPhrase.copyBytes();
        try {
            worker.execute(() -> {
                String boc = null, error = null;
                try {
                    if (closed) throw new IllegalStateException("Wallet engine is closed");
                    boc = nativeSignMessage(ptr, phrase, request.fields(), request.validUntil, operationId);
                } catch (RuntimeException e) { error = GramWalletTonConnect.exception("sign message", e); }
                finally { Arrays.fill(phrase, (byte) 0); }
                final String signed = boc, failure = error;
                AndroidUtilities.runOnUIThread(() -> callback.run(closed ? null : signed, closed ? "Wallet engine is closed" : failure));
            });
        } catch (RejectedExecutionException e) {
            Arrays.fill(phrase, (byte) 0);
            AndroidUtilities.runOnUIThread(() -> callback.run(null, "Wallet engine is closed"));
        }
    }

    static JSONObject signData(SecretPhrase phrase, TL_wallet.tonConnectSession session, String address,
            byte[] publicKey, JSONObject payload, String domain, int timestamp) throws JSONException {
        JSONObject signed = tonConnectCrypto(phrase, session, address, publicKey, new JSONObject()
                .put("signData", payload).put("signDomain", domain).put("timestamp", timestamp));
        return new JSONObject().put("signature", signed.getString("signature")).put("address", signed.getString("address"))
                .put("timestamp", timestamp).put("domain", domain).put("payload", payload);
    }

    private static native void nativePreviewSignMessage(long handle, String[] fields, long validUntil);
    private static native String nativeSignMessage(long handle, byte[] phrase, String[] fields, long validUntil, String operationId);

    private static native TL_wallet.walletTransaction nativePreviewTonConnect(long handle, String[] fields, long validUntil);
    private static native PreparedSend nativePrepareTonConnectTransfer(long handle, byte[] phrase, String[] fields, long validUntil, String operationId);

    private static native byte[] nativeTonConnectCrypto(byte[] phrase, byte[] parameters);

    @Keep
    public static final class ImportedWalletProof {
        public final byte[] publicKey;
        public final int timestamp;
        public final byte[] signature;

        @Keep
        private ImportedWalletProof(byte[] publicKey, int timestamp, byte[] signature) {
            this.publicKey = publicKey;
            this.timestamp = timestamp;
            this.signature = signature;
        }
    }

    public static ImportedWalletProof createImportProof(SecretPhrase phrase, String domain, String payload, int timestamp) {
        return createProof(phrase, domain, payload, timestamp, true);
    }

    public static ImportedWalletProof createOwnershipProof(SecretPhrase phrase, String domain, String payload, int timestamp) {
        return createProof(phrase, domain, payload, timestamp, false);
    }

    private static ImportedWalletProof createProof(SecretPhrase phrase, String domain, String payload, int timestamp, boolean forImport) {
        if (phrase == null || phrase.isEmpty()) {
            throw new IllegalArgumentException("Recovery phrase is required");
        }
        if (domain == null || domain.isEmpty() || payload == null || payload.isEmpty() || timestamp <= 0) {
            throw new IllegalArgumentException("Challenge domain, payload and positive timestamp are required");
        }
        byte[] bytes = phrase.copyBytes();
        try {
            return nativeCreateImportProof(bytes, domain.getBytes(StandardCharsets.UTF_8),
                    payload.getBytes(StandardCharsets.UTF_8), timestamp, forImport);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public static String decryptComment(SecretPhrase secretPhrase, String senderAddress, String encryptedComment) {
        if (secretPhrase == null || secretPhrase.isEmpty()) {
            throw new IllegalArgumentException("Recovery phrase is required");
        }
        if (!isValidAddress(senderAddress)) {
            throw new IllegalArgumentException("Valid sender address is required");
        }
        byte[] payload = decodeEncryptedComment(encryptedComment);
        String body = encryptedCommentBody(payload);
        byte[] phrase = secretPhrase.copyBytes();
        byte[] plaintext = null;
        try {
            plaintext = nativeDecryptRawComment(phrase, senderAddress, body);
            return new String(plaintext, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(phrase, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static byte[] decodeEncryptedComment(String comment) {
        if (comment == null || comment.isEmpty() || comment.length() > 1368
                || !comment.matches("[A-Za-z0-9+/]+={0,2}")) {
            throw new IllegalArgumentException("Invalid encrypted comment base64");
        }
        byte[] payload = Base64.decode(comment, Base64.NO_WRAP);
        if (payload.length < 64 || payload.length > 1024 || payload.length % 16 != 0) {
            throw new IllegalArgumentException("Invalid encrypted comment size");
        }
        return payload;
    }

    private static String encryptedCommentBody(byte[] payload) {
        int cells = 1 + (payload.length - 35 + 126) / 127;
        int totalCellBytes = payload.length + 4 + cells * 2 + cells - 1;
        ByteArrayOutputStream boc = new ByteArrayOutputStream(12 + totalCellBytes);
        byte[] header = {(byte) 0xb5, (byte) 0xee, (byte) 0x9c, 0x72,
            1, 2, (byte) cells, 1, 0, (byte) (totalCellBytes >> 8), (byte) totalCellBytes, 0};
        boc.write(header, 0, header.length);
        int offset = 0;
        for (int i = 0; i < cells; i++) {
            int count = Math.min(i == 0 ? 35 : 127, payload.length - offset);
            boolean hasNext = i + 1 < cells;
            boc.write(hasNext ? 1 : 0);
            boc.write((count + (i == 0 ? 4 : 0)) * 2);
            if (i == 0) {
                boc.write(0x21);
                boc.write(0x67);
                boc.write(0xda);
                boc.write(0x4b);
            }
            boc.write(payload, offset, count);
            if (hasNext) boc.write(i + 1);
            offset += count;
        }
        return Base64.encodeToString(boc.toByteArray(), Base64.NO_WRAP);
    }

    public static boolean isValidAddress(@Nullable String address) {
        return address != null && !address.isEmpty() && nativeIsValidAddress(address);
    }

    public static boolean isValidRecipientAddress(@Nullable String address) {
        if (!isValidAddress(address)) return false;
        if (address.indexOf(':') >= 0) return true;
        final byte[] decoded = Base64.decode(address.replace('-', '+').replace('_', '/'), Base64.NO_WRAP);
        return (decoded[0] & 0x80) == 0;
    }

    static String toUserFriendlyAddress(String address) {
        return nativeTransactionAddress(address);
    }

    public WalletEngine2(int currentAccount, String address, byte[] publicKey) {
        this.currentAccount = currentAccount;
        this.address = address;
        this.publicKey = publicKey.clone();
        transport = new HttpTransport(currentAccount);
        ptr = nativeCreate(currentAccount, address, this.publicKey, transport,
            ApplicationLoader.applicationContext.getNoBackupFilesDir().getAbsolutePath() + "/wallet-engine");
    }

    public synchronized void balance(
        Utilities.Callback2<Long, String  > onResult
    ) {
        worker.execute(() -> {
            Long balance = null;
            String error = null;
            try {
                balance = nativeBalance(ptr);
            } catch (RuntimeException e) {
                error = e.getMessage() == null ? "NULL_ERROR" : e.getMessage();
            }
            final Long result = balance;
            final String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                onResult.run(result, failure);
            });
        });
    }

    public Runnable emulateSend(
        String address,
        long amount,
        String comment,
        byte[] comment_recipient_public_key,
        Utilities.Callback2<TL_wallet.walletTransaction, String  > onResult
    ) {
        final byte[] commentBytes = comment == null ? null : comment.getBytes(StandardCharsets.UTF_8);
        final AtomicBoolean cancelled = new AtomicBoolean();
        final FutureTask<Void> task = new FutureTask<>(() -> {
            if (cancelled.get()) return null;
            TL_wallet.walletTransaction transaction = null;
            String error = null;
            try {
                final byte[] previewComment = commentBytes != null && comment_recipient_public_key != null
                    ? encryptedCommentPreview(commentBytes.length) : commentBytes;
                transaction = nativeEmulateSend(ptr, address, amount, previewComment, comment_recipient_public_key != null);
            } catch (RuntimeException e) {
                error = errorMessage(e);
            }
            final TL_wallet.walletTransaction result = transaction;
            final String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (!cancelled.get()) onResult.run(result, failure);
            });
            return null;
        });
        try {
            worker.execute(task);
        } catch (RejectedExecutionException e) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!cancelled.get()) onResult.run(null, "NO_WALLET_ENGINE");
            });
        }
        return () -> {
            cancelled.set(true);
            task.cancel(false);
        };
    }

    private static final long NFT_ATTACHED_NANOGRAMS = 100_000_000L;
    private static final long NFT_FORWARD_NANOGRAMS = 10_000_000L;
    private String nftPreviewRecipient;
    private String nftPreviewAddress;
    private String nftPreviewComment;
    private String nftPreviewOperationId;

    public synchronized Runnable emulateSendNFT(String address, String gift_address,
            Utilities.Callback2<TL_wallet.walletTransaction, String> onResult) {
        return emulateSendNFT(address, gift_address, null, onResult);
    }

    public synchronized Runnable emulateSendNFT(String address, String gift_address, String comment,
            Utilities.Callback2<TL_wallet.walletTransaction, String> onResult) {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final FutureTask<Void> task = new FutureTask<>(() -> {
            if (cancelled.get()) return null;
            TL_wallet.walletTransaction transaction = null;
            String error = null;
            try {
                if (closed) throw new IllegalStateException("NO_WALLET_ENGINE");
                final String operationId = UUID.randomUUID().toString();
                transaction = nativeEmulateSendNFT(ptr, address, gift_address, comment == null ? null : comment.getBytes(StandardCharsets.UTF_8), operationId,
                        NFT_ATTACHED_NANOGRAMS, NFT_FORWARD_NANOGRAMS);
                nftPreviewRecipient = address;
                nftPreviewAddress = gift_address;
                nftPreviewComment = comment;
                nftPreviewOperationId = operationId;
            } catch (RuntimeException e) {
                error = errorMessage(e);
            }
            final TL_wallet.walletTransaction result = transaction;
            final String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (!cancelled.get()) onResult.run(closed ? null : result, closed ? "NO_WALLET_ENGINE" : failure);
            });
            return null;
        });
        if (closed) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!cancelled.get()) onResult.run(null, "NO_WALLET_ENGINE");
            });
        } else {
            try {
                worker.execute(task);
            } catch (RejectedExecutionException e) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!cancelled.get()) onResult.run(null, "NO_WALLET_ENGINE");
                });
            }
        }
        return () -> { cancelled.set(true); task.cancel(false); };
    }

    public synchronized void prepareSendNFT(SecretPhrase secret_phrase, String address, String gift_address,
            Utilities.Callback2<TL_wallet.sendTransfer, String> onResult) {
        prepareSendNFT(secret_phrase, address, gift_address, null, onResult);
    }

    public synchronized void prepareSendNFT(SecretPhrase secret_phrase, String address, String gift_address, String comment,
            Utilities.Callback2<TL_wallet.sendTransfer, String> onResult) {
        if (closed || secret_phrase == null || secret_phrase.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> onResult.run(null,
                    closed ? "NO_WALLET_ENGINE" : "Recovery phrase is required"));
            return;
        }
        final byte[] phrase = secret_phrase.copyBytes();
        try {
            worker.execute(() -> {
                TL_wallet.sendTransfer transfer = null;
                String error = null;
                try {
                    if (closed) throw new IllegalStateException("NO_WALLET_ENGINE");
                    resolveBeforeNewSend();
                    if (isPendingPhase(nativeResolvePending(ptr).phase)) {
                        throw new IllegalStateException("Previous wallet send is still unresolved");
                    }
                    final String operationId = nftPreviewOperationId != null
                            && java.util.Objects.equals(address, nftPreviewRecipient)
                            && java.util.Objects.equals(gift_address, nftPreviewAddress)
                            && java.util.Objects.equals(comment, nftPreviewComment)
                            ? nftPreviewOperationId : UUID.randomUUID().toString();
                    final PreparedSend prepared = nativePrepareSendNFT(ptr, phrase, address, gift_address,
                            comment == null ? null : comment.getBytes(StandardCharsets.UTF_8), operationId, NFT_ATTACHED_NANOGRAMS, NFT_FORWARD_NANOGRAMS);
                    if (prepared == null) throw new IllegalStateException("Could not prepare NFT transfer");
                    transfer = prepared.transfer;
                    nftPreviewOperationId = null;
                } catch (RuntimeException e) {
                    error = errorMessage(e);
                } finally {
                    Arrays.fill(phrase, (byte) 0);
                }
                final TL_wallet.sendTransfer result = transfer;
                final String failure = error;
                AndroidUtilities.runOnUIThread(() -> onResult.run(
                        closed ? null : result, closed ? "NO_WALLET_ENGINE" : failure));
            });
        } catch (RejectedExecutionException e) {
            Arrays.fill(phrase, (byte) 0);
            AndroidUtilities.runOnUIThread(() -> onResult.run(null, "NO_WALLET_ENGINE"));
        }
    }

    private static native TL_wallet.walletTransaction nativeEmulateSendNFT(long handle, String address,
            String nftAddress, byte[] comment, String operationId, long attachedNanograms, long forwardNanograms);
    private static native PreparedSend nativePrepareSendNFT(long handle, byte[] phrase, String address,
            String nftAddress, byte[] comment, String operationId, long attachedNanograms, long forwardNanograms);

    private static byte[] encryptedCommentPreview(int commentLength) {
        if (commentLength > 960) {
            throw new IllegalArgumentException("The encrypted comment exceeds 960 UTF-8 bytes");
        }
        final int payloadLength = 48 + commentLength + 16 + (16 - commentLength % 16) % 16;
        final int cells = 1 + (payloadLength - 35 + 126) / 127;
        final int dataSize = 4 + payloadLength + 2 * cells + cells - 1;
        final java.nio.ByteBuffer boc = java.nio.ByteBuffer.allocate(12 + dataSize);
        boc.putInt(0xb5ee9c72);
        boc.put((byte) 1);
        boc.put((byte) 2);
        boc.put((byte) cells).put((byte) 1).put((byte) 0);
        boc.putShort((short) dataSize).put((byte) 0);
        int remaining = payloadLength;
        for (int i = 0; i < cells; i++) {
            final int chunk = Math.min(remaining, i == 0 ? 35 : 127);
            final boolean hasNext = i + 1 < cells;
            boc.put((byte) (hasNext ? 1 : 0));
            boc.put((byte) ((chunk + (i == 0 ? 4 : 0)) * 2));
            if (i == 0) boc.putInt(0x2167da4b);
            boc.position(boc.position() + chunk);
            if (hasNext) boc.put((byte) (i + 1));
            remaining -= chunk;
        }
        return android.util.Base64.encode(boc.array(), android.util.Base64.NO_WRAP);
    }

    public void emulateRotateKey(
        Utilities.Callback2<TL_wallet.walletTransaction, String  > onResult
    ) {
        if (onResult == null) throw new IllegalArgumentException("Result callback is required");
        if (closed) {
            AndroidUtilities.runOnUIThread(() -> onResult.run(null, "NO_WALLET_ENGINE"));
            return;
        }
        try {
            worker.execute(() -> {
                TL_wallet.walletTransaction transaction = null;
                String error = null;
                try {
                    if (closed) throw new IllegalStateException("NO_WALLET_ENGINE");
                    transaction = nativeEmulateRotateKey(ptr, address);
                } catch (RuntimeException e) {
                    error = errorMessage(e);
                }
                final TL_wallet.walletTransaction result = transaction;
                final String failure = error;
                AndroidUtilities.runOnUIThread(() -> onResult.run(
                    closed ? null : result, closed ? "NO_WALLET_ENGINE" : failure));
            });
        } catch (RejectedExecutionException e) {
            AndroidUtilities.runOnUIThread(() -> onResult.run(null, "NO_WALLET_ENGINE"));
        }
    }

    public void prepareRotateKey(
        SecretPhrase secret_phrase,
        @Nullable String gasless_address,
        Utilities.Callback4<TL_wallet.sendTransfer, SecretPhrase  ,
            byte[]  , String  > onResult
    ) {
        if (onResult == null) throw new IllegalArgumentException("Result callback is required");
        if (closed) {
            AndroidUtilities.runOnUIThread(() -> onResult.run(null, null, null, "NO_WALLET_ENGINE"));
            return;
        }
        if (secret_phrase == null || secret_phrase.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> onResult.run(null, null, null, "Recovery phrase is required"));
            return;
        }
        final byte[] phrase = secret_phrase.copyBytes();
        try {
            worker.execute(() -> {
                PreparedRotation prepared = null;
                String error = null;
                try {
                    resolveBeforeNewSend();
                    if (isPendingPhase(nativeResolvePending(ptr).phase)) {
                        throw new IllegalStateException("Previous wallet send is still unresolved");
                    }
                    prepared = nativePrepareRotateKey(ptr, phrase, gasless_address);
                    if (prepared == null) throw new IllegalStateException("Could not prepare key rotation");
                } catch (RuntimeException e) {
                    error = errorMessage(e);
                } finally {
                    Arrays.fill(phrase, (byte) 0);
                }
                final PreparedRotation result = prepared;
                final String failure = error;
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        if (closed) {
                            onResult.run(null, null, null, "NO_WALLET_ENGINE");
                        } else if (result == null) {
                            onResult.run(null, null, null, failure);
                        } else {
                            onResult.run(result.transfer, result.newSecretPhrase, result.newPublicKey, null);
                        }
                    } finally {
                        if (result != null) result.newSecretPhrase.close();
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            Arrays.fill(phrase, (byte) 0);
            AndroidUtilities.runOnUIThread(() -> onResult.run(null, null, null, "NO_WALLET_ENGINE"));
        }
    }

    public void getTransactionByHash(String tx_hash, Utilities.Callback<TL_wallet.walletTransaction> done) {
        if (done == null) {
            throw new IllegalArgumentException("Result callback is required");
        }
        if (tx_hash == null || tx_hash.isEmpty() || closed) {
            AndroidUtilities.runOnUIThread(() -> done.run(null));
            return;
        }
        try {
            worker.execute(() -> {
                TL_wallet.walletTransaction transaction = null;
                try {
                    final String url = "https://toncenter.com/api/v3/transactions?hash="
                        + URLEncoder.encode(tx_hash, "UTF-8") + "&limit=1";
                    final byte[] response = transport.execute(
                        nextAuxiliaryRequestId--, url, false, new byte[0], 15000);
                    transaction = parseTransactionResponse(response);
                } catch (Exception e) {
                    FileLog.e(e);
                }
                final TL_wallet.walletTransaction result = transaction;
                AndroidUtilities.runOnUIThread(() -> done.run(result));
            });
        } catch (RejectedExecutionException e) {
            AndroidUtilities.runOnUIThread(() -> done.run(null));
        }
    }

    private static TL_wallet.walletTransaction parseTransactionResponse(byte[] response) throws Exception {
        final JSONObject envelope = new JSONObject(new String(response, StandardCharsets.UTF_8));
        final JSONArray transactions = envelope.getJSONArray("transactions");
        if (transactions.length() != 1) return null;
        final JSONObject transaction = transactions.getJSONObject(0);
        final JSONObject incoming = transaction.optJSONObject("in_msg");
        final JSONArray outgoing = transaction.getJSONArray("out_msgs");
        final boolean isIncoming = incoming != null && !incoming.isNull("source")
            && !incoming.getString("source").isEmpty();
        if (isIncoming ? outgoing.length() != 0 : outgoing.length() != 1) return null;
        final JSONObject message = isIncoming ? incoming : outgoing.getJSONObject(0);
        final String peerField = isIncoming ? "source" : "destination";
        if (message.isNull(peerField)) return null;
        final long amount = Long.parseLong(message.getString("value"));
        final long fee = Long.parseLong(transaction.getString("total_fees"));
        if (amount < 0 || fee < 0) return null;
        final TL_wallet.walletTransaction result = createTransaction(message.getString(peerField), amount);
        result.incoming = isIncoming;
        result.tx_hash = transaction.getString("hash");
        result.id = "chain:" + result.tx_hash + (isIncoming ? ":in:0" : ":out:0");
        result.date = Math.toIntExact(transaction.getLong("now"));
        result.fee = fee;
        result.failed = transaction.getJSONObject("description").getBoolean("aborted")
            || message.optBoolean("bounced", false);
        result.phase = result.failed ? SendPhase.FAILED : SendPhase.CONFIRMED;
        result.comment_encrypted = isEncryptedComment(message);
        final JSONObject content = message.optJSONObject("message_content");
        final JSONObject decoded = content == null ? null : content.optJSONObject("decoded");
        if (result.comment_encrypted) {
            result.comment = encryptedCommentPayload(content.getString("body"));
        } else if (decoded != null && "text_comment".equals(decoded.optString("type"))) {
            result.comment = decoded.getString("comment");
        }
        return result;
    }

    static String encryptedCommentPayload(String body) {
        if (body == null || body.length() > 8192) {
            throw new IllegalArgumentException("Invalid encrypted comment BOC");
        }
        final java.nio.ByteBuffer boc = java.nio.ByteBuffer.wrap(Base64.decode(body, Base64.DEFAULT));
        if (boc.getInt() != 0xb5ee9c72) throw new IllegalArgumentException("Invalid BOC magic");
        final int flags = boc.get() & 0xff;
        final int indexBytes = flags & 7;
        final int offsetBytes = boc.get() & 0xff;
        if ((flags & 0x38) != 0) throw new IllegalArgumentException("Unsupported BOC flags");
        final int cells = readBocInt(boc, indexBytes);
        final int roots = readBocInt(boc, indexBytes);
        final int absent = readBocInt(boc, indexBytes);
        final int dataSize = readBocInt(boc, offsetBytes);
        if (cells < 1 || cells > 1028 || roots != 1 || absent != 0) {
            throw new IllegalArgumentException("Invalid encrypted comment cells");
        }
        final int root = readBocInt(boc, indexBytes);
        if (root >= cells) throw new IllegalArgumentException("Invalid BOC root");
        if ((flags & 0x80) != 0) {
            for (int i = 0; i < cells; i++) readBocInt(boc, offsetBytes);
        }
        final int checksumBytes = (flags & 0x40) != 0 ? 4 : 0;
        if (dataSize != boc.remaining() - checksumBytes) {
            throw new IllegalArgumentException("Invalid BOC size");
        }
        final int end = boc.position() + dataSize;
        boc.limit(end);
        final byte[][] data = new byte[cells][];
        final int[] next = new int[cells];
        for (int i = 0; i < cells; i++) {
            final int refs = boc.get() & 0xff;
            final int bits = boc.get() & 0xff;
            if (refs > 1 || (bits & 1) != 0) {
                throw new IllegalArgumentException("Invalid encrypted comment snake");
            }
            data[i] = new byte[bits / 2];
            boc.get(data[i]);
            next[i] = refs == 0 ? -1 : readBocInt(boc, indexBytes);
            if (refs != 0 && (next[i] <= i || next[i] >= cells)) {
                throw new IllegalArgumentException("Invalid BOC reference");
            }
        }
        if (boc.position() != end) throw new IllegalArgumentException("Invalid BOC cell data");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = root; i != -1; i = next[i]) {
            if (bytes.size() + data[i].length > 1028) {
                throw new IllegalArgumentException("Encrypted comment is too long");
            }
            bytes.write(data[i], 0, data[i].length);
        }
        final byte[] raw = bytes.toByteArray();
        if (raw.length < 4 || java.nio.ByteBuffer.wrap(raw).getInt() != 0x2167da4b) {
            throw new IllegalArgumentException("Invalid encrypted comment opcode");
        }
        final String payload = Base64.encodeToString(Arrays.copyOfRange(raw, 4, raw.length), Base64.NO_WRAP);
        decodeEncryptedComment(payload);
        return payload;
    }

    static String textCommentFromBody(String body) {
        if (body == null || body.length() > 8192) return null;
        try {
            final java.nio.ByteBuffer boc = java.nio.ByteBuffer.wrap(Base64.decode(body, Base64.DEFAULT));
            if (boc.getInt() != 0xb5ee9c72) return null;
            final int flags = boc.get() & 0xff;
            final int indexBytes = flags & 7;
            final int offsetBytes = boc.get() & 0xff;
            if ((flags & 0x38) != 0) return null;
            final int cells = readBocInt(boc, indexBytes);
            final int roots = readBocInt(boc, indexBytes);
            final int absent = readBocInt(boc, indexBytes);
            final int dataSize = readBocInt(boc, offsetBytes);
            if (cells < 1 || cells > 1028 || roots != 1 || absent != 0) return null;
            final int root = readBocInt(boc, indexBytes);
            if (root >= cells) return null;
            if ((flags & 0x80) != 0) {
                for (int i = 0; i < cells; i++) readBocInt(boc, offsetBytes);
            }
            final int checksumBytes = (flags & 0x40) != 0 ? 4 : 0;
            if (dataSize != boc.remaining() - checksumBytes) return null;
            final int end = boc.position() + dataSize;
            boc.limit(end);
            final byte[][] data = new byte[cells][];
            final int[] next = new int[cells];
            for (int i = 0; i < cells; i++) {
                final int descriptor = boc.get() & 0xff;
                final int bits = boc.get() & 0xff;
                final int refs = descriptor & 7;
                if ((descriptor & ~7) != 0 || refs > 1) return null;
                final int bytesCount = (bits + 1) >> 1;
                if (boc.remaining() < bytesCount + refs * indexBytes) return null;
                data[i] = new byte[bytesCount];
                boc.get(data[i]);
                if (refs == 0) {
                    if ((bits & 1) != 0 && bytesCount > 0) {
                        final int last = data[i][bytesCount - 1] & 0xff;
                        int trailing = 0;
                        while (trailing < 8 && (last & (1 << trailing)) == 0) trailing++;
                        if (trailing == 8) return null;
                        final int trimmed = last & ~(1 << trailing);
                        if (trimmed == 0) data[i] = Arrays.copyOf(data[i], bytesCount - 1);
                        else data[i][bytesCount - 1] = (byte) trimmed;
                    }
                } else if ((bits & 1) != 0) {
                    return null;
                }
                next[i] = refs == 0 ? -1 : readBocInt(boc, indexBytes);
                if (refs != 0 && (next[i] <= i || next[i] >= cells)) return null;
            }
            if (boc.position() != end) return null;
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int i = root; i != -1; i = next[i]) {
                if (bytes.size() + data[i].length > 1028) return null;
                bytes.write(data[i], 0, data[i].length);
            }
            final byte[] raw = bytes.toByteArray();
            if (raw.length < 4 || java.nio.ByteBuffer.wrap(raw).getInt() != 0) return null;
            return new String(raw, 4, raw.length - 4, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static int readBocInt(java.nio.ByteBuffer boc, int size) {
        if (size < 1 || size > 4) throw new IllegalArgumentException("Invalid BOC integer size");
        long value = 0;
        for (int i = 0; i < size; i++) value = (value << 8) | (boc.get() & 0xff);
        if (value > Integer.MAX_VALUE) throw new IllegalArgumentException("BOC integer overflow");
        return (int) value;
    }

    public void decryptTransactionComment(
        SecretPhrase secretPhrase,
        String transactionTxHash,
        Utilities.Callback2<String  , String  > onResult
    ) {
        if (onResult == null) {
            throw new IllegalArgumentException("Result callback is required");
        }
        if (secretPhrase == null || secretPhrase.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> onResult.run(null, "Recovery phrase is required"));
            return;
        }
        if (transactionTxHash == null || transactionTxHash.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> onResult.run(null, "Transaction hash is required"));
            return;
        }
        if (closed) {
            AndroidUtilities.runOnUIThread(() -> onResult.run(null, "NO_WALLET_ENGINE"));
            return;
        }

        final byte[] phrase = secretPhrase.copyBytes();
        try {
            worker.execute(() -> {
                String comment = null;
                String error = null;
                try {
                    final String encodedHash = URLEncoder.encode(transactionTxHash, "UTF-8");
                    final String url = "https://toncenter.com/api/v3/transactions?hash="
                        + encodedHash + "&limit=1";
                    final byte[] response = transport.execute(
                        nextAuxiliaryRequestId--, url, false, new byte[0], 15000);
                    comment = decryptTransactionCommentResponse(phrase, response);
                } catch (Exception e) {
                    error = e.getMessage() == null ? "Could not decrypt transaction comment" : e.getMessage();
                } finally {
                    Arrays.fill(phrase, (byte) 0);
                }
                final String result = comment;
                final String failure = error;
                AndroidUtilities.runOnUIThread(() -> onResult.run(result, failure));
            });
        } catch (RejectedExecutionException e) {
            Arrays.fill(phrase, (byte) 0);
            AndroidUtilities.runOnUIThread(() -> onResult.run(null, "NO_WALLET_ENGINE"));
        }
    }

    private String decryptTransactionCommentResponse(byte[] phrase, byte[] response) throws Exception {
        final JSONObject envelope = new JSONObject(new String(response, StandardCharsets.UTF_8));
        final JSONArray transactions = envelope.optJSONArray("transactions");
        if (transactions == null) {
            throw new IllegalStateException("Toncenter returned an invalid transaction response");
        }
        if (transactions.length() == 0) {
            throw new IllegalStateException("Transaction not found");
        }

        String decrypted = null;
        int decryptedCount = 0;
        int encryptedCount = 0;
        RuntimeException decryptionFailure = null;
        for (int transactionIndex = 0; transactionIndex < transactions.length(); transactionIndex++) {
            final JSONObject transaction = transactions.getJSONObject(transactionIndex);
            final JSONObject incoming = transaction.optJSONObject("in_msg");
            if (isEncryptedComment(incoming)) {
                encryptedCount++;
                try {
                    decrypted = decryptMessageComment(phrase, incoming);
                    decryptedCount++;
                } catch (RuntimeException e) {
                    decryptionFailure = e;
                }
            }

            final JSONArray outgoing = transaction.optJSONArray("out_msgs");
            if (outgoing == null) continue;
            for (int messageIndex = 0; messageIndex < outgoing.length(); messageIndex++) {
                final JSONObject message = outgoing.getJSONObject(messageIndex);
                if (!isEncryptedComment(message)) continue;
                encryptedCount++;
                try {
                    decrypted = decryptMessageComment(phrase, message);
                    decryptedCount++;
                } catch (RuntimeException e) {
                    decryptionFailure = e;
                }
            }
        }

        if (encryptedCount == 0) {
            throw new IllegalStateException("Transaction has no encrypted comment");
        }
        if (decryptedCount == 0) {
            throw decryptionFailure != null
                ? decryptionFailure
                : new IllegalStateException("Transaction comment could not be decrypted");
        }
        if (decryptedCount != 1) {
            throw new IllegalStateException("Transaction contains multiple decryptable comments");
        }
        return decrypted;
    }

    private String decryptMessageComment(byte[] phrase, JSONObject message) {
        final String sender = message.optString("source", null);
        final JSONObject content = message.optJSONObject("message_content");
        final String body = content == null ? null : content.optString("body", null);
        if (sender == null || sender.isEmpty() || body == null || body.isEmpty()) {
            throw new IllegalStateException("Encrypted transaction message is incomplete");
        }
        return new String(nativeDecryptComment(ptr, phrase, sender, body), StandardCharsets.UTF_8);
    }

    static boolean isEncryptedComment(JSONObject message) {
        if (message == null) return false;
        final Object rawOpcode = message.opt("opcode");
        if (rawOpcode instanceof Number) {
            return (((Number) rawOpcode).longValue() & 0xffffffffL) == 0x2167da4bL;
        }
        if (!(rawOpcode instanceof String)) return false;
        try {
            return (Long.decode((String) rawOpcode) & 0xffffffffL) == 0x2167da4bL;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public void prepareSend(
        SecretPhrase secret_phrase,
        String address,
        long amount,
        String comment,
        byte[] comment_recipient_public_key,
        String gasless_address,
        Utilities.Callback3<TL_wallet.sendTransfer, String  , String  > onResult
    ) {
        if (onResult == null) throw new IllegalArgumentException("Result callback is required");
        if (closed) {
            AndroidUtilities.runOnUIThread(() -> onResult.run(null, null, "NO_WALLET_ENGINE"));
            return;
        }
        if (secret_phrase == null || secret_phrase.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> onResult.run(null, null, "Recovery phrase is required"));
            return;
        }
        final byte[] commentRecipientPublicKey = comment_recipient_public_key == null ? null : comment_recipient_public_key.clone();
        final byte[] phrase = secret_phrase.copyBytes();
        final byte[] commentBytes = comment == null ? null : comment.getBytes(StandardCharsets.UTF_8);
        try {
            worker.execute(() -> {
                TL_wallet.sendTransfer transfer = null;
                String encryptedComment = null;
                String error = null;
                try {
                    resolveBeforeNewSend();
                    if (isPendingPhase(nativeResolvePending(ptr).phase)) {
                        throw new IllegalStateException("Previous wallet send is still unresolved");
                    }
                    final PreparedSend prepared = nativePrepareSend(ptr, phrase, address, amount, commentBytes,
                        commentRecipientPublicKey, gasless_address, UUID.randomUUID().toString());
                    if (prepared != null) {
                        encryptedComment = prepared.encryptedCommentBody == null ? null
                            : encryptedCommentPayload(prepared.encryptedCommentBody);
                        transfer = prepared.transfer;
                    }
                    if (closed) {
                        transfer = null;
                        error = "NO_WALLET_ENGINE";
                    } else if (transfer == null) {
                        error = "Could not prepare transfer";
                    }
                } catch (RuntimeException e) {
                    error = errorMessage(e);
                } finally {
                    Arrays.fill(phrase, (byte) 0);
                }
                final TL_wallet.sendTransfer result = transfer;
                final String encrypted_comment = transfer == null ? null : encryptedComment;
                final String failure = error;
                AndroidUtilities.runOnUIThread(() -> onResult.run(
                    closed ? null : result, closed ? null : encrypted_comment, closed ? "NO_WALLET_ENGINE" : failure));
            });
        } catch (RejectedExecutionException e) {
            Arrays.fill(phrase, (byte) 0);
            AndroidUtilities.runOnUIThread(() -> onResult.run(null, null, "NO_WALLET_ENGINE"));
        }
    }

    public static boolean isPendingPhase(SendPhase phase) {
        return phase != null && phase != SendPhase.IDLE && !isFinalPhase(phase);
    }

    private static class PendingSend {
        final String operationId;
        final SendCallbacks callbacks;
        SendPhase phase;
        String resolutionError;

        PendingSend(TL_wallet.walletTransaction transaction, SendCallbacks callbacks) {
            this.operationId = transaction.operationId;
            this.callbacks = callbacks;
            this.phase = transaction.phase;
        }
    }

    @Keep
    private static class SendResolution {
        final String operationId;
        final SendPhase phase;
        final String transactionHash;
        final String error;
        final long retryAfterMs;

        @Keep
        SendResolution(String operationId, int nativePhase, String transactionHash, String error, long retryAfterMs) {
            this.operationId = operationId;
            this.phase = SendPhase.fromNative(nativePhase);
            this.transactionHash = transactionHash;
            this.error = error;
            this.retryAfterMs = retryAfterMs;
        }
    }

    private long resolvePendingSend() {
        if (closed || pendingSend == null) return 4000;
        final PendingSend pending = pendingSend;
        final SendResolution resolution = nativeResolvePending(ptr);
        if (closed) return 4000;
        pending.resolutionError = null;
        if (!pending.operationId.equals(resolution.operationId)) {
            pendingSend = null;
            pending.callbacks.state(pending.phase.nativeValue, "Send journal changed before confirmation was observed", null);
            return 4000;
        }
        if (resolution.phase == SendPhase.CONFIRMED
                && (resolution.transactionHash == null || resolution.transactionHash.isEmpty())) {
            throw new IllegalStateException("Confirmed send is missing its transaction hash");
        }
        pending.phase = resolution.phase;
        pending.callbacks.state(resolution.phase.nativeValue, resolution.error, resolution.transactionHash);
        if (isFinalPhase(resolution.phase)) {
            pendingSend = null;
        }
        return Math.max(1000, resolution.retryAfterMs);
    }

    private void resolveBeforeNewSend() {
        if (closed) throw new IllegalStateException("NO_WALLET_ENGINE");
        if (pendingSend == null) return;
        try {
            resolvePendingSend();
        } catch (RuntimeException e) {
            FileLog.e("[wallet-engine] Could not observe previous send before signing: " + errorMessage(e));
        }
        if (pendingSend == null && resolutionTask != null) {
            resolutionTask.cancel(false);
            resolutionTask = null;
        }
    }

    private static String errorMessage(Exception e) {
        return e.getMessage() == null ? "NULL_ERROR" : e.getMessage();
    }

    @Keep
    private static class SendCallbacks {
        final Utilities.Callback3<SendPhase, String, String> callback;
        SendPhase phase = SendPhase.IDLE;
        String error;
        String transactionHash;

        SendCallbacks(Utilities.Callback3<SendPhase, String, String> callback) {
            this.callback = callback;
        }

        @Keep
        synchronized void state(int nativePhase, String error, String transactionHash) {
            final SendPhase phase = SendPhase.fromNative(nativePhase);
            if (this.phase == phase && java.util.Objects.equals(this.error, error)
                    && java.util.Objects.equals(this.transactionHash, transactionHash)) return;
            this.phase = phase;
            this.error = error;
            this.transactionHash = transactionHash;
            dispatch(phase, error, transactionHash);
        }

        void dispatch(SendPhase phase, String error, String transactionHash) {
            AndroidUtilities.runOnUIThread(() -> callback.run(phase, error, transactionHash));
        }

        synchronized void failed(String error) {
            state((phase == SendPhase.SUBMISSION_UNKNOWN ? phase : SendPhase.FAILED).nativeValue, error, null);
        }
    }

    public void rotateKey(
        SecretPhrase current_secret_phrase,
        Utilities.Callback2<SecretPhrase  , byte[]  > onNewSecretPhrase,
        Utilities.Callback2<SendPhase, String  > onState
    ) {
        final RotationCallbacks callbacks = new RotationCallbacks(onState, onNewSecretPhrase);
        if (current_secret_phrase == null || current_secret_phrase.isEmpty() || onNewSecretPhrase == null) {
            callbacks.failed("Current recovery phrase and replacement callback are required");
            return;
        }
        final byte[] phrase = current_secret_phrase.copyBytes();
        final String operationId = UUID.randomUUID().toString();
        try {
            worker.execute(() -> {
                try {
                    resolveBeforeNewSend();
                    callbacks.state(SendPhase.VALIDATING.nativeValue, null, null);
                    nativeRotateKey(ptr, phrase, operationId, callbacks);
                } catch (RuntimeException e) {
                    callbacks.failed(errorMessage(e));
                } finally {
                    Arrays.fill(phrase, (byte) 0);
                }
            });
        } catch (RejectedExecutionException e) {
            Arrays.fill(phrase, (byte) 0);
            callbacks.failed("NO_WALLET_ENGINE");
        }
    }

    @Keep
    private static class RotationCallbacks extends SendCallbacks {
        private final Utilities.Callback2<SecretPhrase, byte[]> onReplacement;

        RotationCallbacks(Utilities.Callback2<SendPhase, String> onState,
                          Utilities.Callback2<SecretPhrase, byte[]> onReplacement) {
            super((phase, error, transactionHash) -> onState.run(phase, error));
            this.onReplacement = onReplacement;
        }

        @Keep
        private void replacement(byte[] phrase, byte[] publicKey) {
            boolean interrupted = false;
            try {
                FutureTask<Void> delivery = new FutureTask<>(() -> {
                    SecretPhrase.deliver(new SecretPhrase(phrase), value -> onReplacement.run(value, publicKey));
                    return null;
                });
                AndroidUtilities.runOnUIThread(delivery);
                while (true) {
                    try {
                        delivery.get();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    } catch (ExecutionException e) {
                        throw new IllegalStateException("Could not save replacement recovery phrase");
                    }
                }
                if (interrupted) throw new IllegalStateException("Key rotation was interrupted before submission");
            } finally {
                Arrays.fill(phrase, (byte) 0);
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        transport.close();
        worker.execute(() -> {
            if (resolutionTask != null) resolutionTask.cancel(false);
            pendingSend = null;
            try {
                nativeClose(ptr);
            } finally {
                ptr = 0;
                worker.shutdown();
            }
        });
    }

    private static native long nativeCreate(int currentAccount, String address, byte[] publicKey, HttpTransport transport, String journalDirectory);
    private static native TL_wallet.walletTransaction nativeEmulateSend(long handle, String address, long amount, byte[] comment, boolean comment_encrypted);
    private static native byte[] nativeDecryptComment(long handle, byte[] phrase, String sender, String body);
    private static native byte[] nativeDecryptRawComment(byte[] phrase, String sender, String body);
    private static native TL_wallet.walletTransaction nativeSend(long handle, byte[] phrase, String address, long amount, byte[] comment, byte[] comment_recipient_public_key, String operationId, SendCallbacks callbacks);
    @Keep
    private static class PreparedSend {
        final TL_wallet.sendTransfer transfer;
        final String encryptedCommentBody;

        @Keep
        PreparedSend(String externalBoc, String internalBoc, String encryptedCommentBody) {
            transfer = new TL_wallet.sendTransfer();
            transfer.user_id = new TLRPC.TL_inputUserEmpty();
            transfer.random_id = Utilities.random.nextLong();
            transfer.data_normal = decodeTransferBoc(externalBoc);
            transfer.normalMessageBodyHash = nativeMessageBodyHash(externalBoc);
            transfer.data_gasless = internalBoc == null ? null : decodeTransferBoc(internalBoc);
            if (transfer.data_gasless != null) {
                transfer.gaslessMessageBodyHash = nativeMessageBodyHash(internalBoc);
            }
            this.encryptedCommentBody = encryptedCommentBody;
        }

        private static byte[] decodeTransferBoc(String boc) {
            final byte[] data = Base64.decode(boc, Base64.DEFAULT);
            if (data.length == 0 || data.length > 16 * 1024) {
                throw new IllegalArgumentException("WALLET_TRANSFER_DATA_INVALID");
            }
            return data;
        }
    }

    @Keep
    private static class PreparedRotation {
        final TL_wallet.sendTransfer transfer;
        final SecretPhrase newSecretPhrase;
        final byte[] newPublicKey;

        @Keep
        PreparedRotation(String externalBoc, String internalBoc, byte[] phrase, byte[] publicKey) {
            try {
                if (publicKey == null || publicKey.length != 32 || phrase == null || phrase.length == 0) {
                    throw new IllegalArgumentException("Invalid key rotation result");
                }
                transfer = new PreparedSend(externalBoc, internalBoc, null).transfer;
                newSecretPhrase = new SecretPhrase(phrase);
                newPublicKey = publicKey;
            } finally {
                if (phrase != null) Arrays.fill(phrase, (byte) 0);
            }
        }
    }

    private static native TL_wallet.walletTransaction nativeEmulateRotateKey(long handle, String address);
    private static native String nativeMessageBodyHash(String boc);
    private static native PreparedRotation nativePrepareRotateKey(long handle, byte[] phrase, String gaslessAddress);
    private static native PreparedSend nativePrepareSend(long handle, byte[] phrase, String address, long amount, byte[] comment, byte[] comment_recipient_public_key, String gaslessAddress, String operationId);
    private static native SendResolution nativeResolvePending(long handle);
    private static native void nativeRotateKey(long handle, byte[] phrase, String operationId, RotationCallbacks callbacks);
    private static native long nativeBalance(long handle);
    private static native void nativeClose(long handle);
    private static native byte[] nativeSecretPhraseToPublicKey(byte[] secretPhrase);
    private static native String nativeSecretPhraseToAddress(byte[] secretPhrase);
    private static native ImportedWalletProof nativeCreateImportProof(byte[] phrase, byte[] domain, byte[] payload, int timestamp, boolean forImport);
    private static native boolean nativeIsValidAddress(String address);
    private static native String nativeTransactionAddress(String address);

    @Keep
    private static TL_wallet.walletTransaction createTransaction(String address, long amount) {
        TL_wallet.walletTransaction transaction = new TL_wallet.walletTransaction();
        TL_wallet.walletTransactionPeerAddress peer = new TL_wallet.walletTransactionPeerAddress();
        peer.address = nativeTransactionAddress(address);
        transaction.peer = peer;
        transaction.amount = amount;
        return transaction;
    }

    @Keep
    private static void setTransactionPhase(TL_wallet.walletTransaction transaction, int nativePhase) {
        transaction.phase = SendPhase.fromNative(nativePhase);
        transaction.failed = isFailedPhase(transaction.phase);
        transaction.pending = !transaction.preview && isPendingPhase(transaction.phase);
    }

    @Keep
    public enum SendPhase {
        IDLE(1), VALIDATING(2), AUTHORIZING(3), PREPARING(4), PERSISTING(5),
        READY_TO_SUBMIT(6), SUBMITTING(7), SUBMISSION_UNKNOWN(8), SUBMITTED(9),
        HANDED_OFF(10), CONFIRMED(11), REPLACED(12), SEQUENCE_NUMBER_CONSUMED(13),
        EXPIRED(14), SUPERSEDED(15), FAILED(16), CANCELLED(17);

        private final int nativeValue;

        SendPhase(int nativeValue) {
            this.nativeValue = nativeValue;
        }

        private static SendPhase fromNative(int value) {
            for (SendPhase phase : values()) {
                if (phase.nativeValue == value) return phase;
            }
            throw new IllegalArgumentException("Unknown native send phase: " + value);
        }
    }

    public static boolean isFinalPhase(SendPhase phase) {
        if (phase == null) return false;
        switch (phase) {
            case CONFIRMED:
            case REPLACED:
            case SEQUENCE_NUMBER_CONSUMED:
            case EXPIRED:
            case SUPERSEDED:
            case FAILED:
            case CANCELLED:
                return true;
            default:
                return false;
        }
    }

    public static boolean isFailedPhase(SendPhase phase) {
        if (phase == null) return false;
        switch (phase) {
            case FAILED:
            case CANCELLED:
            case EXPIRED:
            case REPLACED:
            case SEQUENCE_NUMBER_CONSUMED:
            case SUPERSEDED:
                return true;
            default:
                return false;
        }
    }

    @Keep
    public static class HttpTransport {

        public final int currentAccount;
        private final HashMap<Long, Pending> pending = new HashMap<>();
        private boolean closed;

        private static class Pending {
            final CountDownLatch done = new CountDownLatch(1);
            int token;
            byte[] body;
            HostException error;

            synchronized void complete(byte[] body, HostException error) {
                if (done.getCount() == 0) return;
                this.body = body;
                this.error = error;
                done.countDown();
            }
        }

        @Keep
        private static class HostException extends RuntimeException {
            @Keep final int kind;

            HostException(int kind) {
                super("Wallet provider request failed");
                this.kind = kind;
            }
        }

        public HttpTransport(int currentAccount) {
            this.currentAccount = currentAccount;
        }

        @Keep
        public byte[] execute(long id, String url, boolean post, byte[] body, long timeoutMs) {
            final URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                throw new HostException(4);
            }
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"toncenter.com".equalsIgnoreCase(uri.getHost())
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || timeoutMs <= 0) {
                throw new HostException(4);
            }
            final TL_toncenter.performApiRequest request = new TL_toncenter.performApiRequest();
            request.post = post;
            request.endpoint = uri.getRawPath();
            request.query = uri.getRawQuery();
            request.payload = body.length == 0 ? null : new String(body, StandardCharsets.UTF_8);
            final Pending call = new Pending();
            final long start = System.nanoTime();
            try {
                synchronized (pending) {
                    if (closed) throw new HostException(6);
                    pending.put(id, call);
                    call.token = getConnectionsManager().sendRequestTyped(request, Runnable::run, (response, error) -> {
                        if (error != null) {
                            call.complete(null, new HostException(3));
                        } else if (response == null || response.response == null || response.response.data == null) {
                            call.complete(null, new HostException(7));
                        } else {
                            call.complete(response.response.data.getBytes(StandardCharsets.UTF_8), null);
                        }
                    }, MessagesController.getInstance(currentAccount).webFileDatacenterId, 0);
                }
                final long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs) - (System.nanoTime() - start);
                if (!call.done.await(Math.max(0, remaining), TimeUnit.NANOSECONDS)) {
                    call.complete(null, new HostException(2));
                }
                if (call.error != null) throw call.error;
                return call.body;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                call.complete(null, new HostException(6));
                throw new HostException(6);
            } finally {
                synchronized (pending) {
                    pending.remove(id);
                    if (call.error != null && call.token != 0) {
                        getConnectionsManager().cancelRequest(call.token, true);
                    }
                }
            }
        }

        @Keep
        public void cancel(long id) {
            synchronized (pending) {
                Pending call = pending.get(id);
                if (call != null) call.complete(null, new HostException(6));
            }
        }

        public void close() {
            synchronized (pending) {
                closed = true;
                for (Pending call : pending.values()) call.complete(null, new HostException(6));
            }
        }

        private ConnectionsManager getConnectionsManager() {
            return ConnectionsManager.getInstance(currentAccount);
        }
    }

}
