use bytes::BytesMut;
use futures::{SinkExt, StreamExt};
use prost::Message;
use std::io::{Error, ErrorKind};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio_util::codec::{Framed, LengthDelimitedCodec};

use crate::proto::Packet;

pub struct PacketFramed<S> {
    inner: Framed<S, LengthDelimitedCodec>,
}

impl<S> PacketFramed<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    pub fn new(stream: S) -> Self {
        let codec = LengthDelimitedCodec::builder()
            .length_field_length(4)
            .max_frame_length(16 * 1024 * 1024) // 16 MB max frame for large clipboard data
            .new_codec();

        Self {
            inner: Framed::new(stream, codec),
        }
    }

    pub async fn send_packet(&mut self, packet: &Packet) -> Result<(), Error> {
        let mut bytes = BytesMut::with_capacity(packet.encoded_len());
        packet.encode(&mut bytes).map_err(|e| {
            Error::new(ErrorKind::InvalidData, format!("Protobuf encode error: {}", e))
        })?;
        self.inner.send(bytes.freeze()).await
    }

    pub async fn next_packet(&mut self) -> Option<Result<Packet, Error>> {
        match self.inner.next().await {
            Some(Ok(bytes)) => {
                match Packet::decode(bytes) {
                    Ok(packet) => Some(Ok(packet)),
                    Err(e) => Some(Err(Error::new(
                        ErrorKind::InvalidData,
                        format!("Protobuf decode error: {}", e),
                    ))),
                }
            }
            Some(Err(e)) => Some(Err(e)),
            None => None,
        }
    }
}
