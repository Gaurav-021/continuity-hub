use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc as std_mpsc;
use std::sync::Arc;
use std::time::Duration;
use tokio::process::Command;
use tracing::{debug, error, info, warn};

use wayland_client::{
    protocol::{
        wl_pointer::{Axis, ButtonState},
        wl_seat::WlSeat,
    },
    Connection, Dispatch, QueueHandle,
};
use wayland_protocols_wlr::virtual_pointer::v1::client::{
    zwlr_virtual_pointer_manager_v1::ZwlrVirtualPointerManagerV1,
    zwlr_virtual_pointer_v1::ZwlrVirtualPointerV1,
};

use crate::proto::{
    mouse_button_event::{Button, State},
    InputMessage, KeyEvent, MouseButtonEvent, MouseMoveEvent, MouseScrollEvent,
};

// Linux input event codes
const BTN_LEFT: u32 = 0x110;   // 272
const BTN_RIGHT: u32 = 0x111;  // 273
const BTN_MIDDLE: u32 = 0x112; // 274

#[derive(Debug)]
pub enum PointerCommand {
    Move { dx: f64, dy: f64 },
    Button { button_code: u32, pressed: bool },
    Click { button_code: u32 },
    Scroll { dx: f64, dy: f64 },
}

struct WaylandState {
    manager: Option<ZwlrVirtualPointerManagerV1>,
    seat: Option<WlSeat>,
    pointer: Option<ZwlrVirtualPointerV1>,
}

impl Dispatch<ZwlrVirtualPointerManagerV1, ()> for WaylandState {
    fn event(
        _state: &mut Self,
        _proxy: &ZwlrVirtualPointerManagerV1,
        _event: <ZwlrVirtualPointerManagerV1 as wayland_client::Proxy>::Event,
        _data: &(),
        _conn: &Connection,
        _qhandle: &QueueHandle<Self>,
    ) {}
}

impl Dispatch<ZwlrVirtualPointerV1, ()> for WaylandState {
    fn event(
        _state: &mut Self,
        _proxy: &ZwlrVirtualPointerV1,
        _event: <ZwlrVirtualPointerV1 as wayland_client::Proxy>::Event,
        _data: &(),
        _conn: &Connection,
        _qhandle: &QueueHandle<Self>,
    ) {}
}

impl Dispatch<WlSeat, ()> for WaylandState {
    fn event(
        _state: &mut Self,
        _proxy: &WlSeat,
        _event: <WlSeat as wayland_client::Proxy>::Event,
        _data: &(),
        _conn: &Connection,
        _qhandle: &QueueHandle<Self>,
    ) {}
}

impl Dispatch<wayland_client::protocol::wl_registry::WlRegistry, ()> for WaylandState {
    fn event(
        state: &mut Self,
        registry: &wayland_client::protocol::wl_registry::WlRegistry,
        event: wayland_client::protocol::wl_registry::Event,
        _data: &(),
        _conn: &Connection,
        qh: &QueueHandle<Self>,
    ) {
        if let wayland_client::protocol::wl_registry::Event::Global {
            name,
            interface,
            version,
        } = event
        {
            if interface == "zwlr_virtual_pointer_manager_v1" {
                state.manager = Some(registry.bind::<ZwlrVirtualPointerManagerV1, _, _>(
                    name,
                    std::cmp::min(version, 2),
                    qh,
                    (),
                ));
            } else if interface == "wl_seat" && state.seat.is_none() {
                state.seat = Some(registry.bind::<WlSeat, _, _>(
                    name,
                    std::cmp::min(version, 7),
                    qh,
                    (),
                ));
            }
        }
    }
}

pub struct InputManager {
    pointer_tx: Option<std_mpsc::Sender<PointerCommand>>,
    wayland_available: Arc<AtomicBool>,
}

impl Default for InputManager {
    fn default() -> Self {
        Self::new()
    }
}

impl InputManager {
    pub fn new() -> Self {
        let (tx, rx) = std_mpsc::channel::<PointerCommand>();
        let wayland_available = Arc::new(AtomicBool::new(false));
        let wayland_flag = Arc::clone(&wayland_available);

        std::thread::Builder::new()
            .name("wayland-virtual-input".into())
            .spawn(move || {
                Self::run_wayland_worker(rx, wayland_flag);
            })
            .expect("failed to spawn wayland virtual input thread");

        Self {
            pointer_tx: Some(tx),
            wayland_available,
        }
    }

    fn run_wayland_worker(
        rx: std_mpsc::Receiver<PointerCommand>,
        wayland_flag: Arc<AtomicBool>,
    ) {
        let conn = match Connection::connect_to_env() {
            Ok(c) => c,
            Err(e) => {
                warn!("Unable to connect to Wayland display for virtual pointer: {}", e);
                return;
            }
        };

        let display = conn.display();
        let mut event_queue = conn.new_event_queue();
        let qh = event_queue.handle();
        let _registry = display.get_registry(&qh, ());

        let mut state = WaylandState {
            manager: None,
            seat: None,
            pointer: None,
        };

        if let Err(e) = event_queue.roundtrip(&mut state) {
            warn!("Failed Wayland registry roundtrip for virtual input: {}", e);
            return;
        }

        let mgr = match state.manager.as_ref() {
            Some(m) => m,
            None => {
                warn!("Compositor does not support zwlr_virtual_pointer_manager_v1");
                return;
            }
        };

        let seat = state.seat.as_ref();
        let pointer = mgr.create_virtual_pointer(seat, &qh, ());
        state.pointer = Some(pointer);

        wayland_flag.store(true, Ordering::SeqCst);
        info!("Wayland Virtual Pointer initialized successfully (sub-millisecond latency active)");

        let ptr = state.pointer.as_ref().unwrap();

        while let Ok(cmd) = rx.recv() {
            let time = 0u32;
            match cmd {
                PointerCommand::Move { dx, dy } => {
                    ptr.motion(time, dx, dy);
                    ptr.frame();
                    let _ = conn.flush();
                }
                PointerCommand::Button { button_code, pressed } => {
                    let bstate = if pressed {
                        ButtonState::Pressed
                    } else {
                        ButtonState::Released
                    };
                    ptr.button(time, button_code, bstate);
                    ptr.frame();
                    let _ = conn.flush();
                }
                PointerCommand::Click { button_code } => {
                    ptr.button(time, button_code, ButtonState::Pressed);
                    ptr.frame();
                    let _ = conn.flush();

                    std::thread::sleep(Duration::from_millis(20));

                    ptr.button(time, button_code, ButtonState::Released);
                    ptr.frame();
                    let _ = conn.flush();
                }
                PointerCommand::Scroll { dx, dy } => {
                    if dy.abs() > 0.001 {
                        ptr.axis(time, Axis::VerticalScroll, dy);
                    }
                    if dx.abs() > 0.001 {
                        ptr.axis(time, Axis::HorizontalScroll, dx);
                    }
                    ptr.frame();
                    let _ = conn.flush();
                }
            }
        }
    }

    pub fn is_wayland_active(&self) -> bool {
        self.wayland_available.load(Ordering::Relaxed)
    }

    pub async fn handle_message(&self, message: InputMessage) {
        if let Some(event) = message.event {
            match event {
                crate::proto::input_message::Event::MouseMove(m) => {
                    self.handle_mouse_move(m).await;
                }
                crate::proto::input_message::Event::MouseButton(b) => {
                    self.handle_mouse_button(b).await;
                }
                crate::proto::input_message::Event::MouseScroll(s) => {
                    self.handle_mouse_scroll(s).await;
                }
                crate::proto::input_message::Event::KeyEvent(k) => {
                    self.handle_key_event(k).await;
                }
            }
        }
    }

    pub async fn handle_mouse_move(&self, event: MouseMoveEvent) {
        let dx = event.dx as f64;
        let dy = event.dy as f64;

        if let Some(ref tx) = self.pointer_tx {
            if self.is_wayland_active() {
                let _ = tx.send(PointerCommand::Move { dx, dy });
                return;
            }
        }

        // Fallback: hyprctl cursorpos + dispatch movecursor
        Self::fallback_hyprctl_move(dx, dy).await;
    }

    pub async fn handle_mouse_button(&self, event: MouseButtonEvent) {
        let button_code = match Button::try_from(event.button) {
            Ok(Button::Left) => BTN_LEFT,
            Ok(Button::Right) => BTN_RIGHT,
            Ok(Button::Middle) => BTN_MIDDLE,
            _ => BTN_LEFT,
        };

        if let Some(ref tx) = self.pointer_tx {
            if self.is_wayland_active() {
                match State::try_from(event.state) {
                    Ok(State::Press) => {
                        let _ = tx.send(PointerCommand::Button {
                            button_code,
                            pressed: true,
                        });
                    }
                    Ok(State::Release) => {
                        let _ = tx.send(PointerCommand::Button {
                            button_code,
                            pressed: false,
                        });
                    }
                    _ => {
                        let _ = tx.send(PointerCommand::Click { button_code });
                    }
                }
            }
        }
    }

    pub async fn handle_mouse_scroll(&self, event: MouseScrollEvent) {
        let dx = event.dx as f64;
        let dy = event.dy as f64;

        if let Some(ref tx) = self.pointer_tx {
            if self.is_wayland_active() {
                let _ = tx.send(PointerCommand::Scroll { dx, dy });
            }
        }
    }

    pub async fn handle_key_event(&self, event: KeyEvent) {
        // 1. Text typing
        if !event.text.is_empty() {
            debug!("Typing text via wtype: '{}'", event.text);
            let mut cmd = Command::new("wtype");
            cmd.arg("--").arg(&event.text);
            if let Err(e) = cmd.output().await {
                error!("Failed executing wtype for text: {}", e);
            }
            return;
        }

        // 2. Special Key or Key with Modifiers
        if !event.key_name.is_empty() {
            let mut cmd = Command::new("wtype");

            // Normalize modifiers
            for m in &event.modifiers {
                let norm = m.trim().to_lowercase();
                let mod_name = match norm.as_str() {
                    "ctrl" | "control" => "ctrl",
                    "alt" => "alt",
                    "shift" => "shift",
                    "win" | "super" | "logo" => "logo",
                    "capslock" => "capslock",
                    _ => continue,
                };
                cmd.arg("-M").arg(mod_name);
            }

            // Map key name to standard XKB key identifier
            let xkb_key = match event.key_name.as_str() {
                "Enter" | "Return" => "Return",
                "Backspace" | "BackSpace" => "BackSpace",
                "Tab" => "Tab",
                "Escape" | "Esc" => "Escape",
                "Space" | "space" => "space",
                "Up" | "ArrowUp" => "Up",
                "Down" | "ArrowDown" => "Down",
                "Left" | "ArrowLeft" => "Left",
                "Right" | "ArrowRight" => "Right",
                "Home" => "Home",
                "End" => "End",
                "PageUp" | "Page_Up" => "Page_Up",
                "PageDown" | "Page_Down" => "Page_Down",
                "Delete" | "Del" => "Delete",
                other => other,
            };

            let state = crate::proto::key_event::KeyState::try_from(event.state)
                .unwrap_or(crate::proto::key_event::KeyState::KeyClick);

            match state {
                crate::proto::key_event::KeyState::KeyPress => {
                    cmd.arg("-P").arg(xkb_key);
                }
                crate::proto::key_event::KeyState::KeyRelease => {
                    cmd.arg("-p").arg(xkb_key);
                }
                _ => {
                    cmd.arg("-k").arg(xkb_key);
                }
            }

            // Release modifiers
            for m in event.modifiers.iter().rev() {
                let norm = m.trim().to_lowercase();
                let mod_name = match norm.as_str() {
                    "ctrl" | "control" => "ctrl",
                    "alt" => "alt",
                    "shift" => "shift",
                    "win" | "super" | "logo" => "logo",
                    "capslock" => "capslock",
                    _ => continue,
                };
                cmd.arg("-m").arg(mod_name);
            }

            if let Err(e) = cmd.output().await {
                error!("Failed executing wtype for key '{}': {}", event.key_name, e);
            }
        }
    }

    async fn fallback_hyprctl_move(dx: f64, dy: f64) {
        if let Ok(output) = Command::new("hyprctl").arg("cursorpos").output().await {
            if let Ok(text) = String::from_utf8(output.stdout) {
                let parts: Vec<&str> = text.trim().split(',').collect();
                if parts.len() == 2 {
                    if let (Ok(x), Ok(y)) = (parts[0].trim().parse::<f64>(), parts[1].trim().parse::<f64>()) {
                        let new_x = (x + dx).round() as i64;
                        let new_y = (y + dy).round() as i64;
                        let _ = Command::new("hyprctl")
                            .arg("dispatch")
                            .arg("movecursor")
                            .arg(format!("{} {}", new_x, new_y))
                            .output()
                            .await;
                    }
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_input_manager_initialization_and_events() {
        let mgr = InputManager::new();
        // Allow wayland worker thread to initialize
        tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;

        // Test sending mouse move
        mgr.handle_mouse_move(MouseMoveEvent { dx: 1.0, dy: 1.0 }).await;

        // Test sending mouse buttons
        mgr.handle_mouse_button(MouseButtonEvent {
            button: Button::Left as i32,
            state: State::Click as i32,
        }).await;

        mgr.handle_mouse_button(MouseButtonEvent {
            button: Button::Right as i32,
            state: State::Click as i32,
        }).await;

        // Test sending mouse scroll
        mgr.handle_mouse_scroll(MouseScrollEvent { dx: 0.0, dy: 2.0 }).await;

        // Test key event with special key
        mgr.handle_key_event(KeyEvent {
            key_name: "Shift_L".to_string(),
            text: String::new(),
            state: crate::proto::key_event::KeyState::KeyClick as i32,
            modifiers: vec![],
        }).await;
    }
}

