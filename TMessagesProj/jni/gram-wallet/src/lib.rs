/* Modifications Copyright (C) 2026 Ettacent */


mod ton_connect;

pub use ton_connect::gram_ton_connect_crypto;

use base64::{Engine as _, engine::general_purpose::STANDARD};
use ton::{
    block_tlb::Msg,
    ton_core::{cell::TonCell, traits::tlb::TLB},
};

uniffi::setup_scaffolding!();
wallet_engine::uniffi_reexport_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum GramWalletError {
    #[error("Invalid Gram Wallet protocol data")]
    InvalidData,
    #[error("{message}")]
    Protocol { message: String },
}

type Result<T> = std::result::Result<T, GramWalletError>;

fn checked<T, E>(value: std::result::Result<T, E>) -> Result<T> {
    value.map_err(|_| GramWalletError::InvalidData)
}


#[uniffi::export]
pub fn is_valid_cell_boc(boc: String) -> bool {
    ton_connect_core::CellBoc::try_from(boc).is_ok()
}



#[uniffi::export]
pub fn message_body_hash(boc: String) -> Result<String> {
    let message = checked(Msg::<TonCell>::from_boc(checked(STANDARD.decode(boc))?))?;
    Ok(STANDARD.encode(checked(message.body.value.cell_hash())?.as_slice()))
}
