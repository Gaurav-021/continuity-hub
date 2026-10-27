use std::fs;
use std::path::{Path, PathBuf};
use directories::ProjectDirs;
use rcgen::{CertificateParams, DistinguishedName, DnType, KeyPair, SanType};
use rustls_pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppConfig {
    pub device_id: String,
    pub device_name: String,
    pub port: u16,
}

impl Default for AppConfig {
    fn default() -> Self {
        let hostname = std::env::var("HOSTNAME")
            .or_else(|_| std::env::var("USER").map(|u| format!("{}-desktop", u)))
            .unwrap_or_else(|_| "Linux-Desktop".to_string());

        Self {
            device_id: Uuid::new_v4().to_string(),
            device_name: hostname,
            port: 52800,
        }
    }
}

pub struct Identity {
    pub config: AppConfig,
    pub cert_der: CertificateDer<'static>,
    pub key_der: PrivateKeyDer<'static>,
    pub cert_pem: String,
    pub key_pem: String,
    pub fingerprint: String,
    pub base_dir: PathBuf,
}

pub fn get_base_dir() -> PathBuf {
    if let Some(proj_dirs) = ProjectDirs::from("com", "continuityhub", "continuity") {
        proj_dirs.config_dir().to_path_buf()
    } else {
        let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
        PathBuf::from(home).join(".config").join("continuity")
    }
}

pub fn calculate_fingerprint(cert_der: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(cert_der);
    let hash = hasher.finalize();
    hash.iter()
        .map(|byte| format!("{:02X}", byte))
        .collect::<Vec<String>>()
        .join(":")
}

impl Identity {
    pub fn load_or_generate(base_dir: &Path) -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        let keys_dir = base_dir.join("keys");
        fs::create_dir_all(&keys_dir)?;

        let config_file = base_dir.join("config.json");
        let config: AppConfig = if config_file.exists() {
            let data = fs::read_to_string(&config_file)?;
            serde_json::from_str(&data).unwrap_or_default()
        } else {
            let default_config = AppConfig::default();
            let json = serde_json::to_string_pretty(&default_config)?;
            fs::write(&config_file, json)?;
            default_config
        };

        let cert_file = keys_dir.join("cert.pem");
        let key_file = keys_dir.join("key.pem");

        let (cert_pem, key_pem, cert_der, key_der) = if cert_file.exists() && key_file.exists() {
            let cert_pem = fs::read_to_string(&cert_file)?;
            let key_pem = fs::read_to_string(&key_file)?;

            let cert_ders = rustls_pemfile::certs(&mut cert_pem.as_bytes())
                .collect::<Result<Vec<_>, _>>()?;
            let cert_der = cert_ders
                .into_iter()
                .next()
                .ok_or("No certificate found in cert.pem")?;

            let key_der = rustls_pemfile::private_key(&mut key_pem.as_bytes())?
                .ok_or("No private key found in key.pem")?;

            (cert_pem, key_pem, cert_der, key_der)
        } else {
            let mut params = CertificateParams::default();
            let mut dn = DistinguishedName::new();
            dn.push(DnType::CommonName, format!("Continuity-{}", &config.device_id[..8]));
            dn.push(DnType::OrganizationName, "Continuity Hub");
            params.distinguished_name = dn;
            params.subject_alt_names = vec![
                SanType::DnsName(config.device_name.clone().try_into()?),
                SanType::DnsName("localhost".to_string().try_into()?),
                SanType::IpAddress(std::net::IpAddr::V4(std::net::Ipv4Addr::new(127, 0, 0, 1))),
            ];

            let key_pair = KeyPair::generate()?;
            let cert = params.self_signed(&key_pair)?;
            let cert_pem = cert.pem();
            let key_pem = key_pair.serialize_pem();

            fs::write(&cert_file, &cert_pem)?;
            fs::write(&key_file, &key_pem)?;

            let cert_der = CertificateDer::from(cert.der().to_vec());
            let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key_pair.serialize_der()));

            (cert_pem, key_pem, cert_der, key_der)
        };

        let fingerprint = calculate_fingerprint(cert_der.as_ref());

        Ok(Self {
            config,
            cert_der,
            key_der,
            cert_pem,
            key_pem,
            fingerprint,
            base_dir: base_dir.to_path_buf(),
        })
    }
}
