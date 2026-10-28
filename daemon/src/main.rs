use std::path::PathBuf;
use std::sync::Arc;
use clap::Parser;
use tracing::{error, info, Level};
use tracing_subscriber::FmtSubscriber;

pub mod config;
pub mod discovery;
pub mod engine;
pub mod input;
pub mod media;
pub mod notifications;
pub mod platform;
pub mod proto;
pub mod telephony;
pub mod transfer;
pub mod transport;

use config::{get_base_dir, Identity};
use engine::SyncEngine;
use input::InputManager;
use media::MediaManager;
use notifications::NotificationManager;
use platform::linux::WaylandClipboardBackend;
use telephony::TelephonyManager;
use transfer::FileTransferManager;
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

    /// Send a file to the connected peer
    #[arg(long)]
    send_file: Option<PathBuf>,

    /// Send a test notification alert to the connected peer
    #[arg(long)]
    notify_peer: Option<String>,

    /// Send an SMS message through connected phone (format: "phone_number:message body")
    #[arg(long)]
    send_sms: Option<String>,

    /// Print current phone telemetry status formatted for Waybar JSON and exit
    #[arg(long)]
    waybar: bool,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let args = Args::parse();

    if args.waybar {
        if let Ok(content) = std::fs::read_to_string("/tmp/continuity-waybar.json") {
            println!("{}", content.trim());
        } else {
            println!(r#"{{"text":"📱 Disconnected","alt":"disconnected","class":"disconnected","percentage":0,"tooltip":"No phone connected"}}"#);
        }
        return Ok(());
    }

    let _ = rustls::crypto::ring::default_provider().install_default();

    let subscriber = FmtSubscriber::builder()
        .with_max_level(Level::INFO)
        .with_target(false)
        .finish();
    tracing::subscriber::set_global_default(subscriber)
        .expect("setting default subscriber failed");

    let base_dir = args.config_dir.unwrap_or_else(get_base_dir);

    info!("Starting Continuity Hub Daemon (Phase 5: Telephony & Waybar)...");
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
    let file_transfer_mgr = Arc::new(FileTransferManager::new(
        None,
        identity.config.device_id.clone(),
    ));
    let notification_mgr = Arc::new(NotificationManager::new(
        identity.config.device_id.clone(),
    ));
    let input_mgr = Arc::new(InputManager::new());
    let telephony_mgr = Arc::new(TelephonyManager::new(
        identity.config.device_id.clone(),
    ));
    let media_mgr = MediaManager::new(identity.config.device_id.clone());

    info!("File downloads directory: {:?}", file_transfer_mgr.download_dir());

    let engine = Arc::new(SyncEngine::new(
        Arc::new(identity),
        tofu_store,
        clipboard_backend,
        file_transfer_mgr,
        notification_mgr,
        input_mgr,
        telephony_mgr,
        media_mgr,
    ));

    engine.clone().start().await?;

    if let Some(peer_addr) = args.connect {
        let engine_connect = Arc::clone(&engine);
        tokio::spawn(async move {
            tokio::time::sleep(tokio::time::Duration::from_millis(200)).await;
            if let Err(e) = engine_connect.connect_to_peer(peer_addr).await {
                error!("Failed direct connection to {}: {}", peer_addr, e);
            }
        });
    }

    if let Some(file_to_send) = args.send_file {
        let engine_send = Arc::clone(&engine);
        tokio::spawn(async move {
            info!("Waiting 1.5s for peer connection before dispatching file...");
            tokio::time::sleep(tokio::time::Duration::from_millis(1500)).await;
            match engine_send.send_file_to_peer(&file_to_send, None).await {
                Ok(transfer_id) => {
                    info!("File transfer {:?} completed successfully (ID: {})", file_to_send, transfer_id);
                }
                Err(e) => {
                    error!("File transfer failed: {}", e);
                }
            }
        });
    }

    if let Some(msg) = args.notify_peer {
        let engine_notify = Arc::clone(&engine);
        tokio::spawn(async move {
            info!("Waiting 1.5s for peer connection before dispatching notification...");
            tokio::time::sleep(tokio::time::Duration::from_millis(1500)).await;
            engine_notify.broadcast_notification("Desktop Alert", &msg, "Omarchy Linux").await;
            info!("Desktop notification alert sent: '{}'", msg);
        });
    }

    if let Some(sms_arg) = args.send_sms {
        let engine_sms = Arc::clone(&engine);
        tokio::spawn(async move {
            info!("Waiting 1.5s for peer connection before dispatching SMS...");
            tokio::time::sleep(tokio::time::Duration::from_millis(1500)).await;
            if let Some((number, body)) = sms_arg.split_once(':') {
                match engine_sms.send_sms(number.trim(), body.trim()).await {
                    Ok(_) => info!("SMS queued successfully to {}", number),
                    Err(e) => error!("Failed to send SMS: {}", e),
                }
            } else {
                error!("Invalid SMS format. Expected 'phone_number:message body'");
            }
        });
    }

    info!("Continuity Daemon is running. Press Ctrl+C to terminate.");
    tokio::signal::ctrl_c().await?;
    info!("Continuity Daemon shutting down.");

    Ok(())
}
