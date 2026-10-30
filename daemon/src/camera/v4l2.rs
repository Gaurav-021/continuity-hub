use std::ffi::CString;
use std::path::PathBuf;
use tracing::{debug, info, warn};

pub const V4L2_PIX_FMT_JPEG: u32 = 0x4745504a; // 'J', 'P', 'E', 'G'
pub const V4L2_BUF_TYPE_VIDEO_OUTPUT: u32 = 2;
pub const V4L2_FIELD_NONE: u32 = 1;
pub const V4L2_COLORSPACE_SRGB: u32 = 8;
#[cfg(target_os = "linux")]
pub const VIDIOC_S_FMT: libc::c_ulong = 0xc0d05605;

#[cfg(target_os = "linux")]
#[repr(C)]
struct V4l2Format {
    type_: u32,
    fmt: [u8; 204],
}

pub struct V4l2LoopbackWriter {
    device_path: Option<PathBuf>,
    fd: Option<i32>,
    configured_width: u32,
    configured_height: u32,
    logged_missing: bool,
}

impl V4l2LoopbackWriter {
    pub fn new() -> Self {
        let mut writer = Self {
            device_path: None,
            fd: None,
            configured_width: 0,
            configured_height: 0,
            logged_missing: false,
        };

        writer.detect_device();
        writer
    }

    /// Check if a V4L2 loopback device is available (/dev/video10, /dev/video2, or CONTINUITY_V4L2_DEVICE)
    pub fn detect_device(&mut self) -> Option<PathBuf> {
        if let Some(ref path) = self.device_path {
            return Some(path.clone());
        }

        // 1. Check custom environment variable
        if let Ok(custom) = std::env::var("CONTINUITY_V4L2_DEVICE") {
            let p = PathBuf::from(custom);
            if p.exists() {
                self.device_path = Some(p.clone());
                return Some(p);
            }
        }

        // 2. Check standard v4l2loopback video device numbers
        let candidates = [
            "/dev/video10",
            "/dev/video20",
            "/dev/video2",
            "/dev/video3",
            "/dev/video4",
        ];

        for path_str in candidates {
            let p = PathBuf::from(path_str);
            if p.exists() {
                // Verify it can be opened for writing
                #[cfg(target_os = "linux")]
                unsafe {
                    let c_path = CString::new(path_str).unwrap();
                    let test_fd = libc::open(c_path.as_ptr(), libc::O_RDWR | libc::O_NONBLOCK);
                    if test_fd >= 0 {
                        libc::close(test_fd);
                        info!("Detected native V4L2 virtual webcam loopback device: {}", path_str);
                        self.device_path = Some(p.clone());
                        return Some(p);
                    }
                }
            }
        }

        if !self.logged_missing {
            self.logged_missing = true;
            info!(
                "No native V4L2 loopback device detected for hardware webcam emulation. \
                 To enable direct webcam in Zoom/Meet/Discord, load module: \
                 'sudo modprobe v4l2loopback devices=1 video_nr=10 card_label=\"Continuity Camera (Pixel 9 Pro)\" exclusive_caps=1'. \
                 Wireless HTTP stream is active at http://127.0.0.1:52802/live.mjpg"
            );
        }

        None
    }

    #[cfg(target_os = "linux")]
    fn open_device(&mut self) -> Result<i32, Box<dyn std::error::Error + Send + Sync>> {
        if let Some(fd) = self.fd {
            return Ok(fd);
        }

        let dev_path = self.detect_device().ok_or("No V4L2 loopback device available")?;
        let path_str = dev_path.to_str().ok_or("Invalid path string")?;
        let c_path = CString::new(path_str)?;

        unsafe {
            let fd = libc::open(c_path.as_ptr(), libc::O_RDWR);
            if fd < 0 {
                let err = std::io::Error::last_os_error();
                return Err(format!("Failed to open V4L2 device {:?}: {}", dev_path, err).into());
            }

            self.fd = Some(fd);
            info!("Opened V4L2 loopback device {:?} (fd: {})", dev_path, fd);
            Ok(fd)
        }
    }

    #[cfg(target_os = "linux")]
    pub fn configure_format(&mut self, width: u32, height: u32) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let fd = self.open_device()?;

        if self.configured_width == width && self.configured_height == height {
            return Ok(());
        }

        unsafe {
            let mut v4l2_fmt = V4l2Format {
                type_: V4L2_BUF_TYPE_VIDEO_OUTPUT,
                fmt: [0u8; 204],
            };

            // Pack struct v4l2_pix_format into beginning of fmt union
            // width: u32, height: u32, pixelformat: u32, field: u32, bytesperline: u32, sizeimage: u32, colorspace: u32
            let slice = &mut v4l2_fmt.fmt[..28];
            slice[0..4].copy_from_slice(&width.to_ne_bytes());
            slice[4..8].copy_from_slice(&height.to_ne_bytes());
            slice[8..12].copy_from_slice(&V4L2_PIX_FMT_JPEG.to_ne_bytes());
            slice[12..16].copy_from_slice(&V4L2_FIELD_NONE.to_ne_bytes());
            slice[16..20].copy_from_slice(&0u32.to_ne_bytes());
            slice[20..24].copy_from_slice(&(width * height * 2).to_ne_bytes());
            slice[24..28].copy_from_slice(&V4L2_COLORSPACE_SRGB.to_ne_bytes());

            let ret = libc::ioctl(fd, VIDIOC_S_FMT, &mut v4l2_fmt);
            if ret < 0 {
                let err = std::io::Error::last_os_error();
                warn!("VIDIOC_S_FMT failed on V4L2 loopback device: {}", err);
                return Err(err.into());
            }

            self.configured_width = width;
            self.configured_height = height;
            info!("Configured V4L2 virtual webcam format: {}x{} JPEG", width, height);
            Ok(())
        }
    }

    pub fn write_frame(&mut self, frame_data: &[u8], width: u32, height: u32) {
        #[cfg(target_os = "linux")]
        {
            if self.device_path.is_none() && self.detect_device().is_none() {
                return;
            }

            if self.configured_width != width || self.configured_height != height {
                let _ = self.configure_format(width, height);
            }

            if let Some(fd) = self.fd {
                unsafe {
                    let written = libc::write(fd, frame_data.as_ptr() as *const libc::c_void, frame_data.len());
                    if written < 0 {
                        let err = std::io::Error::last_os_error();
                        debug!("Error writing frame to V4L2 loopback device: {}", err);
                    }
                }
            }
        }
        #[cfg(not(target_os = "linux"))]
        {
            let _ = (frame_data, width, height);
        }
    }
}

impl Drop for V4l2LoopbackWriter {
    fn drop(&mut self) {
        #[cfg(target_os = "linux")]
        if let Some(fd) = self.fd {
            unsafe {
                libc::close(fd);
            }
        }
    }
}

impl Default for V4l2LoopbackWriter {
    fn default() -> Self {
        Self::new()
    }
}
