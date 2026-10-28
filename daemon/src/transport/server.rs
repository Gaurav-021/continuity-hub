use std::net::SocketAddr;
use std::sync::Arc;
use tokio::net::TcpListener;
use tokio::sync::mpsc;
use tokio_rustls::TlsAcceptor;
use tracing::{error, info, warn};

use crate::input::InputManager;
use crate::media::MediaManager;
use crate::notifications::NotificationManager;
use crate::proto::ClipboardPayload;
use crate::telephony::TelephonyManager;
use crate::transfer::FileTransferManager;
use crate::transport::peer::{run_peer_connection, PeerInfo, PeerSession};
use crate::transport::tofu::TofuStore;

pub struct TlsServer {
    listener: TcpListener,
    acceptor: TlsAcceptor,
    my_info: PeerInfo,
    tofu_store: TofuStore,
    file_transfer_mgr: Arc<FileTransferManager>,
    notification_mgr: Arc<NotificationManager>,
    input_mgr: Arc<InputManager>,
    telephony_mgr: Arc<TelephonyManager>,
    media_mgr: Arc<MediaManager>,
}

impl TlsServer {
    #[allow(clippy::too_many_arguments)]
    pub async fn bind(
        addr: SocketAddr,
        server_config: Arc<rustls::ServerConfig>,
        my_info: PeerInfo,
        tofu_store: TofuStore,
        file_transfer_mgr: Arc<FileTransferManager>,
        notification_mgr: Arc<NotificationManager>,
        input_mgr: Arc<InputManager>,
        telephony_mgr: Arc<TelephonyManager>,
        media_mgr: Arc<MediaManager>,
    ) -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        let listener = TcpListener::bind(addr).await?;
        let acceptor = TlsAcceptor::from(server_config);
        info!("TLS TCP Server listening on {}", addr);
        Ok(Self {
            listener,
            acceptor,
            my_info,
            tofu_store,
            file_transfer_mgr,
            notification_mgr,
            input_mgr,
            telephony_mgr,
            media_mgr,
        })
    }

    pub async fn run(
        self,
        clipboard_tx: mpsc::Sender<ClipboardPayload>,
        register_peer_tx: mpsc::Sender<PeerSession>,
    ) {
        loop {
            match self.listener.accept().await {
                Ok((tcp_stream, remote_addr)) => {
                    let _ = tcp_stream.set_nodelay(true);
                    let acceptor = self.acceptor.clone();
                    let my_info = self.my_info.clone();
                    let clip_tx = clipboard_tx.clone();
                    let reg_tx = register_peer_tx.clone();
                    let tofu_store = self.tofu_store.clone();
                    let file_transfer_mgr = Arc::clone(&self.file_transfer_mgr);
                    let notification_mgr = Arc::clone(&self.notification_mgr);
                    let input_mgr = Arc::clone(&self.input_mgr);
                    let telephony_mgr = Arc::clone(&self.telephony_mgr);
                    let media_mgr = Arc::clone(&self.media_mgr);

                    tokio::spawn(async move {
                        info!("Incoming connection from {}", remote_addr);
                        match acceptor.accept(tcp_stream).await {
                            Ok(tls_stream) => {
                                info!("TLS handshake successful for incoming connection from {}", remote_addr);
                                if let Err(e) = run_peer_connection(
                                    tls_stream,
                                    my_info,
                                    false,
                                    clip_tx,
                                    reg_tx,
                                    tofu_store,
                                    file_transfer_mgr,
                                    notification_mgr,
                                    input_mgr,
                                    telephony_mgr,
                                    media_mgr,
                                )
                                .await
                                {
                                    warn!("Connection with {} terminated: {}", remote_addr, e);
                                }
                            }
                            Err(e) => {
                                error!("TLS accept error from {}: {}", remote_addr, e);
                            }
                        }
                    });
                }
                Err(e) => {
                    error!("TCP listener accept error: {}", e);
                }
            }
        }
    }
}
