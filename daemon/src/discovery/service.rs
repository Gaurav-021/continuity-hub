use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use tokio::sync::mpsc;
use tracing::{info, warn};

use crate::config::AppConfig;

pub const SERVICE_TYPE: &str = "_continuity._tcp.local.";

#[derive(Debug, Clone)]
pub struct DiscoveredDevice {
    pub device_id: String,
    pub device_name: String,
    pub addresses: Vec<SocketAddr>,
    pub port: u16,
}

pub struct DiscoveryManager {
    mdns: ServiceDaemon,
    config: Arc<AppConfig>,
}

impl DiscoveryManager {
    pub fn new(config: Arc<AppConfig>) -> Result<Self, mdns_sd::Error> {
        let mdns = ServiceDaemon::new()?;
        Ok(Self { mdns, config })
    }

    pub fn advertise(&self) -> Result<(), mdns_sd::Error> {
        let instance_name = format!("{}-{}", self.config.device_name, &self.config.device_id[..8]);
        let host_name = format!("{}.local.", &self.config.device_id[..8]);

        let mut properties = HashMap::new();
        properties.insert("device_name".to_string(), self.config.device_name.clone());
        properties.insert("device_id".to_string(), self.config.device_id.clone());
        properties.insert("version".to_string(), "1".to_string());

        let service_info = ServiceInfo::new(
            SERVICE_TYPE,
            &instance_name,
            &host_name,
            "", // auto-detect IPs on host network interfaces
            self.config.port,
            properties,
        )?;

        self.mdns.register(service_info)?;
        info!(
            "mDNS Service '{}' registered on port {} ({})",
            instance_name, self.config.port, SERVICE_TYPE
        );
        Ok(())
    }

    pub fn browse(&self) -> Result<mpsc::Receiver<DiscoveredDevice>, mdns_sd::Error> {
        let receiver = self.mdns.browse(SERVICE_TYPE)?;
        let (tx, rx) = mpsc::channel(32);
        let my_device_id = self.config.device_id.clone();

        tokio::spawn(async move {
            info!("mDNS browsing started for {}", SERVICE_TYPE);
            while let Ok(event) = receiver.recv_async().await {
                match event {
                    ServiceEvent::ServiceResolved(info) => {
                        let properties = info.get_properties();
                        let device_id = properties
                            .get_property_val_str("device_id")
                            .unwrap_or_default()
                            .to_string();

                        // Skip discovering ourselves
                        if device_id.is_empty() || device_id == my_device_id {
                            continue;
                        }

                        let device_name = properties
                            .get_property_val_str("device_name")
                            .unwrap_or_else(|| info.get_fullname())
                            .to_string();

                        let port = info.get_port();
                        let mut socket_addrs = Vec::new();
                        for ip in info.get_addresses() {
                            socket_addrs.push(SocketAddr::new(*ip, port));
                        }

                        if !socket_addrs.is_empty() {
                            info!(
                                "Discovered peer via mDNS: '{}' ({}) at {:?}",
                                device_name, device_id, socket_addrs
                            );
                            let discovered = DiscoveredDevice {
                                device_id,
                                device_name,
                                addresses: socket_addrs,
                                port,
                            };
                            let _ = tx.send(discovered).await;
                        }
                    }
                    ServiceEvent::SearchStopped(service_type) => {
                        warn!("mDNS search stopped for {}", service_type);
                        break;
                    }
                    _ => {}
                }
            }
        });

        Ok(rx)
    }
}
