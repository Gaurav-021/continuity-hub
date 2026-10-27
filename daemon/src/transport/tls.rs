use std::sync::Arc;
use rustls::{ClientConfig, ServerConfig};

use crate::config::Identity;
use crate::transport::tofu::{TofuClientCertVerifier, TofuServerCertVerifier, TofuStore};

pub fn create_server_config(
    identity: &Identity,
    tofu_store: TofuStore,
) -> Result<Arc<ServerConfig>, Box<dyn std::error::Error + Send + Sync>> {
    let client_verifier = TofuClientCertVerifier::new(tofu_store);

    let mut config = ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()?
        .with_client_cert_verifier(client_verifier)
        .with_single_cert(
            vec![identity.cert_der.clone()],
            identity.key_der.clone_key(),
        )?;

    config.alpn_protocols = vec![b"continuity/1".to_vec()];
    Ok(Arc::new(config))
}

pub fn create_client_config(
    identity: &Identity,
    tofu_store: TofuStore,
) -> Result<Arc<ClientConfig>, Box<dyn std::error::Error + Send + Sync>> {
    let server_verifier = TofuServerCertVerifier::new(tofu_store);

    let mut config = ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()?
        .dangerous()
        .with_custom_certificate_verifier(server_verifier)
        .with_client_auth_cert(
            vec![identity.cert_der.clone()],
            identity.key_der.clone_key(),
        )?;

    config.alpn_protocols = vec![b"continuity/1".to_vec()];
    Ok(Arc::new(config))
}
