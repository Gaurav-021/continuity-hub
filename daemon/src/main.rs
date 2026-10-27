use std::path::PathBuf;
use std::sync::Arc;
use clap::Parser;
use tracing::{info, Level};
use tracing_subscriber::FmtSubscriber;

pub mod config;
pub mod discovery;
pub mod engine;
pub mod platform;
pub mod proto;
pub mod transport;

use config::{get_base_dir, Identity};
use engine::SyncEngine;
use platform::linux::WaylandClipboardBackend;
use transport::tofu::TofuStore;

#[derive(Parser, Debug)]
#[command(author, version, about = "Continuity Hub Daemon for Linux Wayland")]
struct Args {
    /// Custom port to listen and advertise on
    #[arg(short, long)]
    port: Option<u16>,

    /// Custom device name
    #[arg(short, long)]
    name: Option<String>,

    /// Custom config directory
    #[arg(short, long)]
    config_dir: Option<PathBuf>,

    /// Directly connect to a peer address (e.g. 127.0.0.1:52800)
    #[arg(long)]
    connect: Option<std::net::SocketAddr>,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let _ = rustls::crypto::ring::default_provider().install_default();

    let subscriber = FmtSubscriber::builder()
        .with_max_level(Level::INFO)
        .with_target(false)
        .finish();
    tracing::subscriber::set_global_default(subscriber)
        .expect("setting default subscriber failed");

    let args = Args::parse();
    let base_dir = args.config_dir.unwrap_or_else(get_base_dir);

    info!("Starting Continuity Hub Daemon...");
    info!("Base configuration directory: {:?}", base_dir);

    let mut identity = Identity::load_or_generate(&base_dir)?;
    if let Some(port) = args.port {
        identity.config.port = port;
    }
    if let Some(name) = args.name {
        identity.config.device_name = name;
    }

    info!(
        "Device Name: '{}', Device ID: '{}', Port: {}",
        identity.config.device_name, identity.config.device_id, identity.config.port
    );
    info!("Local Certificate Fingerprint (SHA-256): {}", identity.fingerprint);

    let tofu_store = TofuStore::new(&base_dir)?;
    let clipboard_backend = Arc::new(WaylandClipboardBackend::new());

    let engine = Arc::new(SyncEngine::new(
        Arc::new(identity),
        tofu_store,
        clipboard_backend,
    ));

    engine.clone().start().await?;

    if let Some(peer_addr) = args.connect {
        let engine_connect = Arc::clone(&engine);
        tokio::spawn(async move {
            tokio::time::sleep(tokio::time::Duration::from_millis(200)).await;
            if let Err(e) = engine_connect.connect_to_peer(peer_addr).await {
                tracing::error!("Failed direct connection to {}: {}", peer_addr, e);
            }
        });
    }

    info!("Continuity Daemon is running. Press Ctrl+C to terminate.");
    tokio::signal::ctrl_c().await?;
    info!("Continuity Daemon shutting down.");

    Ok(())
}
