/* Modifications Copyright (C) 2026 Ettacent */

use std::sync::Arc;

use base64::{Engine as _, engine::general_purpose::STANDARD};
use serde::Deserialize;
use serde_json::{Value, json};
use wallet_engine::*;
use zeroize::Zeroizing;

use crate::{GramWalletError, Result};

fn invalid(message: impl Into<String>) -> GramWalletError {
    GramWalletError::Protocol {
        message: message.into(),
    }
}

fn protocol<T, E: std::fmt::Display>(
    operation: &str,
    result: std::result::Result<T, E>,
) -> Result<T> {
    result.map_err(|error| invalid(format!("{operation}: {error}")))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Parameters {
    dapp_client_id: String,
    session_nonce: String,
    wallet_public_key: String,
    wallet_address: String,
    #[serde(default)]
    client_id: Option<String>,
    #[serde(default)]
    challenge: Option<String>,
    #[serde(default)]
    decrypt: Option<String>,
    #[serde(default)]
    encrypt: Option<Value>,
    #[serde(default)]
    account: bool,
    #[serde(default)]
    proof_domain: Option<String>,
    #[serde(default)]
    proof_payload: Option<String>,
    #[serde(default)]
    sign_data: Option<ton_connect_core::SignDataPayload>,
    #[serde(default)]
    sign_domain: Option<String>,
    #[serde(default)]
    timestamp: Option<u64>,
}

struct PhraseHost(Zeroizing<String>);

fn unavailable() -> ProtectedSecretHostError {
    ProtectedSecretHostError::Failed {
        kind: ProtectedSecretHostErrorKind::PolicyViolation,
        diagnostic: "Unsupported adapter operation".to_owned(),
    }
}

#[async_trait::async_trait]
impl WalletPlatformHost for PhraseHost {
    async fn read_protected_secret(
        &self,
        request: ProtectedSecretRead,
    ) -> std::result::Result<Vec<u8>, ProtectedSecretHostError> {
        if !matches!(
            request.reason,
            SecretAccessReason::SignTonConnectProof
                | SecretAccessReason::DeriveTonConnectSessionKey
                | SecretAccessReason::SignTonConnectData
        ) {
            return Err(unavailable());
        }
        Ok(self.0.as_bytes().to_vec())
    }
    async fn store_protected_secret(
        &self,
        _: ProtectedSecretStore,
    ) -> std::result::Result<(), ProtectedSecretHostError> {
        Err(unavailable())
    }
    async fn delete_protected_secret(
        &self,
        _: ProtectedSecretRef,
    ) -> std::result::Result<(), ProtectedSecretHostError> {
        Err(unavailable())
    }
    async fn load_journal(
        &self,
        _: JournalKey,
    ) -> std::result::Result<Option<JournalRecord>, JournalHostError> {
        Ok(None)
    }
    async fn compare_exchange_journal(
        &self,
        _: JournalCompareExchange,
    ) -> std::result::Result<JournalCompareExchangeResult, JournalHostError> {
        Err(JournalHostError::Failed {
            kind: JournalHostErrorKind::Unavailable,
            diagnostic: "No adapter journal".to_owned(),
        })
    }
}

struct Session {
    derived: Arc<TonConnectDerivedSession>,
    lifecycle: Arc<WalletLifecycle>,
    descriptor: WalletDescriptor,
}

impl Session {
    fn new(phrase: String, p: &Parameters) -> Result<Self> {
        let phrase = Zeroizing::new(phrase);
        let normalized = Zeroizing::new(
            phrase
                .split_whitespace()
                .map(str::to_lowercase)
                .collect::<Vec<_>>()
                .join(" "),
        );
        let anchor = rotation_mnemonic_public_key(normalized.to_string())
            .map_err(|_| invalid("Invalid wallet recovery phrase"))?;
        let lifecycle = WalletLifecycle::new(Arc::new(PhraseHost(normalized)));
        let descriptor = WalletDescriptor {
            record_id: "ton-connect".to_owned(),
            address: protocol(
                "Parse wallet address",
                TonAddressString::try_from(p.wallet_address.clone()),
            )?,
            public_key: anchor,
            network: Network::Mainnet,
            secret_ref: ProtectedSecretRef {
                value: "wallet:ton-connect:mnemonic".to_owned(),
            },
        };
        protocol(
            "Validate wallet account",
            lifecycle.ton_connect_account(descriptor.clone()),
        )?;
        let derived = protocol(
            "Derive TON Connect session",
            futures::executor::block_on(lifecycle.derive_ton_connect_session(
                TonConnectDerivedSessionRequest {
                    descriptor: descriptor.clone(),
                    dapp_client_id: p.dapp_client_id.to_lowercase(),
                    nonce: protocol("Decode session nonce", STANDARD.decode(&p.session_nonce))?,
                },
            )),
        )?;
        if protocol(
            "Decode wallet public key",
            STANDARD.decode(&p.wallet_public_key),
        )? != derived.signing_public_key()
        {
            return Err(invalid(
                "Recovery phrase does not match the current wallet signing key",
            ));
        }
        if let Some(expected) = &p.client_id {
            if !derived.public_key_hex().eq_ignore_ascii_case(expected) {
                return Err(invalid(
                    "Derived session key does not match the registered client ID",
                ));
            }
        }
        Ok(Self {
            derived,
            lifecycle,
            descriptor,
        })
    }

    fn client_id(&self) -> String {
        self.derived.public_key_hex()
    }

    fn encrypt(&self, event: &Value) -> Result<String> {
        let body = protocol("Serialize TON Connect response", serde_json::to_vec(event))?;
        Ok(STANDARD.encode(protocol(
            "Encrypt TON Connect response",
            self.derived.encrypt_envelope(body),
        )?))
    }
}



#[uniffi::export]
pub fn gram_ton_connect_crypto(phrase: String, parameters: String) -> Result<String> {
    let p: Parameters = protocol("Parse crypto parameters", serde_json::from_str(&parameters))?;
    let session = Session::new(phrase, &p)?;
    let mut output = json!({"clientId": session.client_id()});
    if let Some(challenge) = &p.challenge {
        let bytes = protocol("Decode challenge", STANDARD.decode(challenge))?;
        let answer = Zeroizing::new(protocol(
            "Open session challenge",
            session.derived.open_challenge(bytes),
        )?);
        output["challengeAnswer"] = json!(STANDARD.encode(answer.as_slice()));
    }
    if let Some(body) = &p.decrypt {
        let body = protocol("Decode encrypted body", STANDARD.decode(body))?;
        if body.len() < 40 || body.len() > 1024 * 1024 {
            return Err(invalid("Encrypted body must be between 40 bytes and 1 MiB"));
        }
        let plaintext = Zeroizing::new(protocol(
            "Decrypt request",
            session.derived.decrypt_envelope(body),
        )?);
        output["plaintext"] = json!(protocol(
            "Decode UTF-8 request",
            std::str::from_utf8(&plaintext)
        )?);
    }
    if let Some(message) = &p.encrypt {
        output["body"] = json!(session.encrypt(message)?);
    }
    if p.account {
        let account = protocol(
            "Get wallet account",
            session
                .lifecycle
                .ton_connect_account(session.descriptor.clone()),
        )?;
        output["account"] = json!({"name":"ton_addr", "address":account.address,
            "network":account.network, "walletStateInit":account.wallet_state_init,
            "publicKey":hex::encode(protocol("Decode signing key", STANDARD.decode(&p.wallet_public_key))?)});
    }
    if let Some(domain) = &p.proof_domain {
        if domain
            .trim_end_matches('.')
            .eq_ignore_ascii_case("telegram.org")
        {
            return Err(invalid(
                "The telegram.org proof domain is reserved for wallet import",
            ));
        }
        let timestamp = p
            .timestamp
            .filter(|t| *t > 0)
            .ok_or_else(|| invalid("Missing proof timestamp"))?;
        let payload = p
            .proof_payload
            .as_ref()
            .ok_or_else(|| invalid("Missing proof payload"))?;
        let signed = protocol(
            "Sign proof",
            futures::executor::block_on(session.lifecycle.sign_ton_connect_proof(
                TonConnectProofSignRequest {
                    descriptor: session.descriptor.clone(),
                    domain: domain.clone(),
                    timestamp,
                    payload: payload.clone(),
                },
            )),
        )?;
        output["signature"] = json!(STANDARD.encode(signed.signature));
    }
    if let Some(payload) = &p.sign_data {
        use ton_connect_core::SignDataPayload;
        let value = protocol("Serialize signing payload", serde_json::to_value(payload))?;
        let request = TonConnectSignDataRequest {
            payload: match payload {
                SignDataPayload::Text { text, .. } => {
                    TonConnectSignDataPayload::Text { text: text.clone() }
                }
                SignDataPayload::Binary { bytes, .. } => TonConnectSignDataPayload::Binary {
                    bytes: bytes.as_str().to_owned(),
                },
                SignDataPayload::Cell { schema, cell, .. } => TonConnectSignDataPayload::Cell {
                    schema: schema.clone(),
                    cell: cell.as_str().to_owned(),
                },
            },
            network: value
                .get("network")
                .and_then(Value::as_str)
                .map(str::to_owned),
            from: value.get("from").and_then(Value::as_str).map(str::to_owned),
        };
        let domain = p
            .sign_domain
            .as_deref()
            .filter(|d| !d.is_empty())
            .ok_or_else(|| invalid("Missing signing domain"))?;
        let timestamp = p
            .timestamp
            .filter(|t| *t > 0)
            .ok_or_else(|| invalid("Missing signing timestamp"))?;
        let signed = protocol(
            "Sign data",
            futures::executor::block_on(session.lifecycle.sign_ton_connect_data(
                TonConnectSignDataSignRequest {
                    descriptor: session.descriptor.clone(),
                    request,
                    domain: domain.to_owned(),
                    timestamp,
                },
            )),
        )?;
        let account = protocol(
            "Get signing account",
            session
                .lifecycle
                .ton_connect_account(session.descriptor.clone()),
        )?;
        output["signature"] = json!(STANDARD.encode(signed.signature));
        output["address"] = json!(account.address);
    }
    Ok(output.to_string())
}
