/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2015-2018.
 */

#include <algorithm>
#include <openssl/rand.h>
#include "ConnectionSession.h"
#include "MTProtoScheme.h"
#include "ConnectionsManager.h"
#include "NativeByteBuffer.h"

ConnectionSession::ConnectionSession(int32_t instance) {
    instanceNum = instance;
}

void ConnectionSession::recreateSession() {
    processedMessageIds.clear();
    messagesIdsForConfirmation.clear();
    processedSessionChanges.clear();
    nextSeqNo = 0;

    genereateNewSessionId();
}

void ConnectionSession::genereateNewSessionId() {
    int64_t newSessionId;
    RAND_bytes((uint8_t *) &newSessionId, 8);
#if USE_DEBUG_SESSION
    sessionId = (0xabcd000000000000L | (newSessionId & 0x0000ffffffffffffL));
#else
    sessionId = newSessionId;
#endif
}

void ConnectionSession::setSessionId(int64_t id) {
    sessionId = id;
}

int64_t ConnectionSession::getSessionId() {
    return sessionId;
}

uint32_t ConnectionSession::generateMessageSeqNo(bool increment) {
    uint32_t value = nextSeqNo;
    if (increment) {
        nextSeqNo++;
    }
    return value * 2 + (increment ? 1 : 0);
}

int32_t ConnectionSession::isMessageIdProcessed(int64_t messageId) {
    if (!(messageId & 1)) {
        return 1;
    }
    if (minProcessedMessageId != 0 && messageId < minProcessedMessageId) {
        return 2;
    }
    if (std::find(processedMessageIds.begin(), processedMessageIds.end(), messageId) != processedMessageIds.end()) {
        return 1;
    }
    return 0;
}

void ConnectionSession::addProcessedMessageId(int64_t messageId) {
    if (processedMessageIds.size() > 300) {
        std::sort(processedMessageIds.begin(), processedMessageIds.end());
        processedMessageIds.erase(processedMessageIds.begin(), processedMessageIds.begin() + 100);
        minProcessedMessageId = *(processedMessageIds.begin());
    }
    processedMessageIds.push_back(messageId);
}

bool ConnectionSession::hasMessagesToConfirm() {
    return !messagesIdsForConfirmation.empty();
}

void ConnectionSession::addMessageToConfirm(int64_t messageId) {
    if (std::find(messagesIdsForConfirmation.begin(), messagesIdsForConfirmation.end(), messageId) != messagesIdsForConfirmation.end()) {
        return;
    }
    messagesIdsForConfirmation.push_back(messageId);
}

NetworkMessage *ConnectionSession::generateConfirmationRequest() {
    if (messagesIdsForConfirmation.empty()) {
        return nullptr;
    }

    const size_t count = std::min(messagesIdsForConfirmation.size(),
            static_cast<size_t>((INT32_MAX - 56 - 255 - 24 - 12) / 8));
    auto msgAck = std::make_unique<TL_msgs_ack>();
    msgAck->msg_ids.assign(messagesIdsForConfirmation.begin(), messagesIdsForConfirmation.begin() + count);
    auto networkMessage = std::make_unique<NetworkMessage>();
    networkMessage->message = std::make_unique<TL_message>();
    networkMessage->message->msg_id = ConnectionsManager::getInstance(instanceNum).generateMessageId();
    networkMessage->message->seqno = generateMessageSeqNo(false);
    networkMessage->message->bytes = static_cast<int32_t>(12 + count * 8);
    networkMessage->message->body = std::move(msgAck);
    networkMessage->sessionId = getSessionId();
    return networkMessage.release();
}

void ConnectionSession::commitConfirmationRequest(const std::vector<int64_t> &ids, int64_t ownerSessionId) {
    if (ownerSessionId != sessionId) return;
    if (ids.size() <= messagesIdsForConfirmation.size()
            && std::equal(ids.begin(), ids.end(), messagesIdsForConfirmation.begin())) {
        messagesIdsForConfirmation.erase(messagesIdsForConfirmation.begin(), messagesIdsForConfirmation.begin() + ids.size());
        return;
    }
    auto end = std::remove_if(messagesIdsForConfirmation.begin(), messagesIdsForConfirmation.end(),
            [&](int64_t id) { return std::find(ids.begin(), ids.end(), id) != ids.end(); });
    messagesIdsForConfirmation.erase(end, messagesIdsForConfirmation.end());
}

bool ConnectionSession::isSessionProcessed(int64_t sessionId) {
    return std::find(processedSessionChanges.begin(), processedSessionChanges.end(), sessionId) != processedSessionChanges.end();
}

void ConnectionSession::addProcessedSession(int64_t sessionId) {
    processedSessionChanges.push_back(sessionId);
}
