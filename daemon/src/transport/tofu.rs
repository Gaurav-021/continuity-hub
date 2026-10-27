use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::sync::{Arc, RwLock};
use chrono::Utc;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
use rustls::{CertificateError, DistinguishedName, Error};
use rustls_pki_types::{CertificateDer, ServerName, UnixTime};
use serde::{Deserialize, Serialize};
use tracing::{error, info, warn};

use crate::config::calculate_fingerprint;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KnownDevice {
    pub device_id: String,
    pub device_name: String,
    pub cert_fingerprint: String,
    pub pinned_at: String,
    pub trusted: bool,
}

#[derive(Debug, Clone)]
pub struct TofuStore {
    file_path: PathBuf,
    devices: Arc<RwLock<HashMap<String, KnownDevice>>>,
}

#[derive(Debug)]
pub enum TofuVerification {
    Trusted(KnownDevice),
    FirstUsePinned(String),
    FingerprintMismatch {
        device_id: String,
        expected_fingerprint: String,
        presented_fingerprint: String,
    },
    Rejected,
}

impl TofuStore {
    pub fn new(base_dir: &std::path::Path) -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        let file_path = base_dir.join("known_devices.json");
        let mut map = HashMap::new();

        if file_path.exists() {
            let content = fs::read_to_string(&file_path)?;
            let list: Vec<KnownDevice> = serde_json::from_str(&content).unwrap_or_default();
            for dev in list {
                map.insert(dev.cert_fingerprint.clone(), dev);
            }
        }

        Ok(Self {
            file_path,
            devices: Arc::new(RwLock::new(map)),
        })
    }

    fn persist(&self) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let map = self.devices.read().unwrap();
        let list: Vec<KnownDevice> = map.values().cloned().collect();
        let data = serde_json::to_string_pretty(&list)?;
        fs::write(&self.file_path, data)?;
        Ok(())
    }

    pub fn verify_and_pin(
        &self,
        fingerprint: &str,
        suggested_device_id: Option<&str>,
        suggested_device_name: Option<&str>,
    ) -> Result<TofuVerification, Box<dyn std::error::Error + Send + Sync>> {
        let mut map = self.devices.write().unwrap();

        // 1. Check if fingerprint is already pinned
        if let Some(dev) = map.get(fingerprint) {
            if dev.trusted {
                return Ok(TofuVerification::Trusted(dev.clone()));
            } else {
                return Ok(TofuVerification::Rejected);
            }
        }

        // 2. Check if device_id is already pinned under a DIFFERENT fingerprint
        if let Some(id) = suggested_device_id {
            for (_, dev) in map.iter() {
                if dev.device_id == id && dev.cert_fingerprint != fingerprint {
                    warn!(
                        "TOFU WARNING: Device ID '{}' presented fingerprint '{}', but was previously pinned as '{}'! Possible MITM attack!",
                        id, fingerprint, dev.cert_fingerprint
                    );
                    return Ok(TofuVerification::FingerprintMismatch {
                        device_id: id.to_string(),
                        expected_fingerprint: dev.cert_fingerprint.clone(),
                        presented_fingerprint: fingerprint.to_string(),
                    });
                }
            }
        }

        // 3. First Use: Pin fingerprint
        let dev_id = suggested_device_id.unwrap_or("unknown-id").to_string();
        let dev_name = suggested_device_name.unwrap_or("Unknown Device").to_string();
        let new_dev = KnownDevice {
            device_id: dev_id,
            device_name: dev_name,
            cert_fingerprint: fingerprint.to_string(),
            pinned_at: Utc::now().to_rfc3339(),
            trusted: true,
        };

        info!("TOFU: Pinned new trusted device fingerprint: {}", fingerprint);
        map.insert(fingerprint.to_string(), new_dev);
        drop(map);
        let _ = self.persist();

        Ok(TofuVerification::FirstUsePinned(fingerprint.to_string()))
    }

    pub fn update_device_info(&self, fingerprint: &str, device_id: &str, device_name: &str) {
        let mut map = self.devices.write().unwrap();
        if let Some(dev) = map.get_mut(fingerprint) {
            dev.device_id = device_id.to_string();
            dev.device_name = device_name.to_string();
            drop(map);
            let _ = self.persist();
        }
    }
}

// Client-side Verifier (Validating Server Certificate)
#[derive(Debug)]
pub struct TofuServerCertVerifier {
    store: TofuStore,
}

impl TofuServerCertVerifier {
    pub fn new(store: TofuStore) -> Arc<Self> {
        Arc::new(Self { store })
    }
}

impl ServerCertVerifier for TofuServerCertVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, Error> {
        let fingerprint = calculate_fingerprint(end_entity.as_ref());

        match self.store.verify_and_pin(&fingerprint, None, None) {
            Ok(TofuVerification::Trusted(dev)) => {
                info!("TLS Handshake: Server certificate verified for device '{}' ({})", dev.device_name, fingerprint);
                Ok(ServerCertVerified::assertion())
            }
            Ok(TofuVerification::FirstUsePinned(_)) => {
                info!("TLS Handshake: TOFU accepted and pinned server certificate ({})", fingerprint);
                Ok(ServerCertVerified::assertion())
            }
            Ok(TofuVerification::FingerprintMismatch { expected_fingerprint, presented_fingerprint, .. }) => {
                error!("TLS Handshake rejected: Server certificate mismatch! Expected {}, got {}", expected_fingerprint, presented_fingerprint);
                Err(Error::InvalidCertificate(
                    CertificateError::ApplicationVerificationFailure,
                ))
            }
            Ok(TofuVerification::Rejected) => {
                error!("TLS Handshake rejected: Server device explicitly rejected ({})", fingerprint);
                Err(Error::InvalidCertificate(
                    CertificateError::ApplicationVerificationFailure,
                ))
            }
            Err(e) => {
                error!("TLS Handshake error during TOFU verification: {}", e);
                Err(Error::General(e.to_string()))
            }
        }
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        vec![
            rustls::SignatureScheme::RSA_PKCS1_SHA256,
            rustls::SignatureScheme::RSA_PKCS1_SHA384,
            rustls::SignatureScheme::RSA_PKCS1_SHA512,
            rustls::SignatureScheme::RSA_PSS_SHA256,
            rustls::SignatureScheme::RSA_PSS_SHA384,
            rustls::SignatureScheme::RSA_PSS_SHA512,
            rustls::SignatureScheme::ECDSA_NISTP256_SHA256,
            rustls::SignatureScheme::ECDSA_NISTP384_SHA384,
            rustls::SignatureScheme::ECDSA_NISTP521_SHA512,
            rustls::SignatureScheme::ED25519,
        ]
    }
}

// Server-side Verifier (Validating Client Certificate for mTLS)
#[derive(Debug)]
pub struct TofuClientCertVerifier {
    store: TofuStore,
}

impl TofuClientCertVerifier {
    pub fn new(store: TofuStore) -> Arc<Self> {
        Arc::new(Self { store })
    }
}

impl ClientCertVerifier for TofuClientCertVerifier {
    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }

    fn verify_client_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _now: UnixTime,
    ) -> Result<ClientCertVerified, Error> {
        let fingerprint = calculate_fingerprint(end_entity.as_ref());

        match self.store.verify_and_pin(&fingerprint, None, None) {
            Ok(TofuVerification::Trusted(dev)) => {
                info!("mTLS Handshake: Client certificate verified for device '{}' ({})", dev.device_name, fingerprint);
                Ok(ClientCertVerified::assertion())
            }
            Ok(TofuVerification::FirstUsePinned(_)) => {
                info!("mTLS Handshake: TOFU accepted and pinned client certificate ({})", fingerprint);
                Ok(ClientCertVerified::assertion())
            }
            Ok(TofuVerification::FingerprintMismatch { expected_fingerprint, presented_fingerprint, .. }) => {
                error!("mTLS Handshake rejected: Client certificate mismatch! Expected {}, got {}", expected_fingerprint, presented_fingerprint);
                Err(Error::InvalidCertificate(
                    CertificateError::ApplicationVerificationFailure,
                ))
            }
            Ok(TofuVerification::Rejected) => {
                error!("mTLS Handshake rejected: Client device explicitly untrusted ({})", fingerprint);
                Err(Error::InvalidCertificate(
                    CertificateError::ApplicationVerificationFailure,
                ))
            }
            Err(e) => {
                error!("mTLS Handshake error during TOFU verification: {}", e);
                Err(Error::General(e.to_string()))
            }
        }
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        vec![
            rustls::SignatureScheme::RSA_PKCS1_SHA256,
            rustls::SignatureScheme::RSA_PKCS1_SHA384,
            rustls::SignatureScheme::RSA_PKCS1_SHA512,
            rustls::SignatureScheme::RSA_PSS_SHA256,
            rustls::SignatureScheme::RSA_PSS_SHA384,
            rustls::SignatureScheme::RSA_PSS_SHA512,
            rustls::SignatureScheme::ECDSA_NISTP256_SHA256,
            rustls::SignatureScheme::ECDSA_NISTP384_SHA384,
            rustls::SignatureScheme::ECDSA_NISTP521_SHA512,
            rustls::SignatureScheme::ED25519,
        ]
    }
}
